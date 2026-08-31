package io.github.dfa1.zstd;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static io.github.dfa1.zstd.ZstdTestSupport.trainDictionary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZstdFrameTest {

    private static final byte[] PAYLOAD =
            "frame inspection payload ".repeat(500).getBytes(StandardCharsets.UTF_8);
    private static final byte[] GARBAGE = "xx".getBytes(StandardCharsets.UTF_8);

    @Nested
    class IsFrame {

        @Test
        void recognizesAZstdFrame() {
            assertThat(ZstdFrame.isZstdFrame(Zstd.compress(PAYLOAD))).isTrue();
        }

        @Test
        void rejectsGarbage() {
            // Given bytes that are not a zstd frame

            // When checking whether they are a frame
            boolean result = ZstdFrame.isZstdFrame(ZstdFrameTest.GARBAGE);

            // Then it reports false rather than throwing
            assertThat(result).isFalse();
        }
    }

    @Nested
    class CompressedSize {

        @Test
        void equalsLengthForSingleFrame() {
            // Given one frame
            byte[] frame = Zstd.compress(PAYLOAD);

            // Then its compressed size is the whole buffer
            assertThat(ZstdFrame.compressedSize(frame)).isEqualTo(new ZstdByteSize(frame.length));
        }

        @Test
        void locatesFirstFrameBoundaryWhenConcatenated() throws Exception {
            // Given two frames back to back
            byte[] first = Zstd.compress(PAYLOAD);
            byte[] second = Zstd.compress("second".getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream both = new ByteArrayOutputStream();
            both.write(first);
            both.write(second);

            // Then compressedSize reports where the second frame begins
            assertThat(ZstdFrame.compressedSize(both.toByteArray())).isEqualTo(new ZstdByteSize(first.length));
        }

        @Test
        void rejectsGarbage() {
            // Given bytes that are not a zstd frame
            // When asking for the first frame's compressed size
            ThrowingCallable result = () -> ZstdFrame.compressedSize(ZstdFrameTest.GARBAGE);

            // Then it fails
            ZstdFrameTest.rejectsGarbage(result);
        }
    }

    @Nested
    class DecompressedBound {

        @Test
        void boundsTheDecompressedSize() {
            byte[] frame = Zstd.compress(PAYLOAD);
            assertThat(ZstdFrame.decompressedBound(frame).value()).isGreaterThanOrEqualTo(PAYLOAD.length);
        }

        @Test
        void rejectsGarbage() {
            // Given bytes that are not valid zstd data
            // When asking for the decompressed bound
            ThrowingCallable result = () -> ZstdFrame.decompressedBound(ZstdFrameTest.GARBAGE);

            // Then it fails
            ZstdFrameTest.rejectsGarbage(result);
        }
    }

    @Nested
    class DecompressedSize {

        @Test
        void equalsContentSizeForSingleFrame() {
            // Given one frame that records its content size
            byte[] frame = Zstd.compress(PAYLOAD);

            // Then the exact decompressed size is the payload length
            assertThat(ZstdFrame.decompressedSize(frame)).isEqualTo(new ZstdByteSize(PAYLOAD.length));
        }

        @Test
        void sumsAcrossConcatenatedFrames() throws Exception {
            // Given two frames back to back
            byte[] second = "second payload".getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream both = new ByteArrayOutputStream();
            both.write(Zstd.compress(PAYLOAD));
            both.write(Zstd.compress(second));

            // Then the total is the sum of both decompressed sizes
            assertThat(ZstdFrame.decompressedSize(both.toByteArray()))
                    .isEqualTo(new ZstdByteSize((long) PAYLOAD.length + second.length));
        }

        @Test
        void matchesThroughTheSegmentOverload() {
            // Given a frame in a native segment
            byte[] frame = Zstd.compress(PAYLOAD);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = Zstd.copyIn(arena, frame);

                // Then the segment overload agrees with the byte[] overload
                assertThat(ZstdFrame.decompressedSize(seg)).isEqualTo(ZstdFrame.decompressedSize(frame));
            }
        }

        @Test
        void failsWhenAFrameDoesNotRecordItsSize() throws Exception {
            // Given a streamed frame with no pledged size
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (ZstdOutputStream zout = new ZstdOutputStream(sink)) {
                zout.write(PAYLOAD);
            }
            byte[] frame = sink.toByteArray();

            // When the exact total is requested
            ThrowingCallable result = () -> ZstdFrame.decompressedSize(frame);

            // Then it fails because the size was never recorded
            assertThatThrownBy(result)
                    .isInstanceOf(ZstdException.class)
                    .hasMessageContaining("not stored");
        }

        @Test
        void rejectsGarbage() {
            // Given bytes that are not valid zstd data
            // When the exact total is requested
            ThrowingCallable result = () -> ZstdFrame.decompressedSize(ZstdFrameTest.GARBAGE);

            // Then it fails
            ZstdFrameTest.rejectsGarbage(result);
        }
    }

    @Nested
    class HeaderSize {

        @Test
        void matchesTheParsedHeaderSize() {
            // Given a frame
            byte[] frame = Zstd.compress(PAYLOAD);

            // Then the light probe agrees with the fully parsed header
            assertThat(ZstdFrame.headerSize(frame)).isEqualTo(ZstdFrame.header(frame).headerSize());
        }

        @Test
        void computedFromJustTheHeaderPrefix() {
            // Given only the first 5 bytes of a frame (the minimum prefix)
            byte[] frame = Zstd.compress(PAYLOAD);
            byte[] prefix = Arrays.copyOf(frame, 5);

            // Then the header size can still be determined, matching the full frame
            assertThat(ZstdFrame.headerSize(prefix)).isEqualTo(ZstdFrame.headerSize(frame));
        }

        @Test
        void matchesThroughTheSegmentOverload() {
            // Given a frame in a native segment
            byte[] frame = Zstd.compress(PAYLOAD);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = Zstd.copyIn(arena, frame);

                // Then the segment overload agrees with the byte[] overload
                assertThat(ZstdFrame.headerSize(seg)).isEqualTo(ZstdFrame.headerSize(frame));
            }
        }

        @Test
        void rejectsInputShorterThanThePrefix() {
            // Given fewer bytes than the header-size prefix
            byte[] tooShort = Arrays.copyOf(Zstd.compress(PAYLOAD), 2);

            // When the header size is requested
            ThrowingCallable result = () -> ZstdFrame.headerSize(tooShort);

            // Then it fails instead of guessing
            assertThatThrownBy(result).isInstanceOf(ZstdException.class);
        }
    }

    @Nested
    class DecompressionMargin {

        @Test
        void isPositiveForAValidFrame() {
            // Given a frame
            byte[] frame = Zstd.compress(PAYLOAD);

            // Then its in-place margin is a real, positive size
            assertThat(ZstdFrame.decompressionMargin(frame).value()).isPositive();
        }

        @Test
        void matchesThroughTheSegmentOverload() {
            byte[] frame = Zstd.compress(PAYLOAD);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = Zstd.copyIn(arena, frame);
                assertThat(ZstdFrame.decompressionMargin(seg)).isEqualTo(ZstdFrame.decompressionMargin(frame));
            }
        }

        @Test
        void enablesInPlaceDecompression() {
            // Given a frame and a single buffer sized output + margin
            byte[] frame = Zstd.compress(PAYLOAD);
            long margin = ZstdFrame.decompressionMargin(frame).value();
            try (Arena arena = Arena.ofConfined();
                 ZstdDecompressContext dctx = new ZstdDecompressContext()) {
                MemorySegment buf = arena.allocate(PAYLOAD.length + margin);

                // and the compressed frame placed at the very end of that buffer
                MemorySegment in = buf.asSlice(buf.byteSize() - frame.length, frame.length);
                MemorySegment.copy(frame, 0, in, ValueLayout.JAVA_BYTE, 0, frame.length);

                // When decompressed in place (output overlaps the input tail)
                long n = dctx.decompress(buf, in);

                // Then the original is recovered at the start of the same buffer
                byte[] out = new byte[(int) n];
                MemorySegment.copy(buf, ValueLayout.JAVA_BYTE, 0, out, 0, (int) n);
                assertThat(out).isEqualTo(PAYLOAD);
            }
        }

        @Test
        void rejectsGarbage() {
            // Given bytes that are not valid zstd data
            // When the margin is requested
            ThrowingCallable result = () -> ZstdFrame.decompressionMargin(ZstdFrameTest.GARBAGE);

            // Then it fails
            ZstdFrameTest.rejectsGarbage(result);
        }
    }

    @Nested
    class Header {

        @Test
        void reportsContentSizeAndNoChecksumByDefault() {
            // Given a default frame
            ZstdFrameHeader header = ZstdFrame.header(Zstd.compress(PAYLOAD));

            // Then it is a standard frame storing the content size, no checksum, no dict
            assertThat(header.frameType()).isEqualTo(ZstdFrameType.STANDARD);
            assertThat(header.contentSize()).hasValue(new ZstdByteSize(PAYLOAD.length));
            assertThat(header.hasChecksum()).isFalse();
            assertThat(header.dictId()).isEqualTo(ZstdDictionaryId.NONE);
            assertThat(header.windowSize()).hasValueSatisfying(w -> assertThat(w.value()).isPositive());
        }

        @Test
        void reflectsChecksumFlag() {
            byte[] frame;
            try (ZstdCompressContext ctx = new ZstdCompressContext().checksum(true)) {
                frame = ctx.compress(PAYLOAD);
            }
            assertThat(ZstdFrame.header(frame).hasChecksum()).isTrue();
        }

        @Test
        void reportsASaneBlockSizeMax() {
            // Given a default frame
            ZstdFrameHeader header = ZstdFrame.header(Zstd.compress(PAYLOAD));

            // Then blockSizeMax is the masked 32-bit field — a real block size, never
            // the all-ones value a sign-extension or wrong mask would produce
            assertThat(header.blockSizeMax().value()).isPositive().isLessThanOrEqualTo(128 * 1024L);
        }

        @Test
        void rejectsATruncatedHeader() {
            // Given the first two bytes of a frame — too short to hold a header
            byte[] truncated = Arrays.copyOf(Zstd.compress(PAYLOAD), 2);

            // When its header is parsed
            ThrowingCallable result = () -> ZstdFrame.header(truncated);

            // Then it fails instead of returning a bogus header
            assertThatThrownBy(result).isInstanceOf(ZstdException.class);
        }

        @Test
        void contentSizeIsEmptyForAContentSizeWithTheSignBitSet() {
            // Given a frame header whose 8-byte Frame_Content_Size field reads as
            // Long.MIN_VALUE — zstd stores it as `unsigned long long`, so this is the
            // real value 2^63, unrepresentable as a size in this library
            byte[] frame = frameHeaderDeclaringContentSize(Long.MIN_VALUE);

            // When the header is parsed and its content size read
            ZstdFrameHeader header = ZstdFrame.header(frame);

            // Then contentSize() reports absent instead of leaking an
            // IllegalArgumentException out of ZstdByteSize's non-negative guard —
            // and windowSize(), which a single-segment frame mirrors from it, too
            assertThat(header.contentSize()).isEmpty();
            assertThat(header.windowSize()).isEmpty();
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
            for (int i = 0; i < 8; i++) {   // 8-byte little-endian content size
                f[5 + i] = (byte) (contentSize >>> (8 * i));
            }
            return f;
        }
    }

    @Nested
    class Skippable {

        @Test
        void roundTripsContentAndMagicVariant() {
            // Given user metadata wrapped in a skippable frame
            byte[] meta = "sidecar metadata".getBytes(StandardCharsets.UTF_8);
            byte[] frame = ZstdFrame.writeSkippableFrame(meta, new ZstdMagicVariant(7));

            // Then it is recognized as skippable and decodes back with its variant
            assertThat(ZstdFrame.isSkippableFrame(frame)).isTrue();
            assertThat(ZstdFrame.header(frame).frameType()).isEqualTo(ZstdFrameType.SKIPPABLE);

            ZstdSkippableContent read = ZstdFrame.readSkippableFrame(frame);
            assertThat(read.content()).isEqualTo(meta);
            assertThat(read.magicVariant()).isEqualTo(new ZstdMagicVariant(7));
        }

        @Test
        void standardFrameIsNotSkippable() {
            assertThat(ZstdFrame.isSkippableFrame(Zstd.compress(PAYLOAD))).isFalse();
        }

        @Test
        void defensivelyCopiesContentInAndOut() {
            // Given a backing array wrapped in a skippable-content value
            byte[] backing = "metadata".getBytes(StandardCharsets.UTF_8);
            ZstdSkippableContent content = new ZstdSkippableContent(backing, new ZstdMagicVariant(2));

            // When the source array and a value returned by the accessor are mutated
            backing[0] = 'X';
            content.content()[1] = 'X';

            // Then the record's own bytes are untouched
            assertThat(content.content()).isEqualTo("metadata".getBytes(StandardCharsets.UTF_8));
        }

        @Test
        void contentHasValueEqualityOverTheBytesNotArrayIdentity() {
            // Given two separately built payloads with the same bytes and variant, and one differing
            ZstdSkippableContent a = new ZstdSkippableContent("meta".getBytes(StandardCharsets.UTF_8), new ZstdMagicVariant(3));
            ZstdSkippableContent b = new ZstdSkippableContent("meta".getBytes(StandardCharsets.UTF_8), new ZstdMagicVariant(3));
            ZstdSkippableContent differentVariant = new ZstdSkippableContent("meta".getBytes(StandardCharsets.UTF_8), new ZstdMagicVariant(4));

            // When compared by value and rendered as text
            boolean sameBytesEqual = a.equals(b);
            boolean differentVariantEqual = a.equals(differentVariant);

            // Then equality and hashCode follow the content bytes, and toString omits the identity hash
            assertThat(sameBytesEqual).isTrue();
            assertThat(differentVariantEqual).isFalse();
            assertThat(a)
                    .hasSameHashCodeAs(b)
                    .hasToString("ZstdSkippableContent[content=4 bytes, magicVariant=3]");
        }

        @Test
        void hashCodeMatchesTheDocumentedFormula() {
            // Given content bytes and a variant
            byte[] contentBytes = "meta".getBytes(StandardCharsets.UTF_8);
            ZstdMagicVariant variant = new ZstdMagicVariant(3);
            ZstdSkippableContent sut = new ZstdSkippableContent(contentBytes, variant);

            // When hashed
            int hash = sut.hashCode();

            // Then it matches 31 * Arrays.hashCode(content) + magicVariant.hashCode()
            assertThat(hash).isEqualTo(31 * Arrays.hashCode(contentBytes) + variant.hashCode());
        }
    }

    @Nested
    class DictIdLookup {

        @Test
        void isNoneForDictionarylessFrame() {
            assertThat(ZstdFrame.dictId(Zstd.compress(PAYLOAD))).isEqualTo(ZstdDictionaryId.NONE);
        }

        @Test
        void matchesTheDictionaryUsed() {
            // Given a frame compressed with a dictionary
            ZstdDictionary dict = trainDictionary(3000);
            byte[] frame;
            try (ZstdCompressContext ctx = new ZstdCompressContext()) {
                frame = ctx.compress(PAYLOAD, dict);
            }

            // Then the frame's dictionary id matches the dictionary
            assertThat(ZstdFrame.dictId(frame)).isEqualTo(dict.id());
        }

        @Test
        void matchesThroughTheSegmentOverload() {
            // Given a dictionary frame in a native segment
            ZstdDictionary dict = trainDictionary(3000);
            byte[] frame;
            try (ZstdCompressContext ctx = new ZstdCompressContext()) {
                frame = ctx.compress(PAYLOAD, dict);
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = Zstd.copyIn(arena, frame);

                // Then the segment overload reports the same present id
                assertThat(ZstdFrame.dictId(seg)).isNotEqualTo(ZstdDictionaryId.NONE).isEqualTo(dict.id());
            }
        }

    }

    @Nested
    class SegmentOverloads {

        @Test
        void mirrorTheByteArrayOverloadsForANativeFrame() {
            // Given a frame as both a byte[] and the same bytes in a native segment
            byte[] frame = Zstd.compress(PAYLOAD);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = Zstd.copyIn(arena, frame);

                // When inspected through the zero-copy MemorySegment overloads
                // Then each agrees with its byte[] counterpart
                assertThat(ZstdFrame.isZstdFrame(seg)).isEqualTo(ZstdFrame.isZstdFrame(frame));
                assertThat(ZstdFrame.compressedSize(seg)).isEqualTo(ZstdFrame.compressedSize(frame));
                assertThat(ZstdFrame.decompressedBound(seg)).isEqualTo(ZstdFrame.decompressedBound(frame));
                assertThat(ZstdFrame.dictId(seg)).isEqualTo(ZstdFrame.dictId(frame));
            }
        }

        @Test
        void recognizesASkippableNativeFrame() {
            // Given a skippable frame in a native segment
            byte[] frame = ZstdFrame.writeSkippableFrame("meta".getBytes(StandardCharsets.UTF_8), new ZstdMagicVariant(5));
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = Zstd.copyIn(arena, frame);

                // When tested through the MemorySegment overload / Then it is skippable
                assertThat(ZstdFrame.isSkippableFrame(seg)).isTrue();
            }
        }

        @Test
        void headerThroughTheSegmentOverloadMatchesTheByteArrayForm() {
            // Given a frame in a native segment
            byte[] frame = Zstd.compress(PAYLOAD);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = Zstd.copyIn(arena, frame);

                // Then the parsed header equals the one from the byte[] overload
                assertThat(ZstdFrame.header(seg)).isEqualTo(ZstdFrame.header(frame));
            }
        }

        @Test
        void garbageNativeSegmentIsNotAFrame() {
            // Given non-frame bytes in a native segment
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = Zstd.copyIn(arena, "not a frame".getBytes(StandardCharsets.UTF_8));

                // Then the segment overload rejects it
                assertThat(ZstdFrame.isZstdFrame(seg)).isFalse();
            }
        }

        @Test
        void standardNativeFrameIsNotSkippable() {
            // Given a standard frame in a native segment
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = Zstd.copyIn(arena, Zstd.compress(PAYLOAD));

                // Then the segment overload reports it is not skippable
                assertThat(ZstdFrame.isSkippableFrame(seg)).isFalse();
            }
        }
    }

    private static void rejectsGarbage(ThrowingCallable result) {
        assertThatThrownBy(result).isInstanceOf(ZstdException.class);
    }
}
