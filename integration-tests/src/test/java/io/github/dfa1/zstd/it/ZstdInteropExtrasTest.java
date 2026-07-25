package io.github.dfa1.zstd.it;

import com.github.luben.zstd.ZstdCompressCtx;
import io.github.dfa1.zstd.ZstdDictionaryId;
import io.github.dfa1.zstd.Zstd;
import io.github.dfa1.zstd.ZstdByteSize;
import io.github.dfa1.zstd.ZstdCompressionLevel;
import io.github.dfa1.zstd.ZstdDictionary;
import io.github.dfa1.zstd.ZstdException;
import io.github.dfa1.zstd.ZstdFrame;
import io.github.dfa1.zstd.ZstdFrameHeader;
import io.github.dfa1.zstd.ZstdFrameType;
import io.github.dfa1.zstd.ZstdInputStream;
import io.github.dfa1.zstd.ZstdOutputStream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static io.github.dfa1.zstd.it.ItTestSupport.random;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Interop gaps the round-trip suite does not cover: checksum trailers, skippable
/// frames, multi-frame concatenation, foreign frame-header parsing, and streaming
/// across small write/read chunks. Reference binding is zstd-jni (luben).
class ZstdInteropExtrasTest {

    private static byte[] javaStreamDecode(byte[] frame) {
        try (ZstdInputStream in = new ZstdInputStream(new ByteArrayInputStream(frame))) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] jniStreamDecode(byte[] frame) {
        try (var in = new com.github.luben.zstd.ZstdInputStream(new ByteArrayInputStream(frame))) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /// The 4-byte content checksum must be written and verified compatibly in
    /// both directions, and a corrupted payload under a checksum must be rejected.
    @Nested
    class Checksum {

        private final byte[] data = "checksum payload ".repeat(4096).getBytes(StandardCharsets.UTF_8);

        @Test
        void javaChecksumDecodedByJni() {
            // Given
            byte[] frame;
            try (io.github.dfa1.zstd.ZstdCompressContext ctx = new io.github.dfa1.zstd.ZstdCompressContext()) {
                frame = ctx.checksum(true).compress(data);
            }

            // When
            byte[] restored = com.github.luben.zstd.Zstd.decompress(frame, data.length);

            // Then
            assertThat(ZstdFrame.header(frame).hasChecksum()).isTrue();
            assertThat(restored).isEqualTo(data);
        }

        @Test
        void jniChecksumDecodedByJava() {
            // Given
            byte[] frame;
            try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
                frame = ctx.setChecksum(true).compress(data);
            }

            // When
            byte[] restored = javaStreamDecode(frame);

            // Then
            assertThat(ZstdFrame.header(frame).hasChecksum()).isTrue();
            assertThat(restored).isEqualTo(data);
        }

        @Test
        void javaRejectsCorruptedChecksum() {
            // Given
            byte[] frame;
            try (io.github.dfa1.zstd.ZstdCompressContext ctx = new io.github.dfa1.zstd.ZstdCompressContext()) {
                frame = ctx.checksum(true).compress(data);
            }
            frame[frame.length / 2] ^= 0x7F;

            // When
            ThrowingCallable result = () -> javaStreamDecode(frame);

            // Then
            assertThatThrownBy(result).isInstanceOfAny(ZstdException.class, UncheckedIOException.class);
        }
    }

    /// Skippable frames carry application metadata the decoder must pass over. A
    /// skippable frame written by this library must be recognized and skipped by
    /// the zstd-jni stream decoder, leaving the following real frame intact.
    @Nested
    class Skippable {

        @Test
        void jniStreamSkipsJavaSkippableFrame() {
            // Given
            byte[] payload = "after the skippable frame ".repeat(1000).getBytes(StandardCharsets.UTF_8);
            byte[] meta = "sidecar-metadata".getBytes(StandardCharsets.UTF_8);
            byte[] skippable = ZstdFrame.writeSkippableFrame(meta, 0);
            byte[] real = Zstd.compress(payload, ZstdCompressionLevel.DEFAULT);

            // When
            byte[] restored = jniStreamDecode(concat(skippable, real));

            // Then
            assertThat(restored).isEqualTo(payload);
        }

        @Test
        void javaParsesItsOwnSkippableFrameHeader() {
            // Given
            byte[] meta = "sidecar".getBytes(StandardCharsets.UTF_8);
            byte[] skippable = ZstdFrame.writeSkippableFrame(meta, 5);

            // When
            ZstdFrameHeader header = ZstdFrame.header(skippable);

            // Then
            assertThat(ZstdFrame.isSkippableFrame(skippable)).isTrue();
            assertThat(header.frameType()).isEqualTo(ZstdFrameType.SKIPPABLE);
            assertThat(ZstdFrame.readSkippableFrame(skippable).content()).isEqualTo(meta);
        }
    }

    /// zstd streaming concatenates adjacent frames transparently. Two frames from
    /// one binding must decode as the joined payload through the other's streamer.
    @Nested
    class MultiFrame {

        private final byte[] a = "first frame body ".repeat(2000).getBytes(StandardCharsets.UTF_8);
        private final byte[] b = "second frame body ".repeat(2000).getBytes(StandardCharsets.UTF_8);

        @Test
        void javaFramesConcatReadByJniStream() {
            // Given
            byte[] joined = concat(
                    Zstd.compress(a, ZstdCompressionLevel.DEFAULT),
                    Zstd.compress(b, ZstdCompressionLevel.DEFAULT));

            // When
            byte[] restored = jniStreamDecode(joined);

            // Then
            assertThat(restored).isEqualTo(concat(a, b));
        }

        @Test
        void jniFramesConcatReadByJavaStream() {
            // Given
            byte[] joined = concat(
                    com.github.luben.zstd.Zstd.compress(a, ZstdCompressionLevel.DEFAULT.value()),
                    com.github.luben.zstd.Zstd.compress(b, ZstdCompressionLevel.DEFAULT.value()));

            // When
            byte[] restored = javaStreamDecode(joined);

            // Then
            assertThat(restored).isEqualTo(concat(a, b));
        }
    }

    /// This library must read the header of a frame produced elsewhere: content
    /// size when pledged, and the dictionary id when one was used.
    @Nested
    class FrameHeader {

        private final byte[] data = "header introspection ".repeat(3000).getBytes(StandardCharsets.UTF_8);

        @Test
        void javaReadsContentSizeFromJniFrame() {
            // Given
            byte[] frame;
            try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
                frame = ctx.setContentSize(true).compress(data);
            }

            // When
            ZstdFrameHeader header = ZstdFrame.header(frame);

            // Then
            assertThat(header.frameType()).isEqualTo(ZstdFrameType.STANDARD);
            assertThat(header.contentSize()).hasValue(new ZstdByteSize(data.length));
        }

        @Test
        void javaReadsDictIdFromJniDictFrame() {
            // Given
            ZstdDictionary dict = trainDict();
            var jniDict = new com.github.luben.zstd.ZstdDictCompress(
                    dict.toByteArray(), ZstdCompressionLevel.DEFAULT.value());
            byte[] frame = com.github.luben.zstd.Zstd.compress(sample(7), jniDict);

            // When
            ZstdDictionaryId dictId = ZstdFrame.dictId(frame);

            // Then
            assertThat(dictId)
                    .isEqualTo(dict.id())
                    .isNotEqualTo(ZstdDictionaryId.NONE);
        }

        private ZstdDictionary trainDict() {
            List<byte[]> samples = new ArrayList<>();
            for (int i = 0; i < 3000; i++) {
                samples.add(sample(i));
            }
            return ZstdDictionary.train(samples, ZstdByteSize.ofKiB(8));
        }

        private byte[] sample(int i) {
            return ("{\"id\":" + i + ",\"user\":\"u" + (i % 30) + "\",\"event\":\"click\"}")
                    .getBytes(StandardCharsets.UTF_8);
        }
    }

    /// Streaming must survive being driven one tiny chunk at a time — exercising
    /// internal buffer refills and flush boundaries — across a spread of payloads.
    @Nested
    class ChunkedStreaming {

        private static final int CHUNK = 7;

        static Stream<Arguments> payloads() {
            Random r = new Random(0x5EED);
            return Stream.of(
                    Arguments.of("empty", new byte[0]),
                    Arguments.of("one-byte", new byte[]{42}),
                    Arguments.of("text", "stream me ".repeat(5000).getBytes(StandardCharsets.UTF_8)),
                    Arguments.of("random-64k", random(r, 64 * 1024)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("payloads")
        void javaChunkedWriteJniRead(String name, byte[] data) throws IOException {
            // Given
            ByteArrayOutputStream sink = new ByteArrayOutputStream();

            // When
            try (ZstdOutputStream zout = new ZstdOutputStream(sink, new ZstdCompressionLevel(7))) {
                writeInChunks(zout, data);
            }

            // Then
            assertThat(jniStreamDecode(sink.toByteArray())).isEqualTo(data);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("payloads")
        void jniChunkedWriteJavaRead(String name, byte[] data) throws IOException {
            // Given
            ByteArrayOutputStream sink = new ByteArrayOutputStream();

            // When
            try (var zout = new com.github.luben.zstd.ZstdOutputStream(sink)) {
                writeInChunks(zout, data);
            }

            // Then
            assertThat(javaStreamDecode(sink.toByteArray())).isEqualTo(data);
        }

        private static void writeInChunks(java.io.OutputStream out, byte[] data) throws IOException {
            for (int off = 0; off < data.length; off += CHUNK) {
                out.write(data, off, Math.min(CHUNK, data.length - off));
                out.flush();
            }
        }
    }
}
