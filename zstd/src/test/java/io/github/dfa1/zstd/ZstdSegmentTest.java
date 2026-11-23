package io.github.dfa1.zstd;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static io.github.dfa1.zstd.ZstdTestSupport.bytesOf;
import static io.github.dfa1.zstd.ZstdTestSupport.segmentOf;
import static io.github.dfa1.zstd.ZstdTestSupport.trainDictionary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZstdSegmentTest {

    @Nested
    class ExplicitDestination {

        @Test
        void roundTripsNativeToNative() {
            // Given a payload in a native source segment
            byte[] original = "segment payload ".repeat(200).getBytes(StandardCharsets.UTF_8);

            try (Arena arena = Arena.ofConfined();
                 ZstdCompressContext cctx = Zstd.newCompressContext();
                 ZstdDecompressContext dctx = Zstd.newDecompressContext()) {

                MemorySegment src = segmentOf(arena, original);
                MemorySegment dst = arena.allocate(Zstd.compressBound(ZstdByteSize.of(original.length)).value());

                // When compressed into a caller-sized destination
                long packedLen = cctx.compress(dst, src);
                MemorySegment frame = dst.asSlice(0, packedLen);

                // Then the frame header reports the original size, and it decodes back
                ZstdByteSize outLen = Zstd.decompressedSize(frame);
                assertThat(outLen).isEqualTo(ZstdByteSize.of(original.length));

                MemorySegment out = arena.allocate(outLen.value());
                long written = dctx.decompress(out, frame);
                assertThat(bytesOf(out, written)).isEqualTo(original);
            }
        }
    }

    @Nested
    class ArenaAllocating {

        @Test
        void codecSizesAndAllocatesOutput() {
            // Given a payload in a native source segment
            byte[] original = "arena-sized payload ".repeat(100).getBytes(StandardCharsets.UTF_8);

            try (Arena arena = Arena.ofConfined();
                 ZstdCompressContext cctx = Zstd.newCompressContext();
                 ZstdDecompressContext dctx = Zstd.newDecompressContext()) {

                MemorySegment src = segmentOf(arena, original);

                // When the codec sizes and allocates both buffers from the caller's arena
                MemorySegment frame = cctx.compress(arena, src);
                MemorySegment out = dctx.decompress(arena, frame);

                // Then the output is exactly the original
                assertThat(out.byteSize()).isEqualTo(original.length);
                assertThat(bytesOf(out, out.byteSize())).isEqualTo(original);
            }
        }
    }

    @Nested
    class WithDictionary {

        @Test
        void roundTripsWithDigestedDictionary() {
            // Given a digested dictionary and a sample in a native segment
            ZstdDictionary dict = trainDictionary(2000);
            byte[] sample = "{\"id\":42,\"user\":\"u\",\"active\":true}".getBytes(StandardCharsets.UTF_8);

            try (Arena arena = Arena.ofConfined();
                 ZstdCompressContext cctx = Zstd.newCompressContext();
                 ZstdDecompressContext dctx = Zstd.newDecompressContext();
                 ZstdCompressDictionary cdict = Zstd.newCompressDictionary(dict);
                 ZstdDecompressDictionary ddict = Zstd.newDecompressDictionary(dict)) {

                MemorySegment src = segmentOf(arena, sample);

                // When round-tripped segment-to-segment against the dictionary
                MemorySegment frame = cctx.compress(arena, src, cdict);
                MemorySegment out = arena.allocate(sample.length);
                long written = dctx.decompress(out, frame, ddict);

                // Then the sample is recovered
                assertThat(bytesOf(out, written)).isEqualTo(sample);
            }
        }

        @Test
        void arenaAllocatingDecompressSizesOutputFromTheDigestedDictionaryFrame() {
            // Given a digested-dictionary frame that stores its decompressed size
            ZstdDictionary dict = trainDictionary(2000);
            byte[] sample = "{\"id\":99,\"user\":\"u\",\"active\":false}".getBytes(StandardCharsets.UTF_8);

            try (Arena arena = Arena.ofConfined();
                 ZstdCompressContext cctx = Zstd.newCompressContext();
                 ZstdDecompressContext dctx = Zstd.newDecompressContext();
                 ZstdCompressDictionary cdict = Zstd.newCompressDictionary(dict);
                 ZstdDecompressDictionary ddict = Zstd.newDecompressDictionary(dict)) {

                MemorySegment src = segmentOf(arena, sample);
                MemorySegment frame = cctx.compress(arena, src, cdict);

                // When decoded through the arena-allocating ddict overload
                MemorySegment out = dctx.decompress(arena, frame, ddict);

                // Then it allocates the exact size and returns the sample (a non-null segment)
                assertThat(out).isNotNull();
                assertThat(out.byteSize()).isEqualTo(sample.length);
                assertThat(bytesOf(out, out.byteSize())).isEqualTo(sample);
            }
        }
    }

    @Nested
    class HeapSegmentGuard {

        @Test
        void compressRejectsHeapSource() {
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressContext sut = Zstd.newCompressContext()) {
                // Given a heap-backed source segment handed to the zero-copy API
                MemorySegment heapSrc = MemorySegment.ofArray(new byte[64]);
                MemorySegment dst = arena.allocate(64);

                // When compressing from it
                ThrowingCallable result = () -> sut.compress(dst, heapSrc);

                // Then it fails fast with a clear message instead of a cryptic FFM error
                assertThatThrownBy(result)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("native");
            }
        }

        @Test
        void decompressRejectsHeapDestination() {
            try (Arena arena = Arena.ofConfined();
                 ZstdDecompressContext sut = Zstd.newDecompressContext()) {
                // Given a heap-backed destination segment handed to the zero-copy API
                MemorySegment heapDst = MemorySegment.ofArray(new byte[64]);
                MemorySegment src = arena.allocate(64);

                // When decompressing into it
                ThrowingCallable result = () -> sut.decompress(heapDst, src);

                // Then it fails fast with a clear message instead of a cryptic FFM error
                assertThatThrownBy(result)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("native");
            }
        }

        @Test
        void compressRejectsHeapDestination() {
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressContext sut = Zstd.newCompressContext()) {
                // Given a heap-backed destination segment handed to the zero-copy API
                MemorySegment heapDst = MemorySegment.ofArray(new byte[64]);
                MemorySegment src = arena.allocate(64);

                // When compressing into it
                ThrowingCallable result = () -> sut.compress(heapDst, src);

                // Then it fails fast with a clear message instead of a cryptic FFM error
                assertThatThrownBy(result)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("native");
            }
        }

        @Test
        void decompressRejectsHeapSource() {
            try (Arena arena = Arena.ofConfined();
                 ZstdDecompressContext sut = Zstd.newDecompressContext()) {
                // Given a heap-backed source segment handed to the zero-copy API
                MemorySegment heapSrc = MemorySegment.ofArray(new byte[64]);
                MemorySegment dst = arena.allocate(64);

                // When decompressing from it
                ThrowingCallable result = () -> sut.decompress(dst, heapSrc);

                // Then it fails fast with a clear message instead of a cryptic FFM error
                assertThatThrownBy(result)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("native");
            }
        }

        @Test
        void compressWithDictionaryRejectsHeapSource() {
            ZstdDictionary dict = trainDictionary(2000);
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressContext sut = Zstd.newCompressContext();
                 ZstdCompressDictionary cdict = Zstd.newCompressDictionary(dict)) {
                // Given a heap-backed source handed to the dictionary zero-copy API
                MemorySegment heapSrc = MemorySegment.ofArray(new byte[64]);
                MemorySegment dst = arena.allocate(64);

                // When compressing against the digested dictionary
                ThrowingCallable result = () -> sut.compress(dst, heapSrc, cdict);

                // Then it fails fast before reaching native code
                assertThatThrownBy(result)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("native");
            }
        }

        @Test
        void compressWithDictionaryRejectsHeapDestination() {
            ZstdDictionary dict = trainDictionary(2000);
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressContext sut = Zstd.newCompressContext();
                 ZstdCompressDictionary cdict = Zstd.newCompressDictionary(dict)) {
                // Given a heap-backed destination handed to the dictionary zero-copy API
                MemorySegment heapDst = MemorySegment.ofArray(new byte[64]);
                MemorySegment src = arena.allocate(64);

                // When compressing against the digested dictionary
                ThrowingCallable result = () -> sut.compress(heapDst, src, cdict);

                // Then it fails fast before reaching native code
                assertThatThrownBy(result)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("native");
            }
        }

        @Test
        void decompressWithDictionaryRejectsHeapSource() {
            ZstdDictionary dict = trainDictionary(2000);
            try (Arena arena = Arena.ofConfined();
                 ZstdDecompressContext sut = Zstd.newDecompressContext();
                 ZstdDecompressDictionary ddict = Zstd.newDecompressDictionary(dict)) {
                // Given a heap-backed source handed to the dictionary zero-copy API
                MemorySegment heapSrc = MemorySegment.ofArray(new byte[64]);
                MemorySegment dst = arena.allocate(64);

                // When decompressing against the digested dictionary
                ThrowingCallable result = () -> sut.decompress(dst, heapSrc, ddict);

                // Then it fails fast before reaching native code
                assertThatThrownBy(result)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("native");
            }
        }

        @Test
        void decompressWithDictionaryRejectsHeapDestination() {
            ZstdDictionary dict = trainDictionary(2000);
            try (Arena arena = Arena.ofConfined();
                 ZstdDecompressContext sut = Zstd.newDecompressContext();
                 ZstdDecompressDictionary ddict = Zstd.newDecompressDictionary(dict)) {
                // Given a heap-backed destination handed to the dictionary zero-copy API
                MemorySegment heapDst = MemorySegment.ofArray(new byte[64]);
                MemorySegment src = arena.allocate(64);

                // When decompressing against the digested dictionary
                ThrowingCallable result = () -> sut.decompress(heapDst, src, ddict);

                // Then it fails fast before reaching native code
                assertThatThrownBy(result)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("native");
            }
        }

        @Test
        void decompressedSizeRejectsHeapFrame() {
            // Given a heap-backed frame segment
            MemorySegment heapFrame = MemorySegment.ofArray(new byte[8]);

            // When reading its decompressed size
            ThrowingCallable result = () -> Zstd.decompressedSize(heapFrame);

            // Then it fails fast with a clear message instead of a cryptic FFM error
            assertThatThrownBy(result)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("native");
        }
    }

    @Nested
    class ReadOnlyDestinationGuard {

        private static final byte[] ORIGINAL = "read-only destination ".repeat(50).getBytes(StandardCharsets.UTF_8);
        private static final String READ_ONLY_DST = "dst must be a writable MemorySegment; got a read-only segment";

        @Test
        void compressRejectsReadOnlyDestinationAndLeavesItUntouched() {
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressContext sut = Zstd.newCompressContext()) {
                // Given a read-only view over writable native memory
                MemorySegment backing = arena.allocate(Zstd.compressBound(ZstdByteSize.of(ORIGINAL.length)).value());
                MemorySegment src = segmentOf(arena, ORIGINAL);

                // When compressing into the view
                ThrowingCallable result = () -> sut.compress(backing.asReadOnly(), src);

                // Then it fails fast and zstd never wrote through the view
                assertThatThrownBy(result)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage(READ_ONLY_DST);
                assertThat(bytesOf(backing, backing.byteSize())).containsOnly((byte) 0);
            }
        }

        @Test
        void decompressRejectsReadOnlyDestinationAndLeavesItUntouched() {
            try (Arena arena = Arena.ofConfined();
                 ZstdDecompressContext sut = Zstd.newDecompressContext()) {
                // Given a valid frame and a read-only view over writable native memory
                MemorySegment frame = segmentOf(arena, Zstd.compress(ORIGINAL));
                MemorySegment backing = arena.allocate(ORIGINAL.length);

                // When decompressing into the view
                ThrowingCallable result = () -> sut.decompress(backing.asReadOnly(), frame);

                // Then it fails fast and zstd never wrote through the view
                assertThatThrownBy(result)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage(READ_ONLY_DST);
                assertThat(bytesOf(backing, backing.byteSize())).containsOnly((byte) 0);
            }
        }

        @Test
        void compressWithDictionaryRejectsReadOnlyDestination() {
            ZstdDictionary dict = trainDictionary(2000);
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressContext sut = Zstd.newCompressContext();
                 ZstdCompressDictionary cdict = Zstd.newCompressDictionary(dict)) {
                // Given a read-only destination handed to the dictionary zero-copy API
                MemorySegment dst = arena.allocate(64).asReadOnly();
                MemorySegment src = arena.allocate(64);

                // When compressing against the digested dictionary
                ThrowingCallable result = () -> sut.compress(dst, src, cdict);

                // Then it fails fast before reaching native code
                assertThatThrownBy(result)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage(READ_ONLY_DST);
            }
        }

        @Test
        void decompressWithDictionaryRejectsReadOnlyDestination() {
            ZstdDictionary dict = trainDictionary(2000);
            try (Arena arena = Arena.ofConfined();
                 ZstdDecompressContext sut = Zstd.newDecompressContext();
                 ZstdDecompressDictionary ddict = Zstd.newDecompressDictionary(dict)) {
                // Given a read-only destination handed to the dictionary zero-copy API
                MemorySegment dst = arena.allocate(64).asReadOnly();
                MemorySegment src = arena.allocate(64);

                // When decompressing against the digested dictionary
                ThrowingCallable result = () -> sut.decompress(dst, src, ddict);

                // Then it fails fast before reaching native code
                assertThatThrownBy(result)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage(READ_ONLY_DST);
            }
        }

        @Test
        void decompressAcceptsReadOnlySource() {
            try (Arena arena = Arena.ofConfined();
                 ZstdDecompressContext sut = Zstd.newDecompressContext()) {
                // Given a frame in a read-only segment, as from a read-only mmap
                MemorySegment frame = segmentOf(arena, Zstd.compress(ORIGINAL)).asReadOnly();
                MemorySegment dst = arena.allocate(ORIGINAL.length);

                // When decompressing from it
                long written = sut.decompress(dst, frame);

                // Then the source side stays usable: only the destination must be writable
                assertThat(bytesOf(dst, written)).isEqualTo(ORIGINAL);
            }
        }
    }

}
