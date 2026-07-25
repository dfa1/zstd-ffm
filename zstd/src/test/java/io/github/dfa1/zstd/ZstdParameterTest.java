package io.github.dfa1.zstd;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZstdParameterTest {

    private static final byte[] PAYLOAD =
            "advanced-parameter payload ".repeat(2000).getBytes(StandardCharsets.UTF_8);

    @Nested
    class Checksum {

        @Test
        void addsFourByteTrailerAndStillRoundTrips() {
            // Given the same input compressed at the same level with and without a checksum
            byte[] plain;
            byte[] checksummed;
            try (ZstdCompressContext noSum = new ZstdCompressContext().level(new ZstdCompressionLevel(9));
                 ZstdCompressContext withSum =
                         new ZstdCompressContext().level(new ZstdCompressionLevel(9)).checksum(true)) {
                plain = noSum.compress(PAYLOAD);
                checksummed = withSum.compress(PAYLOAD);
            }

            // Then the checksum adds exactly its 4-byte trailer, and the frame still decodes
            assertThat(checksummed).hasSize(plain.length + 4);
            assertThat(Zstd.decompress(checksummed)).isEqualTo(PAYLOAD);
        }

        @Test
        void rejectsCorruptedChecksummedFrame() {
            // Given a checksummed frame with one body byte flipped
            byte[] frame;
            try (ZstdCompressContext ctx = new ZstdCompressContext().checksum(true)) {
                frame = ctx.compress(PAYLOAD);
            }
            frame[frame.length / 2] ^= 0x01;

            // When decompressed
            ThrowingCallable result = () -> Zstd.decompress(frame, new ZstdByteSize(PAYLOAD.length));

            // Then the integrity check fails
            assertThatThrownBy(result).isInstanceOf(ZstdException.class);
        }
    }

    @Nested
    class Ratio {

        @Test
        void longDistanceMatchingRoundTrips() {
            byte[] frame;
            try (ZstdCompressContext ctx =
                         new ZstdCompressContext().level(new ZstdCompressionLevel(3)).longDistanceMatching(true)) {
                frame = ctx.compress(PAYLOAD);
            }
            assertThat(Zstd.decompress(frame)).isEqualTo(PAYLOAD);
        }

        @Test
        void explicitWindowLogRoundTrips() {
            byte[] frame;
            try (ZstdCompressContext ctx = new ZstdCompressContext().windowLog(new ZstdWindowLog(24))) {
                frame = ctx.compress(PAYLOAD);
            }
            assertThat(Zstd.decompress(frame)).isEqualTo(PAYLOAD);
        }
    }

    @Nested
    class Bounds {

        @Test
        void compressionLevelBoundsMatchTheLibrary() {
            ZstdBounds bounds = ZstdCompressParameter.COMPRESSION_LEVEL.bounds();
            assertThat(bounds.lowerBound()).isEqualTo(ZstdCompressionLevel.minCompressionLevel());
            assertThat(bounds.upperBound()).isEqualTo(ZstdCompressionLevel.maxCompressionLevel());
        }

        @ParameterizedTest
        @EnumSource(ZstdCompressParameter.class)
        void everyCompressionParameterReportsBounds(ZstdCompressParameter parameter) {
            ZstdBounds bounds = parameter.bounds();
            assertThat(bounds.upperBound()).isGreaterThanOrEqualTo(bounds.lowerBound());
        }

        @Test
        void decompressionParameterReportsBounds() {
            ZstdBounds bounds = ZstdDecompressParameter.WINDOW_LOG_MAX.bounds();
            assertThat(bounds.upperBound()).isGreaterThan(bounds.lowerBound());
        }
    }

    @Nested
    class MoreParameters {

        @ParameterizedTest
        @EnumSource(value = ZstdCompressParameter.class,
                names = {"TARGET_C_BLOCK_SIZE", "LDM_HASH_LOG", "LDM_MIN_MATCH",
                        "LDM_BUCKET_SIZE_LOG", "STRATEGY"})
        void settingAParameterAtItsLowerBoundRoundTrips(ZstdCompressParameter parameter) {
            // Given a parameter set to a valid in-range value
            int value = Math.max(parameter.bounds().lowerBound(), 1);
            byte[] frame;
            try (ZstdCompressContext ctx = new ZstdCompressContext().parameter(parameter, value)) {
                frame = ctx.compress(PAYLOAD);
            }
            // Then the frame still decompresses
            assertThat(Zstd.decompress(frame)).isEqualTo(PAYLOAD);
        }
    }

    @Nested
    class Decompress {

        @Test
        void windowLogMaxIsAccepted() {
            // Given a decompressor configured with a raised window limit
            byte[] frame = Zstd.compress(PAYLOAD);
            try (ZstdDecompressContext ctx = new ZstdDecompressContext().windowLogMax(new ZstdWindowLog(31))) {
                // Then normal frames still decode
                assertThat(ctx.decompress(frame, new ZstdByteSize(PAYLOAD.length))).isEqualTo(PAYLOAD);
            }
        }

        @Test
        void rejectsOutOfRangeValue() {
            // Given a decompression context
            try (ZstdDecompressContext sut = new ZstdDecompressContext()) {
                // When setting an absurd window-log-max
                ThrowingCallable result = () -> sut.parameter(ZstdDecompressParameter.WINDOW_LOG_MAX, 99);

                // Then it is rejected natively
                assertThatThrownBy(result).isInstanceOf(ZstdException.class);
            }
        }
    }

    @Nested
    class Reset {

        @Test
        void sessionOnlyKeepsLevelAndParameters() {
            // Given a context used once, then reset for the session only
            byte[] reused;
            byte[] fresh;
            try (ZstdCompressContext sut = new ZstdCompressContext().level(new ZstdCompressionLevel(19))) {
                sut.compress(PAYLOAD);
                sut.reset(ZstdResetDirective.SESSION_ONLY);
                reused = sut.compress(PAYLOAD);
            }
            try (ZstdCompressContext ctx = new ZstdCompressContext().level(new ZstdCompressionLevel(19))) {
                fresh = ctx.compress(PAYLOAD);
            }

            // Then the level survives the reset: the next frame matches a fresh level-19 frame
            assertThat(reused).isEqualTo(fresh);
        }

        @ParameterizedTest
        @EnumSource(value = ZstdResetDirective.class, names = {"PARAMETERS", "SESSION_AND_PARAMETERS"})
        void parameterResetRestoresTheDefaultLevel(ZstdResetDirective directive) {
            // Given a level-19 context reset with parameters cleared
            byte[] afterReset;
            byte[] atDefault;
            try (ZstdCompressContext sut = new ZstdCompressContext().level(new ZstdCompressionLevel(19))) {
                sut.compress(PAYLOAD);
                sut.reset(directive);
                afterReset = sut.compress(PAYLOAD);
            }
            try (ZstdCompressContext ctx = new ZstdCompressContext()) {
                atDefault = ctx.compress(PAYLOAD);
            }

            // Then the level falls back to the default, matching a fresh default-level frame
            assertThat(afterReset).isEqualTo(atDefault);
        }

        @Test
        void dictionaryRoundTripsAfterParameterReset() {
            // Given a context that compressed against a dictionary, then cleared its parameters
            ZstdDictionary dict =
                    ZstdDictionary.of("dictionary sample payload ".repeat(64).getBytes(StandardCharsets.UTF_8));
            byte[] frame;
            try (ZstdCompressContext sut = new ZstdCompressContext().level(new ZstdCompressionLevel(19))) {
                sut.compress(PAYLOAD, dict);
                sut.reset(ZstdResetDirective.SESSION_AND_PARAMETERS);

                // When it compresses against the dictionary again after the reset
                frame = sut.compress(PAYLOAD, dict);
            }

            // Then the frame still round-trips through the same dictionary
            try (ZstdDecompressContext dctx = new ZstdDecompressContext()) {
                assertThat(dctx.decompress(frame, new ZstdByteSize(PAYLOAD.length), dict)).isEqualTo(PAYLOAD);
            }
        }

        @Test
        void decompressContextStillDecodesAfterReset() {
            // Given a decompression context reset between frames
            byte[] frame = Zstd.compress(PAYLOAD);
            try (ZstdDecompressContext sut = new ZstdDecompressContext()) {
                sut.decompress(frame, new ZstdByteSize(PAYLOAD.length));
                sut.reset(ZstdResetDirective.SESSION_AND_PARAMETERS);

                // Then the next frame still decodes
                assertThat(sut.decompress(frame, new ZstdByteSize(PAYLOAD.length))).isEqualTo(PAYLOAD);
            }
        }

        @Test
        void resetReturnsTheSameContext() {
            // Given both contexts
            try (ZstdCompressContext cctx = new ZstdCompressContext();
                 ZstdDecompressContext dctx = new ZstdDecompressContext()) {
                // Then reset returns the same instance, for chaining
                assertThat(cctx.reset(ZstdResetDirective.SESSION_ONLY)).isSameAs(cctx);
                assertThat(dctx.reset(ZstdResetDirective.SESSION_ONLY)).isSameAs(dctx);
            }
        }

        @Test
        void sessionOnlyKeepsTheCachedLevelForTheLegacyDictionaryPath() {
            // Given a level-19 context reset for the session only, then compressing
            // against a dictionary — the legacy path that reads the cached level field
            ZstdDictionary dict =
                    ZstdDictionary.of("dictionary sample payload ".repeat(64).getBytes(StandardCharsets.UTF_8));
            byte[] afterSessionReset;
            try (ZstdCompressContext sut = new ZstdCompressContext().level(new ZstdCompressionLevel(19))) {
                sut.compress(PAYLOAD, dict);
                sut.reset(ZstdResetDirective.SESSION_ONLY);
                afterSessionReset = sut.compress(PAYLOAD, dict);
            }
            byte[] freshLevel19;
            try (ZstdCompressContext ctx = new ZstdCompressContext().level(new ZstdCompressionLevel(19))) {
                freshLevel19 = ctx.compress(PAYLOAD, dict);
            }

            // Then the cached level survives the session-only reset
            assertThat(afterSessionReset).isEqualTo(freshLevel19);
        }

        @Test
        void parameterResetClearsTheCachedLevelForTheLegacyDictionaryPath() {
            // Given a level-19 context with parameters reset, then compressing against
            // a dictionary via the legacy path that reads the cached level field
            ZstdDictionary dict =
                    ZstdDictionary.of("dictionary sample payload ".repeat(64).getBytes(StandardCharsets.UTF_8));
            byte[] afterParameterReset;
            try (ZstdCompressContext sut = new ZstdCompressContext().level(new ZstdCompressionLevel(19))) {
                sut.compress(PAYLOAD, dict);
                sut.reset(ZstdResetDirective.PARAMETERS);
                afterParameterReset = sut.compress(PAYLOAD, dict);
            }
            byte[] freshDefaultLevel;
            try (ZstdCompressContext ctx = new ZstdCompressContext()) {
                freshDefaultLevel = ctx.compress(PAYLOAD, dict);
            }

            // Then the cached level fell back to the default
            assertThat(afterParameterReset).isEqualTo(freshDefaultLevel);
        }

        @Test
        void rejectsNullDirective() {
            // Given a compression context
            try (ZstdCompressContext sut = new ZstdCompressContext()) {
                // When reset with a null directive
                ThrowingCallable result = () -> sut.reset(null);

                // Then it fails fast
                assertThatThrownBy(result).isInstanceOf(NullPointerException.class);
            }
        }
    }

    @Nested
    class GenericSetter {

        @Test
        void levelViaParameterMatchesLevelMethod() {
            // Given the level set generically vs via level()
            byte[] viaParam;
            byte[] viaMethod;
            try (ZstdCompressContext a = new ZstdCompressContext().parameter(ZstdCompressParameter.COMPRESSION_LEVEL, 17);
                 ZstdCompressContext b = new ZstdCompressContext().level(new ZstdCompressionLevel(17))) {
                viaParam = a.compress(PAYLOAD);
                viaMethod = b.compress(PAYLOAD);
            }

            // Then both produce the same frame
            assertThat(viaParam).isEqualTo(viaMethod);
        }

        @Test
        void rejectsOutOfRangeValue() {
            // Given a compression context
            try (ZstdCompressContext sut = new ZstdCompressContext()) {
                // When setting an absurd window log
                ThrowingCallable result = () -> sut.parameter(ZstdCompressParameter.WINDOW_LOG, 99);

                // Then it is rejected natively
                assertThatThrownBy(result).isInstanceOf(ZstdException.class);
            }
        }

        @Test
        void levelActuallyAppliesToTheNativeCompressionPath() {
            // Given a level-sensitive payload (enough entropy that the level changes
            // the ratio, so a no-op level() would betray itself)
            byte[] data = levelSensitivePayload();
            byte[] atMin;
            byte[] atMax;

            // When compressing via level() at the minimum and the maximum level
            try (ZstdCompressContext low = new ZstdCompressContext().level(ZstdCompressionLevel.FASTEST);
                 ZstdCompressContext high = new ZstdCompressContext().level(ZstdCompressionLevel.MAX)) {
                atMin = low.compress(data);
                atMax = high.compress(data);
            }

            // Then the higher level produces a strictly smaller frame — proving level()
            // sets the native parameter rather than silently leaving the default
            assertThat(atMax).hasSizeLessThan(atMin.length);
        }

        private static byte[] levelSensitivePayload() {
            byte[] b = new byte[64 * 1024];
            Random r = new Random(0xA11CE);
            for (int i = 0; i < b.length; i++) {
                b[i] = (byte) ((i % 17 == 0) ? r.nextInt(256) : 'a' + (i % 8));
            }
            return b;
        }
    }
}
