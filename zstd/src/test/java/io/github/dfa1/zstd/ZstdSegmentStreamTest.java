package io.github.dfa1.zstd;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static io.github.dfa1.zstd.ZstdTestSupport.bytesOf;
import static io.github.dfa1.zstd.ZstdTestSupport.corruptDictionary;
import static io.github.dfa1.zstd.ZstdTestSupport.randomBytes;
import static io.github.dfa1.zstd.ZstdTestSupport.segmentOf;
import static io.github.dfa1.zstd.ZstdTestSupport.trainDictionary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZstdSegmentStreamTest {

    @Nested
    class SingleStep {

        @Test
        void roundTripsInOneCallEach() {
            byte[] original = "segment stream ".repeat(400).getBytes(StandardCharsets.UTF_8);
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressStream cs = new ZstdCompressStream();
                 ZstdDecompressStream ds = new ZstdDecompressStream()) {

                MemorySegment src = segmentOf(arena, original);
                MemorySegment dst = arena.allocate(Zstd.compressBound(new ZstdByteSize(original.length)).value());

                // When compressed in one END step
                ZstdStreamResult c = cs.compress(dst, src, ZstdEndDirective.END);

                // Then everything is consumed and the frame is complete
                assertThat(c.isComplete()).isTrue();
                assertThat(c.bytesConsumed()).isEqualTo(original.length);

                MemorySegment frame = dst.asSlice(0, c.bytesProduced());
                MemorySegment out = arena.allocate(original.length);
                ZstdStreamResult d = ds.decompress(out, frame);

                assertThat(d.isComplete()).isTrue();
                assertThat(d.bytesProduced()).isEqualTo(original.length);
                assertThat(bytesOf(out, d.bytesProduced())).isEqualTo(original);
            }
        }
    }

    @Nested
    class Chunked {

        @Test
        void roundTripsThroughTinyBuffers() {
            // Given a payload far larger than the streaming buffers
            byte[] original = randomBytes(11, 2 * 1024 * 1024);

            byte[] frame = drive(original, 8 * 1024, true);
            byte[] restored = drive(frame, 8 * 1024, false);

            // Then chunked compress + decompress reproduce the input exactly
            assertThat(restored).isEqualTo(original);
        }

        /// Streams `input` through fixed `chunk`-sized native buffers using the
        /// segment driver: compress when `compress`, else decompress.
        private byte[] drive(byte[] input, int chunk, boolean compress) {
            ByteArrayOutputStream collected = new ByteArrayOutputStream();
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressStream cs = compress ? new ZstdCompressStream() : null;
                 ZstdDecompressStream ds = compress ? null : new ZstdDecompressStream()) {

                MemorySegment src = segmentOf(arena, input);
                MemorySegment dst = arena.allocate(chunk);
                long srcOff = 0;
                ZstdStreamResult r;
                do {
                    MemorySegment srcSlice = src.asSlice(srcOff, input.length - srcOff);
                    r = compress
                            ? cs.compress(dst, srcSlice, ZstdEndDirective.END)
                            : ds.decompress(dst, srcSlice);
                    srcOff += r.bytesConsumed();
                    collected.writeBytes(bytesOf(dst, r.bytesProduced()));
                } while (!r.isComplete());
                return collected.toByteArray();
            }
        }
    }

    @Nested
    class Progress {

        @Test
        void tracksByteCounters() {
            byte[] original = "progress payload ".repeat(500).getBytes(StandardCharsets.UTF_8);
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressStream cs = new ZstdCompressStream()) {

                MemorySegment src = segmentOf(arena, original);
                MemorySegment dst = arena.allocate(Zstd.compressBound(new ZstdByteSize(original.length)).value());

                // fresh stream: nothing moved yet
                assertThat(cs.progress().consumed()).isZero();

                ZstdStreamResult r = cs.compress(dst, src, ZstdEndDirective.END);

                // after a complete END step the counters reflect the whole frame
                ZstdFrameProgression p = cs.progress();
                assertThat(p.consumed()).isEqualTo(original.length);
                assertThat(p.ingested()).isEqualTo(original.length);
                assertThat(p.flushed()).isEqualTo(r.bytesProduced());
                assertThat(p.activeWorkers()).isZero(); // no workers configured
            }
        }
    }

    @Nested
    class Parameters {

        @Test
        void returnsTheSameStreamForChaining() {
            // Given a fresh compress stream
            try (ZstdCompressStream cs = new ZstdCompressStream()) {
                // When setting an advanced parameter
                ZstdCompressStream result = cs.parameter(ZstdCompressParameter.CHECKSUM_FLAG, 1);

                // Then it returns the same instance, for chaining
                assertThat(result).isSameAs(cs);
            }
        }

        @Test
        void rejectsOutOfRangeValue() {
            // Given a fresh compress stream
            try (ZstdCompressStream cs = new ZstdCompressStream()) {
                // When setting an absurd window log
                ThrowingCallable result = () -> cs.parameter(ZstdCompressParameter.WINDOW_LOG, 99);

                // Then it is rejected natively
                assertThatThrownBy(result).isInstanceOf(ZstdException.class);
            }
        }
    }

    @Nested
    class WithDictionary {

        @Test
        void roundTripsAgainstDictionary() {
            ZstdDictionary dict = trainDictionary(3000);
            byte[] sample = "{\"id\":1,\"user\":\"u\",\"event\":\"x\"}".getBytes(StandardCharsets.UTF_8);

            try (Arena arena = Arena.ofConfined();
                 ZstdCompressStream cs = new ZstdCompressStream(new ZstdCompressionLevel(3), dict);
                 ZstdDecompressStream ds = new ZstdDecompressStream(dict)) {

                MemorySegment src = segmentOf(arena, sample);
                MemorySegment dst = arena.allocate(Zstd.compressBound(new ZstdByteSize(sample.length)).value());
                ZstdStreamResult c = cs.compress(dst, src, ZstdEndDirective.END);

                MemorySegment out = arena.allocate(sample.length);
                ds.decompress(out, dst.asSlice(0, c.bytesProduced()));
                assertThat(bytesOf(out, sample.length)).isEqualTo(sample);
            }
        }

        @Test
        void framesCarryTheDictionaryId() {
            // Given a compress stream built with a trained dictionary
            ZstdDictionary dict = trainDictionary(3000);
            byte[] sample = "{\"id\":2,\"user\":\"u\",\"event\":\"y\"}".getBytes(StandardCharsets.UTF_8);
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressStream sut = new ZstdCompressStream(ZstdCompressionLevel.DEFAULT, dict)) {
                MemorySegment src = segmentOf(arena, sample);
                MemorySegment dst = arena.allocate(Zstd.compressBound(new ZstdByteSize(sample.length)).value());

                // When a frame is compressed
                ZstdStreamResult c = sut.compress(dst, src, ZstdEndDirective.END);

                // Then the frame header names the dictionary it was compressed against
                assertThat(ZstdFrame.dictId(dst.asSlice(0, c.bytesProduced()))).isEqualTo(dict.id());
            }
        }
    }

    @Nested
    class ConstructionFailure {

        @Test
        void compressStreamWithoutLevelFailsAfterCreatingItsContext() {
            // Given a dictionary but no compression level
            ZstdDictionary dict = trainDictionary(3000);

            // When constructing the stream, which fails once the native context exists
            ThrowingCallable result = () -> new ZstdCompressStream(null, dict);

            // Then the failure propagates (after the half-built stream closed itself)
            assertThatThrownBy(result).isInstanceOf(NullPointerException.class);
        }

        @Test
        void decompressStreamWithCorruptDictionaryFailsAfterCreatingItsContext() {
            // Given a dictionary with a valid magic number but garbage entropy tables
            ZstdDictionary corrupt = corruptDictionary();

            // When constructing the stream, which digests the dictionary eagerly
            ThrowingCallable result = () -> new ZstdDecompressStream(corrupt);

            // Then the native rejection propagates as a ZstdException
            assertThatThrownBy(result).isInstanceOf(ZstdException.class);
        }
    }

    @Nested
    class SegmentGuard {

        @ParameterizedTest
        @ValueSource(strings = {"dst", "src"})
        void compressRejectsHeapSegment(String heapSide) {
            // Given a compress stream and one heap-backed buffer
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressStream sut = new ZstdCompressStream()) {
                MemorySegment heap = MemorySegment.ofArray(new byte[64]);
                MemorySegment dst = "dst".equals(heapSide) ? heap : arena.allocate(64);
                MemorySegment src = "src".equals(heapSide) ? heap : arena.allocate(64);

                // When compressing through it
                ThrowingCallable result = () -> sut.compress(dst, src, ZstdEndDirective.END);

                // Then the guard names the offending argument before any native call
                assertThatThrownBy(result)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageStartingWith(heapSide + " must be a native");
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"dst", "src"})
        void compressRejectsNullSegment(String nullSide) {
            // Given a compress stream and one missing buffer
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressStream sut = new ZstdCompressStream()) {
                MemorySegment dst = "dst".equals(nullSide) ? null : arena.allocate(64);
                MemorySegment src = "src".equals(nullSide) ? null : arena.allocate(64);

                // When compressing through it
                ThrowingCallable result = () -> sut.compress(dst, src, ZstdEndDirective.END);

                // Then the guard names the missing argument
                assertThatThrownBy(result)
                        .isInstanceOf(NullPointerException.class)
                        .hasMessage(nullSide);
            }
        }
    }
}
