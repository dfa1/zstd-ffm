package io.github.dfa1.zstd;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class ZstdErrorTest {

    private static final byte[] PAYLOAD = "typed error payload ".repeat(200).getBytes(StandardCharsets.UTF_8);

    @Nested
    class Code {

        @Test
        void reportsDstSizeTooSmall() {
            // Given a frame decompressed into too small a buffer
            byte[] frame = Zstd.compress(PAYLOAD);

            // When it fails / Then the category is DST_SIZE_TOO_SMALL
            ZstdException ex = catchThrowableOfType(ZstdException.class, () -> Zstd.decompress(frame, ZstdByteSize.of(1)));
            assertThat(ex.code()).isEqualTo(ZstdErrorCode.DST_SIZE_TOO_SMALL);
        }

        @Test
        void carriesTheNativeErrorNameAsTheMessage() {
            // Given a frame decompressed into too small a buffer
            byte[] frame = Zstd.compress(PAYLOAD);

            // When it fails
            ZstdException ex = catchThrowableOfType(ZstdException.class, () -> Zstd.decompress(frame, ZstdByteSize.of(1)));

            // Then the message is the descriptive native error name, not an empty string
            assertThat(ex).hasMessageContaining("too small");
        }

        @Test
        void reportsParameterOutOfBound() {
            try (ZstdCompressContext ctx = Zstd.newCompressContext()) {
                ZstdException ex = catchThrowableOfType(ZstdException.class,
                        () -> ctx.setParameter(ZstdCompressParameter.WINDOW_LOG, 99));
                assertThat(ex.code()).isEqualTo(ZstdErrorCode.PARAMETER_OUT_OF_BOUND);
            }
        }

        @Test
        void reportsANativeCategoryForGarbage() {
            // Given non-zstd input decompressed with a generous buffer
            byte[] garbage = "definitely not a zstd frame at all".getBytes(StandardCharsets.UTF_8);

            // Then the error carries a real native category, not UNKNOWN
            ZstdException ex = catchThrowableOfType(ZstdException.class, () -> Zstd.decompress(garbage, ZstdByteSize.of(1024)));
            assertThat(ex.code()).isNotIn(ZstdErrorCode.UNKNOWN, ZstdErrorCode.NO_ERROR);
        }
    }

    @Nested
    class Mapping {

        @Test
        void mapsKnownValues() {
            assertThat(ZstdErrorCode.of(22)).isEqualTo(ZstdErrorCode.CHECKSUM_WRONG);
            assertThat(ZstdErrorCode.of(70)).isEqualTo(ZstdErrorCode.DST_SIZE_TOO_SMALL);
            assertThat(ZstdErrorCode.CHECKSUM_WRONG.value()).isEqualTo(22);
        }

        @Test
        void mapsUnknownValueToUnknown() {
            assertThat(ZstdErrorCode.of(999_999)).isEqualTo(ZstdErrorCode.UNKNOWN);
        }

        @Test
        void exposesCanonicalDescription() {
            assertThat(ZstdErrorCode.CHECKSUM_WRONG.description()).isNotBlank();
            assertThat(ZstdErrorCode.NO_ERROR.description()).isNotBlank();
        }
    }
}
