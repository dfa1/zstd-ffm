package io.github.dfa1.zstd;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static io.github.dfa1.zstd.ZstdTestSupport.bytesOf;
import static io.github.dfa1.zstd.ZstdTestSupport.sample;
import static io.github.dfa1.zstd.ZstdTestSupport.segmentOf;
import static io.github.dfa1.zstd.ZstdTestSupport.trainDictionary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The `Zstd.new*(...)` factories that replaced the public constructors of the
/// native dictionary and stream types (#143): every overload yields a usable
/// object configured as its parameters say.
class ZstdNativeFactoryTest {

    private static final ZstdCompressionLevel LEVEL = ZstdCompressionLevel.of(7);

    private static ZstdDictionary dict;
    private static byte[] payload;

    @BeforeAll
    static void trainOnce() {
        dict = trainDictionary(200);
        payload = sample(7);
    }

    @Nested
    class NewCompressDictionary {

        @Test
        void fromDictionaryUsesDefaultLevel() {
            // Given
            ZstdDictionary source = dict;

            // When
            try (ZstdCompressDictionary sut = Zstd.newCompressDictionary(source)) {

                // Then
                assertThat(sut.level()).isEqualTo(ZstdCompressionLevel.DEFAULT);
                assertThat(sut.id()).isEqualTo(source.id());
                assertThat(roundTrip(sut)).isEqualTo(payload);
            }
        }

        @Test
        void fromDictionaryAndLevelKeepsLevel() {
            // Given
            ZstdDictionary source = dict;

            // When
            try (ZstdCompressDictionary sut = Zstd.newCompressDictionary(source, LEVEL)) {

                // Then
                assertThat(sut.level()).isEqualTo(LEVEL);
                assertThat(roundTrip(sut)).isEqualTo(payload);
            }
        }

        @Test
        void fromSegmentUsesDefaultLevel() {
            // Given
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment segment = segmentOf(arena, dict.raw());

                // When
                try (ZstdCompressDictionary sut = Zstd.newCompressDictionary(segment)) {

                    // Then
                    assertThat(sut.level()).isEqualTo(ZstdCompressionLevel.DEFAULT);
                    assertThat(sut.id()).isEqualTo(dict.id());
                    assertThat(roundTrip(sut)).isEqualTo(payload);
                }
            }
        }

        @Test
        void fromSegmentAndLevelKeepsLevel() {
            // Given
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment segment = segmentOf(arena, dict.raw());

                // When
                try (ZstdCompressDictionary sut = Zstd.newCompressDictionary(segment, LEVEL)) {

                    // Then
                    assertThat(sut.level()).isEqualTo(LEVEL);
                    assertThat(roundTrip(sut)).isEqualTo(payload);
                }
            }
        }

        private byte[] roundTrip(ZstdCompressDictionary cdict) {
            try (ZstdCompressContext cctx = Zstd.newCompressContext();
                 ZstdDecompressContext dctx = Zstd.newDecompressContext()) {
                byte[] frame = cctx.compress(payload, cdict);
                return dctx.decompress(frame, ZstdByteSize.of(payload.length), dict);
            }
        }
    }

    @Nested
    class NewDecompressDictionary {

        @Test
        void fromDictionaryDecodes() {
            // Given
            byte[] frame = compressWithDict();

            // When
            try (ZstdDecompressDictionary sut = Zstd.newDecompressDictionary(dict);
                 ZstdDecompressContext dctx = Zstd.newDecompressContext()) {

                // Then
                assertThat(sut.id()).isEqualTo(dict.id());
                assertThat(dctx.decompress(frame, ZstdByteSize.of(payload.length), sut)).isEqualTo(payload);
            }
        }

        @Test
        void fromSegmentDecodes() {
            // Given
            byte[] frame = compressWithDict();
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment segment = segmentOf(arena, dict.raw());

                // When
                try (ZstdDecompressDictionary sut = Zstd.newDecompressDictionary(segment);
                     ZstdDecompressContext dctx = Zstd.newDecompressContext()) {

                    // Then
                    assertThat(sut.id()).isEqualTo(dict.id());
                    assertThat(dctx.decompress(frame, ZstdByteSize.of(payload.length), sut)).isEqualTo(payload);
                }
            }
        }
    }

    @Nested
    class NewSegmentStream {

        @Test
        void compressStreamAtDefaultLevel() {
            // Given
            byte[] expected = payload;

            // When
            try (ZstdCompressStream sut = Zstd.newCompressStream()) {
                byte[] frame = compressAll(sut);

                // Then
                assertThat(Zstd.decompress(frame)).isEqualTo(expected);
            }
        }

        @Test
        void compressStreamAtLevel() {
            // Given
            ZstdCompressionLevel level = LEVEL;

            // When
            try (ZstdCompressStream sut = Zstd.newCompressStream()) {
                sut.level(level);
                byte[] frame = compressAll(sut);

                // Then
                assertThat(Zstd.decompress(frame)).isEqualTo(payload);
            }
        }

        @Test
        void compressStreamWithDictionaryAndDecompressStreamWithDictionary() {
            // Given
            byte[] frame;
            try (ZstdCompressStream cs = Zstd.newCompressStream(dict)) {
                cs.level(LEVEL);
                frame = compressAll(cs);
            }

            // When
            try (ZstdDecompressStream sut = Zstd.newDecompressStream(dict)) {
                byte[] result = decompressAll(sut, frame);

                // Then
                assertThat(result).isEqualTo(payload);
                assertThat(ZstdFrame.dictId(frame)).isEqualTo(dict.id());
            }
        }

        @Test
        void decompressStreamWithoutDictionary() {
            // Given
            byte[] frame = Zstd.compress(payload);

            // When
            try (ZstdDecompressStream sut = Zstd.newDecompressStream()) {
                byte[] result = decompressAll(sut, frame);

                // Then
                assertThat(result).isEqualTo(payload);
            }
        }

        private byte[] compressAll(ZstdCompressStream cs) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment src = segmentOf(arena, payload);
                MemorySegment dst = arena.allocate(Zstd.compressBound(ZstdByteSize.of(payload.length)).value());
                ZstdStreamResult r = cs.compress(dst, src.asSlice(0, payload.length), ZstdEndDirective.END);
                assertThat(r.isComplete()).isTrue();
                return bytesOf(dst, r.bytesProduced());
            }
        }

        private byte[] decompressAll(ZstdDecompressStream ds, byte[] frame) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment src = segmentOf(arena, frame);
                MemorySegment dst = arena.allocate(payload.length);
                ZstdStreamResult r = ds.decompress(dst, src.asSlice(0, frame.length));
                assertThat(r.isComplete()).isTrue();
                return bytesOf(dst, r.bytesProduced());
            }
        }
    }

    @Nested
    class NewIoStream {

        @Test
        void outputStreamAtDefaultLevel() throws IOException {
            // Given
            ByteArrayOutputStream sink = new ByteArrayOutputStream();

            // When
            try (OutputStream sut = Zstd.newOutputStream(sink)) {
                sut.write(payload);
            }

            // Then
            assertThat(Zstd.decompress(sink.toByteArray(), ZstdByteSize.of(payload.length))).isEqualTo(payload);
        }

        @Test
        void outputStreamAtLevel() throws IOException {
            // Given
            ByteArrayOutputStream sink = new ByteArrayOutputStream();

            // When
            try (ZstdOutputStream sut = Zstd.newOutputStream(sink)) {
                sut.level(LEVEL);
                sut.write(payload);
            }

            // Then
            assertThat(Zstd.decompress(sink.toByteArray(), ZstdByteSize.of(payload.length))).isEqualTo(payload);
        }

        @Test
        void outputStreamWithDictionaryAndInputStreamWithDictionary() throws IOException {
            // Given
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (OutputStream out = Zstd.newOutputStream(sink, dict)) {
                out.write(payload);
            }

            // When
            byte[] result;
            try (InputStream sut = Zstd.newInputStream(new ByteArrayInputStream(sink.toByteArray()), dict)) {
                result = sut.readAllBytes();
            }

            // Then
            assertThat(result).isEqualTo(payload);
            assertThat(ZstdFrame.dictId(sink.toByteArray())).isEqualTo(dict.id());
        }

        @Test
        void outputStreamWithLevelAndDictionary() throws IOException {
            // Given
            ByteArrayOutputStream sink = new ByteArrayOutputStream();

            // When
            try (ZstdOutputStream sut = Zstd.newOutputStream(sink, dict)) {
                sut.level(LEVEL);
                sut.write(payload);
            }

            // Then
            try (InputStream in = Zstd.newInputStream(new ByteArrayInputStream(sink.toByteArray()), dict)) {
                assertThat(in.readAllBytes()).isEqualTo(payload);
            }
        }

        @Test
        void inputStreamWithoutDictionary() throws IOException {
            // Given
            byte[] frame = Zstd.compress(payload);

            // When
            byte[] result;
            try (InputStream sut = Zstd.newInputStream(new ByteArrayInputStream(frame))) {
                result = sut.readAllBytes();
            }

            // Then
            assertThat(result).isEqualTo(payload);
        }

        @Test
        void inputStreamOverEmptyFrame() throws IOException {
            // Given
            byte[] frame = Zstd.compress(new byte[0]);

            // When
            byte[] result;
            try (InputStream sut = Zstd.newInputStream(new ByteArrayInputStream(frame))) {
                result = sut.readAllBytes();
            }

            // Then
            assertThat(result).isEmpty();
        }
    }

    @Nested
    class NewPledgedOutputStream {

        @Test
        void stampsContentSizeIntoFrame() throws IOException {
            // Given
            ByteArrayOutputStream sink = new ByteArrayOutputStream();

            // When
            try (ZstdOutputStream sut = Zstd.newPledgedOutputStream(sink, ZstdByteSize.of(payload.length))) {
                sut.level(LEVEL);
                sut.write(payload);
            }

            // Then
            assertThat(Zstd.decompress(sink.toByteArray())).isEqualTo(payload);
        }

        @Test
        void stampsZeroContentSizeForEmptyStream() throws IOException {
            // Given
            ByteArrayOutputStream sink = new ByteArrayOutputStream();

            // When
            try (ZstdOutputStream sut = Zstd.newPledgedOutputStream(sink, ZstdByteSize.of(0))) {
                sut.level(LEVEL);
                sut.flush();
            }

            // Then
            assertThat(Zstd.decompress(sink.toByteArray())).isEmpty();
        }

        @Test
        void nullPledgedSizeFailsBeforeTouchingTheSink() {
            // Given
            RecordingOutputStream sink = new RecordingOutputStream();

            // When
            ThrowingCallable result = () -> Zstd.newPledgedOutputStream(sink, null);

            // Then
            assertThatThrownBy(result)
                    .isInstanceOf(NullPointerException.class)
                    .hasMessage("pledgedSrcSize");
            assertThat(sink.untouched()).isTrue();
        }

        @Test
        void failedConfigurationReleasesStreamWithoutTouchingTheSink() {
            // Given
            RecordingOutputStream sink = new RecordingOutputStream();
            ZstdOutputStream stream = Zstd.newOutputStream(sink).level(LEVEL);
            ZstdException failure = new ZstdException("pledge rejected");

            // When
            ThrowingCallable result = () -> ZstdOutputStream.initOrRelease(stream, s -> {
                throw failure;
            });
            ThrowingCallable writeAfterRelease = () -> stream.write(1);

            // Then
            assertThatThrownBy(result).isSameAs(failure);
            assertThatThrownBy(writeAfterRelease)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("closed");
            assertThatCode(stream::close).doesNotThrowAnyException();
            assertThat(sink.untouched()).isTrue();
        }

        @Test
        void successfulConfigurationReturnsTheSameOpenStream() throws IOException {
            // Given
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            ZstdOutputStream stream = Zstd.newOutputStream(sink).level(LEVEL);

            // When
            ZstdOutputStream sut = ZstdOutputStream.initOrRelease(stream, s -> { });

            // Then
            assertThat(sut).isSameAs(stream);
            try (sut) {
                sut.write(payload);
            }
            assertThat(Zstd.decompress(sink.toByteArray(), ZstdByteSize.of(payload.length))).isEqualTo(payload);
        }
    }

    /// An in-memory sink that records whether anything was written to or closed on it.
    private static final class RecordingOutputStream extends OutputStream {

        private boolean written;
        private boolean closed;

        @Override
        public void write(int b) {
            written = true;
        }

        @Override
        public void close() {
            closed = true;
        }

        boolean untouched() {
            return !written && !closed;
        }
    }

    private byte[] compressWithDict() {
        try (ZstdCompressContext cctx = Zstd.newCompressContext()) {
            return cctx.compress(payload, dict);
        }
    }
}
