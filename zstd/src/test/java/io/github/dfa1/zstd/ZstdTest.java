package io.github.dfa1.zstd;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZstdTest {

    @Nested
    class RoundTrip {

        @ParameterizedTest
        @MethodSource("io.github.dfa1.zstd.ZstdTestSupport#bytes")
        void preservesArbitraryBytes(byte[] original) {
            // Given a payload and its compressed frame
            byte[] frame = Zstd.compress(original);

            // When it is decompressed
            byte[] restored = Zstd.decompress(frame);

            // Then the original bytes come back exactly
            assertThat(restored).isEqualTo(original);
        }

        @Test
        void handlesEmptyInput() {
            // Given an empty payload
            byte[] empty = new byte[0];

            // When round-tripped
            byte[] restored = Zstd.decompress(Zstd.compress(empty));

            // Then it is still empty
            assertThat(restored).isEmpty();
        }

        @Test
        void shrinksCompressibleInput() {
            // Given highly repetitive text
            byte[] original = "the quick brown fox ".repeat(100).getBytes(StandardCharsets.UTF_8);

            // When compressed
            byte[] frame = Zstd.compress(original);

            // Then the frame is smaller than the input
            assertThat(frame).hasSizeLessThan(original.length);
        }
    }

    @Nested
    class Levels {

        @ParameterizedTest
        @MethodSource("io.github.dfa1.zstd.ZstdTestSupport#levels")
        void roundTripAtEveryLevel(ZstdCompressionLevel level) {
            // Given a payload compressed at the given level
            byte[] original = "payload-data-".repeat(500).getBytes(StandardCharsets.UTF_8);
            byte[] frame = Zstd.compress(original, level);

            // When decompressed
            byte[] restored = Zstd.decompress(frame);

            // Then the original is recovered
            assertThat(restored).as("level %s", level).isEqualTo(original);
        }

        @Test
        void exposesLevelOrdering() {
            // Given the advertised level bounds
            int min = ZstdCompressionLevel.minCompressionLevel();
            int def = ZstdCompressionLevel.defaultCompressionLevel();
            int max = ZstdCompressionLevel.maxCompressionLevel();

            // Then they are ordered min <= default <= max
            assertThat(min).isLessThanOrEqualTo(def);
            assertThat(def).isLessThanOrEqualTo(max);
        }

        @Test
        void defaultLevelIsThree() {
            // Then the library default level is zstd's documented ZSTD_CLEVEL_DEFAULT
            assertThat(ZstdCompressionLevel.defaultCompressionLevel()).isEqualTo(3);
        }
    }

    @Nested
    class CompressBound {

        @ParameterizedTest
        @ValueSource(longs = {0, 1, 1024, 1_000_000})
        void neverUndersizesTheDestination(long srcSize) {
            // When the worst-case bound is queried
            ZstdByteSize bound = Zstd.compressBound(new ZstdByteSize(srcSize));

            // Then it is at least the input size
            assertThat(bound.value()).isGreaterThanOrEqualTo(srcSize);
        }
    }

    @Nested
    class Errors {

        @Test
        void rejectsCorruptFrame() {
            // Given bytes that are not a zstd frame
            byte[] garbage = "not a zstd frame".getBytes(StandardCharsets.UTF_8);

            // When decompressing
            ThrowingCallable result = () -> Zstd.decompress(garbage);

            // Then it fails
            assertThatThrownBy(result).isInstanceOf(ZstdException.class);
        }

        @Test
        void rejectsOversizedFrameForBuffer() {
            // Given a frame whose content exceeds the caller's maxSize
            byte[] frame = Zstd.compress("0123456789".getBytes(StandardCharsets.UTF_8));

            // When decompressing into too small a buffer
            ThrowingCallable result = () -> Zstd.decompress(frame, new ZstdByteSize(1));

            // Then it fails
            assertThatThrownBy(result).isInstanceOf(ZstdException.class);
        }

        @Test
        void rejectsNullInputWithANamedMessage() {
            // When null is passed where bytes are required
            ThrowingCallable compressNull = () -> Zstd.compress(null);
            ThrowingCallable decompressNull = () -> Zstd.decompress(null);

            // Then it fails fast with a NullPointerException naming the parameter
            assertThatThrownBy(compressNull)
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("src");
            assertThatThrownBy(decompressNull)
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("compressed");
        }
    }

    @Nested
    class Metadata {

        @Test
        void reportsSemanticVersion() {
            // When the runtime version is read / Then it is an x.y.z string
            assertThat(Zstd.version()).matches("\\d+\\.\\d+\\.\\d+");
        }

        @Test
        void reportsVersionNumberConsistentWithTheString() {
            // Given the x.y.z version string split into its parts
            String[] parts = Zstd.version().split("\\.");
            int expected = Integer.parseInt(parts[0]) * 10000
                    + Integer.parseInt(parts[1]) * 100
                    + Integer.parseInt(parts[2]);

            // When the numeric version is read
            int number = Zstd.versionNumber();

            // Then it encodes MAJOR * 10000 + MINOR * 100 + PATCH
            assertThat(number).isEqualTo(expected);
        }
    }

    @Nested
    class DictIdLookup {

        // ZSTD_MAGIC_DICTIONARY (0xEC30A437) little-endian
        private static final byte[] MAGIC = {0x37, (byte) 0xA4, 0x30, (byte) 0xEC};

        @Test
        void readsTheIdStampedInDictionaryBytes() {
            // Given a standard dictionary header carrying a known id
            byte[] dict = standardDict(0x01020304);

            // When the id is read
            ZstdDictionaryId id = Zstd.dictId(dict);

            // Then it matches the stamped value
            assertThat(id).isEqualTo(ZstdDictionaryId.of(0x01020304));
        }

        @Test
        void agreesWithTheZstdDictionaryReader() {
            // Given the same bytes read through both the libzstd and ZDICT readers
            byte[] dict = standardDict(123_456);

            // When each reports the id
            // Then they agree
            assertThat(Zstd.dictId(dict)).isEqualTo(ZstdDictionary.of(dict).id());
        }

        @Test
        void returnsNoneForNonDictionaryBytes() {
            // Given bytes with no dictionary magic
            byte[] notADict = "not a zstd dictionary".getBytes(StandardCharsets.UTF_8);

            // When the id is read / Then it is absent
            assertThat(Zstd.dictId(notADict)).isEqualTo(ZstdDictionaryId.NONE);
        }

        @Test
        void returnsNoneForTooShortInput() {
            // Given fewer than 8 bytes
            // When the id is read / Then it is absent
            assertThat(Zstd.dictId(new byte[] {1, 2, 3})).isEqualTo(ZstdDictionaryId.NONE);
        }

        @Test
        void segmentOverloadMatchesByteArray() {
            // Given the same dictionary as bytes and as a native segment
            byte[] dict = standardDict(777);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = Zstd.copyIn(arena, dict);

                // When read through the zero-copy overload / Then it agrees
                assertThat(Zstd.dictId(seg)).isEqualTo(Zstd.dictId(dict));
            }
        }

        @Test
        void segmentOverloadRejectsHeapSegment() {
            // Given a heap-backed segment
            MemorySegment heap = MemorySegment.ofArray(standardDict(1));

            // When read through the zero-copy overload
            ThrowingCallable result = () -> Zstd.dictId(heap);

            // Then it fails fast naming the parameter
            assertThatThrownBy(result)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("bytes");
        }

        private static byte[] standardDict(int id) {
            // magic (LE) + dictID (LE) + filler; getDictID reads only the first 8 bytes
            byte[] d = new byte[12];
            System.arraycopy(MAGIC, 0, d, 0, 4);
            d[4] = (byte) id;
            d[5] = (byte) (id >>> 8);
            d[6] = (byte) (id >>> 16);
            d[7] = (byte) (id >>> 24);
            return d;
        }
    }

    @Nested
    class UntrustedInput {

        @Test
        void boundedDecompressRefusesADecompressionBomb() {
            // Given a tiny frame that expands enormously (8 MiB of zeros -> a few bytes)
            byte[] bomb = Zstd.compress(new byte[8 * 1024 * 1024]);
            assertThat(bomb).hasSizeLessThan(1024); // huge amplification ratio

            // When decompressed with a small bound (the safe path for untrusted input)
            ThrowingCallable result = () -> Zstd.decompress(bomb, ZstdByteSize.ofKiB(64));

            // Then it is refused instead of allocating the full expansion
            assertThatThrownBy(result).isInstanceOf(ZstdException.class);
        }

        @Test
        void unboundedDecompressTrustsTheDeclaredSize() {
            // Given the same tiny, highly amplifying frame
            byte[] bomb = Zstd.compress(new byte[8 * 1024 * 1024]);

            // When decompressed without a bound
            byte[] out = Zstd.decompress(bomb);

            // Then it expands fully — documenting that this overload trusts the frame
            // header; untrusted callers must use decompress(byte[], maxSize)
            assertThat(out).hasSize(8 * 1024 * 1024);
        }

        @Test
        void rejectsAFrameDeclaringMoreThanArrayMaxWithoutLeakingArithmeticException() {
            // Given a frame header that declares a content size above Integer.MAX_VALUE
            byte[] frame = frameHeaderDeclaringContentSize(3_000_000_000L);

            // When decompressed via the size-trusting overload
            ThrowingCallable result = () -> Zstd.decompress(frame);

            // Then it fails with a ZstdException, not a raw ArithmeticException
            assertThatThrownBy(result)
                    .isInstanceOf(ZstdException.class)
                    .hasMessageContaining("exceeds the maximum array length");
        }

        @Test
        void rejectsAFrameDeclaringAContentSizeWithTheSignBitSet() {
            // Given a frame header whose 8-byte Frame_Content_Size field reads as
            // Long.MIN_VALUE — zstd stores it as `unsigned long long`, so this is the
            // real value 2^63, not a negative size
            byte[] frame = frameHeaderDeclaringContentSize(Long.MIN_VALUE);

            // When decompressed via the size-trusting overload
            ThrowingCallable result = () -> Zstd.decompress(frame);

            // Then it fails with a ZstdException, not an IllegalArgumentException
            // leaking out of ZstdByteSize's own non-negative guard
            assertThatThrownBy(result)
                    .isInstanceOf(ZstdException.class)
                    .hasMessageContaining("not a valid zstd frame content size");
        }

        // A minimal single-segment zstd frame header (magic + descriptor + 8-byte
        // Frame_Content_Size) declaring `contentSize`; enough for the content-size
        // read, which happens before any block is decoded.
        private static byte[] frameHeaderDeclaringContentSize(long contentSize) {
            byte[] f = new byte[13];
            f[0] = (byte) 0x28;             // magic 0xFD2FB528, little-endian
            f[1] = (byte) 0xB5;
            f[2] = (byte) 0x2F;
            f[3] = (byte) 0xFD;
            f[4] = (byte) 0xE0;             // descriptor: FCS_flag=3 (8 bytes) | single-segment
            for (int i = 0; i < 8; i++) {  // 8-byte little-endian content size
                f[5 + i] = (byte) (contentSize >>> (8 * i));
            }
            return f;
        }
    }
}
