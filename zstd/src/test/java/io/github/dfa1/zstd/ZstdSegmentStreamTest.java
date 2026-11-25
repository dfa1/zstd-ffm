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
import static io.github.dfa1.zstd.ZstdTestSupport.withWrongChecksum;
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
                 ZstdCompressStream cs = Zstd.newCompressStream();
                 ZstdDecompressStream ds = Zstd.newDecompressStream()) {

                MemorySegment src = segmentOf(arena, original);
                MemorySegment dst = arena.allocate(Zstd.compressBound(ZstdByteSize.of(original.length)).value());

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
                 ZstdCompressStream cs = compress ? Zstd.newCompressStream() : null;
                 ZstdDecompressStream ds = compress ? null : Zstd.newDecompressStream()) {

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
                 ZstdCompressStream cs = Zstd.newCompressStream()) {

                MemorySegment src = segmentOf(arena, original);
                MemorySegment dst = arena.allocate(Zstd.compressBound(ZstdByteSize.of(original.length)).value());

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
            try (ZstdCompressStream cs = Zstd.newCompressStream()) {
                // When setting an advanced parameter
                ZstdCompressStream result = cs.checksum(true);

                // Then it returns the same instance, for chaining
                assertThat(result).isSameAs(cs);
            }
        }

        @Test
        void rejectsOutOfRangeValue() {
            // Given a fresh compress stream
            try (ZstdCompressStream cs = Zstd.newCompressStream()) {
                // When setting an absurd window log
                ThrowingCallable result = () -> cs.windowLog(99);

                // Then it is rejected before reaching native code
                assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Nested
    class DecompressParameters {

        private static final byte[] PAYLOAD = randomBytes(0x3F, 64 * 1024);

        @Test
        void returnsTheSameStreamForChaining() {
            // Given a fresh decompress stream
            try (ZstdDecompressStream ds = Zstd.newDecompressStream()) {
                // When setting an advanced parameter
                ZstdDecompressStream result = ds.windowLogMax(27);

                // Then it returns the same instance, for chaining
                assertThat(result).isSameAs(ds);
            }
        }

        @Test
        void forceIgnoreChecksumRecoversTheIntactPayload() {
            // Given a frame whose stored checksum is flipped
            byte[] frame = withWrongChecksum(PAYLOAD);
            try (Arena arena = Arena.ofConfined();
                 ZstdDecompressStream sut = Zstd.newDecompressStream().ignoreChecksum(true)) {
                MemorySegment out = arena.allocate(PAYLOAD.length);

                // When decoding it in one step with validation off
                ZstdStreamResult result = sut.decompress(out, segmentOf(arena, frame));

                // Then the whole payload comes back untouched
                assertThat(result.isComplete()).isTrue();
                assertThat(bytesOf(out, result.bytesProduced())).isEqualTo(PAYLOAD);
            }
        }

        @Test
        void magiclessStreamDecodesAMagiclessFrame() {
            // Given a magicless frame
            byte[] frame;
            try (ZstdCompressContext cctx = Zstd.newCompressContext().magicless(true)) {
                frame = cctx.compress(PAYLOAD);
            }
            try (Arena arena = Arena.ofConfined();
                 ZstdDecompressStream sut = Zstd.newDecompressStream().magicless(true)) {
                MemorySegment out = arena.allocate(PAYLOAD.length);

                // When decoding it in one step
                ZstdStreamResult result = sut.decompress(out, segmentOf(arena, frame));

                // Then the whole payload comes back
                assertThat(result.isComplete()).isTrue();
                assertThat(bytesOf(out, result.bytesProduced())).isEqualTo(PAYLOAD);
            }
        }

        @Test
        void defaultStreamRejectsAWrongChecksum() {
            // Given a frame whose stored checksum is flipped
            byte[] frame = withWrongChecksum(PAYLOAD);
            try (Arena arena = Arena.ofConfined();
                 ZstdDecompressStream sut = Zstd.newDecompressStream()) {
                MemorySegment out = arena.allocate(PAYLOAD.length);

                // When decoding it with validation on
                ThrowingCallable result = () -> sut.decompress(out, segmentOf(arena, frame));

                // Then the mismatch is reported
                assertThatThrownBy(result)
                        .isInstanceOfSatisfying(ZstdException.class,
                                e -> assertThat(e.code()).isEqualTo(ZstdErrorCode.CHECKSUM_WRONG));
            }
        }

        @Test
        void parameterAfterCloseThrows() {
            // Given a closed decompress stream
            ZstdDecompressStream sut = Zstd.newDecompressStream();
            sut.close();

            // When a parameter is set on it
            ThrowingCallable result = () -> sut.ignoreChecksum(true);

            // Then it fails fast rather than using the freed context
            assertThatThrownBy(result).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    class WithDictionary {

        @Test
        void roundTripsAgainstDictionary() {
            ZstdDictionary dict = trainDictionary(3000);
            byte[] sample = "{\"id\":1,\"user\":\"u\",\"event\":\"x\"}".getBytes(StandardCharsets.UTF_8);

            try (Arena arena = Arena.ofConfined();
                 ZstdCompressStream cs = Zstd.newCompressStream(dict);
                 ZstdDecompressStream ds = Zstd.newDecompressStream(dict)) {
                cs.level(ZstdCompressionLevel.of(3));

                MemorySegment src = segmentOf(arena, sample);
                MemorySegment dst = arena.allocate(Zstd.compressBound(ZstdByteSize.of(sample.length)).value());
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
                 ZstdCompressStream sut = Zstd.newCompressStream(dict)) {
                MemorySegment src = segmentOf(arena, sample);
                MemorySegment dst = arena.allocate(Zstd.compressBound(ZstdByteSize.of(sample.length)).value());

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
        void compressStreamRejectsANullDictionary() {
            // Given no dictionary
            ZstdDictionary dict = null;

            // When constructing a dictionary stream from it
            ThrowingCallable result = () -> Zstd.newCompressStream(dict);

            // Then it is rejected before any native allocation
            assertThatThrownBy(result).isInstanceOf(NullPointerException.class).hasMessage("dictionary");
        }

        @Test
        void decompressStreamWithCorruptDictionaryFailsAfterCreatingItsContext() {
            // Given a dictionary with a valid magic number but garbage entropy tables
            ZstdDictionary corrupt = corruptDictionary();

            // When constructing the stream, which digests the dictionary eagerly
            ThrowingCallable result = () -> Zstd.newDecompressStream(corrupt);

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
                 ZstdCompressStream sut = Zstd.newCompressStream()) {
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
                 ZstdCompressStream sut = Zstd.newCompressStream()) {
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

        @Test
        void compressRejectsReadOnlyDestination() {
            // Given a compress stream and a read-only view over native memory
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressStream sut = Zstd.newCompressStream()) {
                MemorySegment dst = arena.allocate(64).asReadOnly();
                MemorySegment src = arena.allocate(64);

                // When compressing into it
                ThrowingCallable result = () -> sut.compress(dst, src, ZstdEndDirective.END);

                // Then the guard rejects it before zstd writes through it
                assertThatThrownBy(result)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("dst must be a writable MemorySegment; got a read-only segment");
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"dst", "src"})
        void decompressRejectsHeapSegment(String heapSide) {
            // Given a decompress stream and one heap-backed buffer
            try (Arena arena = Arena.ofConfined();
                 ZstdDecompressStream sut = Zstd.newDecompressStream()) {
                MemorySegment heap = MemorySegment.ofArray(new byte[64]);
                MemorySegment dst = "dst".equals(heapSide) ? heap : arena.allocate(64);
                MemorySegment src = "src".equals(heapSide) ? heap : arena.allocate(64);

                // When decompressing through it
                ThrowingCallable result = () -> sut.decompress(dst, src);

                // Then the guard names the offending argument before any native call
                assertThatThrownBy(result)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageStartingWith(heapSide + " must be a native");
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"dst", "src"})
        void decompressRejectsNullSegment(String nullSide) {
            // Given a decompress stream and one missing buffer
            try (Arena arena = Arena.ofConfined();
                 ZstdDecompressStream sut = Zstd.newDecompressStream()) {
                MemorySegment dst = "dst".equals(nullSide) ? null : arena.allocate(64);
                MemorySegment src = "src".equals(nullSide) ? null : arena.allocate(64);

                // When decompressing through it
                ThrowingCallable result = () -> sut.decompress(dst, src);

                // Then the guard names the missing argument
                assertThatThrownBy(result)
                        .isInstanceOf(NullPointerException.class)
                        .hasMessage(nullSide);
            }
        }

        @Test
        void decompressRejectsReadOnlyDestinationAndLeavesItUntouched() {
            // Given a valid frame and a read-only view over writable native memory
            byte[] original = "read-only stream ".repeat(50).getBytes(StandardCharsets.UTF_8);
            try (Arena arena = Arena.ofConfined();
                 ZstdDecompressStream sut = Zstd.newDecompressStream()) {
                MemorySegment frame = segmentOf(arena, Zstd.compress(original));
                MemorySegment backing = arena.allocate(original.length);

                // When decompressing into the view
                ThrowingCallable result = () -> sut.decompress(backing.asReadOnly(), frame);

                // Then the guard rejects it and zstd never wrote through the view
                assertThatThrownBy(result)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("dst must be a writable MemorySegment; got a read-only segment");
                assertThat(bytesOf(backing, original.length)).containsOnly((byte) 0);
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"dst", "src"})
        void compressRejectsSegmentOfClosedArena(String closedSide) {
            // Given a compress stream and one buffer whose arena is already closed
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressStream sut = Zstd.newCompressStream()) {
                MemorySegment closed = closedSegment();
                MemorySegment dst = "dst".equals(closedSide) ? closed : arena.allocate(64);
                MemorySegment src = "src".equals(closedSide) ? closed : arena.allocate(64);

                // When compressing through it
                ThrowingCallable result = () -> sut.compress(dst, src, ZstdEndDirective.END);

                // Then the linker rejects it before zstd follows a freed address
                assertThatThrownBy(result)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("closed");
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"dst", "src"})
        void decompressRejectsSegmentOfClosedArena(String closedSide) {
            // Given a decompress stream and one buffer whose arena is already closed
            try (Arena arena = Arena.ofConfined();
                 ZstdDecompressStream sut = Zstd.newDecompressStream()) {
                MemorySegment closed = closedSegment();
                MemorySegment dst = "dst".equals(closedSide) ? closed : arena.allocate(64);
                MemorySegment src = "src".equals(closedSide) ? closed : arena.allocate(64);

                // When decompressing through it
                ThrowingCallable result = () -> sut.decompress(dst, src);

                // Then the linker rejects it before zstd follows a freed address
                assertThatThrownBy(result)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("closed");
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"dst", "src"})
        void compressRejectsSegmentConfinedToAnotherThread(String foreignSide) throws InterruptedException {
            // Given a compress stream and one buffer owned by another thread
            MemorySegment foreign = confinedToAnotherThread();
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressStream sut = Zstd.newCompressStream()) {
                MemorySegment dst = "dst".equals(foreignSide) ? foreign : arena.allocate(64);
                MemorySegment src = "src".equals(foreignSide) ? foreign : arena.allocate(64);

                // When compressing through it
                ThrowingCallable result = () -> sut.compress(dst, src, ZstdEndDirective.END);

                // Then the linker rejects it before zstd touches another thread's memory
                assertThatThrownBy(result)
                        .isInstanceOf(WrongThreadException.class);
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"dst", "src"})
        void decompressRejectsSegmentConfinedToAnotherThread(String foreignSide) throws InterruptedException {
            // Given a decompress stream and one buffer owned by another thread
            MemorySegment foreign = confinedToAnotherThread();
            try (Arena arena = Arena.ofConfined();
                 ZstdDecompressStream sut = Zstd.newDecompressStream()) {
                MemorySegment dst = "dst".equals(foreignSide) ? foreign : arena.allocate(64);
                MemorySegment src = "src".equals(foreignSide) ? foreign : arena.allocate(64);

                // When decompressing through it
                ThrowingCallable result = () -> sut.decompress(dst, src);

                // Then the linker rejects it before zstd touches another thread's memory
                assertThatThrownBy(result)
                        .isInstanceOf(WrongThreadException.class);
            }
        }

        /// A segment from a confined arena opened (and left open) on a thread that
        /// has since finished, so it is alive but never accessible from this thread.
        private static MemorySegment confinedToAnotherThread() throws InterruptedException {
            MemorySegment[] seg = new MemorySegment[1];
            Thread owner = Thread.ofPlatform().start(() -> seg[0] = Arena.ofConfined().allocate(64));
            owner.join();
            return seg[0];
        }

        private static MemorySegment closedSegment() {
            Arena arena = Arena.ofConfined();
            MemorySegment seg = arena.allocate(64);
            arena.close();
            return seg;
        }
    }
}
