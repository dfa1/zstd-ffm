package io.github.dfa1.zstd.rfc9842;

import io.github.dfa1.zstd.ZstdByteSize;
import io.github.dfa1.zstd.ZstdCompressContext;
import io.github.dfa1.zstd.ZstdDecompressContext;
import io.github.dfa1.zstd.ZstdDictionary;
import io.github.dfa1.zstd.ZstdFrame;
import io.github.dfa1.zstd.ZstdMagicVariant;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Rfc9842FrameTest {

    private static final byte[] DICT_BYTES =
            "dictionary sample payload ".repeat(64).getBytes(StandardCharsets.UTF_8);
    private static final byte[] PAYLOAD =
            "hello world hello world hello world ".repeat(50).getBytes(StandardCharsets.UTF_8);

    @Test
    void wrapThenUnwrapRoundTripsAndDecodesTheOriginalPayload() {
        // Given a dictionary and a frame compressed against it
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
        byte[] frame;
        try (ZstdCompressContext cctx = new ZstdCompressContext()) {
            frame = cctx.compress(PAYLOAD, dict);
        }

        // When wrapped and then unwrapped
        byte[] dcz = Rfc9842Frame.wrap(frame, dict);
        byte[] unwrapped = Rfc9842Frame.unwrap(dcz, dict);

        // Then the unwrapped bytes are the original frame, byte-for-byte
        assertThat(unwrapped).isEqualTo(frame);

        // And it still decompresses correctly through the existing dictionary APIs
        byte[] restored;
        try (ZstdDecompressContext dctx = new ZstdDecompressContext()) {
            restored = dctx.decompress(unwrapped, new ZstdByteSize(PAYLOAD.length), dict);
        }
        assertThat(restored).isEqualTo(PAYLOAD);
    }

    @Test
    void wrapProducesTheExactRfc9842HeaderLayout() throws NoSuchAlgorithmException {
        // Given a dictionary and an arbitrary "frame" (the header doesn't care what follows)
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
        byte[] frame = {1, 2, 3, 4};

        // When wrapped
        byte[] dcz = Rfc9842Frame.wrap(frame, dict);

        // Then the first 8 bytes are RFC 9842 §5's fixed skippable-frame magic + size
        // (magic 0x184D2A5E little-endian, then a 32-byte little-endian content length)
        byte[] expectedPrefix = {0x5e, 0x2a, 0x4d, 0x18, 0x20, 0x00, 0x00, 0x00};
        assertThat(Arrays.copyOfRange(dcz, 0, 8)).isEqualTo(expectedPrefix);

        // And the next 32 bytes are the dictionary's SHA-256 hash
        byte[] expectedHash = MessageDigest.getInstance("SHA-256").digest(DICT_BYTES);
        assertThat(Arrays.copyOfRange(dcz, 8, 40)).isEqualTo(expectedHash);

        // And the original frame follows immediately after the 40-byte header
        assertThat(Arrays.copyOfRange(dcz, 40, dcz.length)).isEqualTo(frame);
    }

    @Test
    void unwrapRejectsAMismatchedDictionary() {
        // Given a frame wrapped against one dictionary
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
        byte[] frame;
        try (ZstdCompressContext cctx = new ZstdCompressContext()) {
            frame = cctx.compress(PAYLOAD, dict);
        }
        byte[] dcz = Rfc9842Frame.wrap(frame, dict);

        // When unwrapped against a different dictionary
        ZstdDictionary otherDict = ZstdDictionary.of("a completely different dictionary".repeat(10).getBytes());
        ThrowingCallable result = () -> Rfc9842Frame.unwrap(dcz, otherDict);

        // Then it is rejected
        assertThatThrownBy(result)
                .isInstanceOf(Rfc9842Exception.class)
                .hasMessageContaining("hash");
    }

    @Test
    void unwrapRejectsDataWithNoDczHeader() {
        // Given plain bytes with no skippable header at all
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
        byte[] notDcz = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

        // When unwrapped
        ThrowingCallable result = () -> Rfc9842Frame.unwrap(notDcz, dict);

        // Then it is rejected
        assertThatThrownBy(result)
                .isInstanceOf(Rfc9842Exception.class)
                .hasMessageContaining("dcz frame");
    }

    @Test
    void unwrapRejectsASkippableFrameWithTheWrongMagicVariant() {
        // Given a skippable frame carrying a 32-byte payload, but under a
        // different magic variant than the one RFC 9842 fixes for dcz
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
        byte[] notADczVariant = ZstdFrame.writeSkippableFrame(new byte[32], new ZstdMagicVariant(3));

        // When unwrapped
        ThrowingCallable result = () -> Rfc9842Frame.unwrap(notADczVariant, dict);

        // Then it is rejected as not a dcz frame
        assertThatThrownBy(result)
                .isInstanceOf(Rfc9842Exception.class)
                .hasMessageContaining("magic variant");
    }

    @Test
    void unwrapRejectsAWrongLengthHash() {
        // Given a skippable frame with the correct dcz magic variant, but a
        // content length that isn't a 32-byte SHA-256 hash
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
        byte[] wrongLengthHash = ZstdFrame.writeSkippableFrame(new byte[16], new ZstdMagicVariant(14));

        // When unwrapped
        ThrowingCallable result = () -> Rfc9842Frame.unwrap(wrongLengthHash, dict);

        // Then it is rejected as malformed
        assertThatThrownBy(result)
                .isInstanceOf(Rfc9842Exception.class)
                .hasMessageContaining("32-byte hash");
    }

    @Test
    void wrapRejectsNullArguments() {
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);

        assertThatThrownBy(() -> Rfc9842Frame.wrap(null, dict)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Rfc9842Frame.wrap(new byte[0], (ZstdDictionary) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void unwrapRejectsNullArguments() {
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);

        assertThatThrownBy(() -> Rfc9842Frame.unwrap((byte[]) null, dict)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Rfc9842Frame.unwrap(new byte[0], (ZstdDictionary) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Nested
    class MemorySegmentOverloads {

        @Test
        void wrapThenUnwrapRoundTripsAndDecodesTheOriginalPayload() {
            // Given a dictionary and a frame compressed against it
            ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
            byte[] frame;
            try (ZstdCompressContext cctx = new ZstdCompressContext()) {
                frame = cctx.compress(PAYLOAD, dict);
            }

            try (Arena arena = Arena.ofConfined()) {
                // When wrapped and then unwrapped
                MemorySegment frameSegment = MemorySegment.ofArray(frame);
                MemorySegment dcz = Rfc9842Frame.wrap(arena, frameSegment, dict);
                MemorySegment unwrapped = Rfc9842Frame.unwrap(dcz, dict);

                // Then the unwrapped bytes are the original frame, byte-for-byte
                assertThat(toArray(unwrapped)).isEqualTo(frame);

                // And it still decompresses correctly through the existing dictionary APIs
                byte[] restored;
                try (ZstdDecompressContext dctx = new ZstdDecompressContext()) {
                    restored = dctx.decompress(toArray(unwrapped), new ZstdByteSize(PAYLOAD.length), dict);
                }
                assertThat(restored).isEqualTo(PAYLOAD);
            }
        }

        @Test
        void wrapMatchesTheByteArrayOverloadByteForByte() {
            // Given a dictionary and an arbitrary "frame"
            ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
            byte[] frame = {1, 2, 3, 4};

            // When wrapped through the byte[] overload and, separately, both segment overloads
            byte[] expected = Rfc9842Frame.wrap(frame, dict);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment frameSegment = MemorySegment.ofArray(frame);

                MemorySegment viaArena = Rfc9842Frame.wrap(arena, frameSegment, dict);
                assertThat(toArray(viaArena)).isEqualTo(expected);

                MemorySegment dst = arena.allocate(Rfc9842Frame.HEADER_SIZE + frame.length);
                long written = Rfc9842Frame.wrap(dst, frameSegment, dict);
                assertThat(written).isEqualTo(Rfc9842Frame.HEADER_SIZE + frame.length);
                assertThat(toArray(dst)).isEqualTo(expected);
            }
        }

        @Test
        void wrapAndUnwrapWithAPrecomputedHashMatchTheDictionaryOverloads() {
            // Given a dictionary, its precomputed hash, and an arbitrary "frame"
            ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
            AvailableDictionary hash = AvailableDictionary.of(dict);
            byte[] frame = {1, 2, 3, 4};

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment frameSegment = MemorySegment.ofArray(frame);

                // When wrapped via the precomputed-hash overloads
                MemorySegment viaArena = Rfc9842Frame.wrap(arena, frameSegment, hash);
                MemorySegment dst = arena.allocate(Rfc9842Frame.HEADER_SIZE + frame.length);
                long written = Rfc9842Frame.wrap(dst, frameSegment, hash);

                // Then they match the dictionary-based overload byte-for-byte
                byte[] expected = Rfc9842Frame.wrap(frame, dict);
                assertThat(toArray(viaArena)).isEqualTo(expected);
                assertThat(written).isEqualTo(Rfc9842Frame.HEADER_SIZE + frame.length);
                assertThat(toArray(dst)).isEqualTo(expected);

                // And unwrapping via the precomputed hash recovers the original frame
                assertThat(toArray(Rfc9842Frame.unwrap(viaArena, hash))).isEqualTo(frame);
            }
        }

        @Test
        void unwrapRejectsAMismatchedDictionary() {
            // Given a frame wrapped against one dictionary
            ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
            byte[] frame;
            try (ZstdCompressContext cctx = new ZstdCompressContext()) {
                frame = cctx.compress(PAYLOAD, dict);
            }

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment dcz = Rfc9842Frame.wrap(arena, MemorySegment.ofArray(frame), dict);

                // When unwrapped against a different dictionary
                ZstdDictionary otherDict =
                        ZstdDictionary.of("a completely different dictionary".repeat(10).getBytes());
                ThrowingCallable result = () -> Rfc9842Frame.unwrap(dcz, otherDict);

                // Then it is rejected
                assertThatThrownBy(result)
                        .isInstanceOf(Rfc9842Exception.class)
                        .hasMessageContaining("hash");
            }
        }

        @Test
        void unwrapRejectsDataWithNoDczHeader() {
            // Given plain bytes with no skippable header at all
            ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
            MemorySegment notDcz = MemorySegment.ofArray(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10});

            // When unwrapped
            ThrowingCallable result = () -> Rfc9842Frame.unwrap(notDcz, dict);

            // Then it is rejected
            assertThatThrownBy(result)
                    .isInstanceOf(Rfc9842Exception.class)
                    .hasMessageContaining("dcz frame");
        }

        @Test
        void unwrapRejectsASkippableFrameWithTheWrongMagicVariant() {
            // Given a skippable frame carrying a 32-byte payload, but under a
            // different magic variant than the one RFC 9842 fixes for dcz
            ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
            MemorySegment notADczVariant =
                    MemorySegment.ofArray(ZstdFrame.writeSkippableFrame(new byte[32], new ZstdMagicVariant(3)));

            // When unwrapped
            ThrowingCallable result = () -> Rfc9842Frame.unwrap(notADczVariant, dict);

            // Then it is rejected as not a dcz frame
            assertThatThrownBy(result)
                    .isInstanceOf(Rfc9842Exception.class)
                    .hasMessageContaining("magic variant");
        }

        @Test
        void unwrapRejectsAWrongLengthHash() {
            // Given a skippable frame with the correct dcz magic variant, but a
            // content length that isn't a 32-byte SHA-256 hash
            ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
            MemorySegment wrongLengthHash =
                    MemorySegment.ofArray(ZstdFrame.writeSkippableFrame(new byte[16], new ZstdMagicVariant(14)));

            // When unwrapped
            ThrowingCallable result = () -> Rfc9842Frame.unwrap(wrongLengthHash, dict);

            // Then it is rejected as malformed
            assertThatThrownBy(result)
                    .isInstanceOf(Rfc9842Exception.class)
                    .hasMessageContaining("32-byte hash");
        }

        @Test
        void wrapRejectsNullArguments() {
            ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
            MemorySegment frame = MemorySegment.ofArray(new byte[0]);

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment dst = arena.allocate(Rfc9842Frame.HEADER_SIZE);
                assertThatThrownBy(() -> Rfc9842Frame.wrap(arena, null, dict))
                        .isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> Rfc9842Frame.wrap(arena, frame, (ZstdDictionary) null))
                        .isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> Rfc9842Frame.wrap((Arena) null, frame, dict))
                        .isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> Rfc9842Frame.wrap(dst, null, dict))
                        .isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> Rfc9842Frame.wrap(dst, frame, (ZstdDictionary) null))
                        .isInstanceOf(NullPointerException.class);
                assertThatThrownBy(() -> Rfc9842Frame.wrap((MemorySegment) null, frame, dict))
                        .isInstanceOf(NullPointerException.class);
            }
        }

        @Test
        void unwrapRejectsNullArguments() {
            ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
            MemorySegment empty = MemorySegment.ofArray(new byte[0]);

            assertThatThrownBy(() -> Rfc9842Frame.unwrap((MemorySegment) null, dict))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> Rfc9842Frame.unwrap(empty, (ZstdDictionary) null))
                    .isInstanceOf(NullPointerException.class);
        }

        private static byte[] toArray(MemorySegment segment) {
            byte[] out = new byte[Math.toIntExact(segment.byteSize())];
            MemorySegment.copy(segment, JAVA_BYTE, 0, out, 0, out.length);
            return out;
        }
    }
}
