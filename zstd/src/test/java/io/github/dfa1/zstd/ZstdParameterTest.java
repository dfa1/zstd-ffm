package io.github.dfa1.zstd;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static io.github.dfa1.zstd.ZstdTestSupport.randomBytes;
import static io.github.dfa1.zstd.ZstdTestSupport.rawFrameWithWindowLog;
import static io.github.dfa1.zstd.ZstdTestSupport.withWrongChecksum;
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
            try (ZstdCompressContext noSum = Zstd.newCompressContext().level(ZstdCompressionLevel.of(9));
                 ZstdCompressContext withSum =
                         Zstd.newCompressContext().level(ZstdCompressionLevel.of(9)).checksum(true)) {
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
            try (ZstdCompressContext ctx = Zstd.newCompressContext().checksum(true)) {
                frame = ctx.compress(PAYLOAD);
            }
            frame[frame.length / 2] ^= 0x01;

            // When decompressed
            ThrowingCallable result = () -> Zstd.decompress(frame, ZstdByteSize.of(PAYLOAD.length));

            // Then the integrity check fails
            assertThatThrownBy(result).isInstanceOf(ZstdException.class);
        }
    }

    @Nested
    class IgnoreChecksum {

        @Test
        void defaultDecoderRejectsAWrongChecksum() {
            // Given a checksummed frame whose stored checksum is flipped
            byte[] frame = withWrongChecksum(PAYLOAD);
            try (ZstdDecompressContext sut = Zstd.newDecompressContext()) {
                // When decoding it with validation on
                ThrowingCallable result = () -> sut.decompress(frame, ZstdByteSize.of(PAYLOAD.length));

                // Then the mismatch is reported as such
                assertThatThrownBy(result)
                        .isInstanceOfSatisfying(ZstdException.class,
                                e -> assertThat(e.code()).isEqualTo(ZstdErrorCode.CHECKSUM_WRONG));
            }
        }

        @Test
        void ignoringTheChecksumRecoversTheIntactPayload() {
            // Given a checksummed frame whose stored checksum is flipped
            byte[] frame = withWrongChecksum(PAYLOAD);

            // When decoding it with validation off
            byte[] restored;
            try (ZstdDecompressContext sut = Zstd.newDecompressContext().ignoreChecksum(true)) {
                restored = sut.decompress(frame, ZstdByteSize.of(PAYLOAD.length));
            }

            // Then the payload comes back untouched
            assertThat(restored).isEqualTo(PAYLOAD);
        }

        @ParameterizedTest
        @ValueSource(booleans = {false, true})
        void framesWithoutAChecksumDecodeEitherWay(boolean ignore) {
            // Given a frame with no checksum
            byte[] frame = Zstd.compress(PAYLOAD);

            // When decoding it with either setting
            byte[] restored;
            try (ZstdDecompressContext sut = Zstd.newDecompressContext().ignoreChecksum(ignore)) {
                restored = sut.decompress(frame, ZstdByteSize.of(PAYLOAD.length));
            }

            // Then it decodes the same
            assertThat(restored).isEqualTo(PAYLOAD);
        }

        @Test
        void togglingBackToFalseRestoresValidation() {
            // Given a decoder that ignored checksums and was switched back
            byte[] frame = withWrongChecksum(PAYLOAD);
            try (ZstdDecompressContext sut = Zstd.newDecompressContext().ignoreChecksum(true).ignoreChecksum(false)) {
                // When decoding a frame with a wrong checksum
                ThrowingCallable result = () -> sut.decompress(frame, ZstdByteSize.of(PAYLOAD.length));

                // Then validation is back on
                assertThatThrownBy(result).isInstanceOf(ZstdException.class);
            }
        }

        @Test
        void otherCorruptionStillFails() {
            // Given a frame with no checksum and a truncated body
            byte[] full = Zstd.compress(PAYLOAD);
            byte[] frame = Arrays.copyOf(full, full.length - 1);
            try (ZstdDecompressContext sut = Zstd.newDecompressContext().ignoreChecksum(true)) {
                // When decoding it with checksum validation off
                ThrowingCallable result = () -> sut.decompress(frame, ZstdByteSize.of(PAYLOAD.length));

                // Then the malformed frame is still rejected
                assertThatThrownBy(result).isInstanceOf(ZstdException.class);
            }
        }
    }

    @Nested
    class Magicless {

        @Test
        void roundTripsWhenBothSidesAreMagicless() {
            // Given a magicless frame
            byte[] frame = compressMagicless(PAYLOAD);

            // When decoding with a magicless decompressor
            byte[] restored;
            try (ZstdDecompressContext sut = Zstd.newDecompressContext().magicless(true)) {
                restored = sut.decompress(frame, ZstdByteSize.of(PAYLOAD.length));
            }

            // Then the input comes back
            assertThat(restored).isEqualTo(PAYLOAD);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 1000})
        void isExactlyFourBytesShorterThanTheStandardFrame(int size) {
            // Given the same input
            byte[] input = randomBytes(size, size);

            // When compressed in both formats
            byte[] standard;
            try (ZstdCompressContext ctx = Zstd.newCompressContext()) {
                standard = ctx.compress(input);
            }
            byte[] magicless = compressMagicless(input);

            // Then only the 4-byte magic number is dropped
            assertThat(magicless).hasSize(standard.length - 4);
            assertThat(Arrays.copyOfRange(standard, 4, standard.length)).isEqualTo(magicless);
        }

        @Test
        void standardDecoderRejectsMagiclessFrame() {
            // Given a magicless frame and a default decompressor
            byte[] frame = compressMagicless(PAYLOAD);
            try (ZstdDecompressContext sut = Zstd.newDecompressContext()) {
                // When decoding it
                ThrowingCallable result = () -> sut.decompress(frame, ZstdByteSize.of(PAYLOAD.length));

                // Then the missing magic number is rejected
                assertThatThrownBy(result).isInstanceOf(ZstdException.class);
            }
        }

        @Test
        void magiclessDecoderRejectsStandardFrame() {
            // Given a standard frame and a magicless decompressor
            byte[] frame = Zstd.compress(PAYLOAD);
            try (ZstdDecompressContext sut = Zstd.newDecompressContext().magicless(true)) {
                // When decoding it
                ThrowingCallable result = () -> sut.decompress(frame, ZstdByteSize.of(PAYLOAD.length));

                // Then the magic number is misread as a header and rejected
                assertThatThrownBy(result).isInstanceOf(ZstdException.class);
            }
        }

        @Test
        void togglingBackToFalseRestoresTheStandardFormat() {
            // Given contexts switched to magicless and back
            byte[] frame;
            try (ZstdCompressContext ctx = Zstd.newCompressContext().magicless(true).magicless(false)) {
                frame = ctx.compress(PAYLOAD);
            }

            // When decoding with a decompressor also toggled back
            byte[] restored;
            try (ZstdDecompressContext sut = Zstd.newDecompressContext().magicless(true).magicless(false)) {
                restored = sut.decompress(frame, ZstdByteSize.of(PAYLOAD.length));
            }

            // Then the frame is standard and decodes
            assertThat(ZstdFrame.isZstdFrame(frame)).isTrue();
            assertThat(restored).isEqualTo(PAYLOAD);
        }

        @Test
        void frameInspectionDoesNotRecognizeMagiclessFrames() {
            // Given a magicless frame
            byte[] frame = compressMagicless(PAYLOAD);

            // When inspecting it with the standard-format helpers
            ThrowingCallable compressedSize = () -> ZstdFrame.compressedSize(frame);
            ThrowingCallable decompressedSize = () -> ZstdFrame.decompressedSize(frame);
            ThrowingCallable header = () -> ZstdFrame.header(frame);
            ThrowingCallable sizeReadingDecode = () -> {
                try (ZstdDecompressContext ctx = Zstd.newDecompressContext().magicless(true)) {
                    ctx.decompress(frame);
                }
            };

            // Then it is not recognized: no frame, no dictionary, size/header queries fail
            assertThat(ZstdFrame.isZstdFrame(frame)).isFalse();
            assertThat(ZstdFrame.dictId(frame)).isEqualTo(ZstdDictionaryId.NONE);
            assertThatThrownBy(compressedSize).isInstanceOf(ZstdException.class);
            assertThatThrownBy(decompressedSize).isInstanceOf(ZstdException.class);
            assertThatThrownBy(header).isInstanceOf(ZstdException.class);
            assertThatThrownBy(sizeReadingDecode).isInstanceOf(ZstdException.class);
        }

        private static byte[] compressMagicless(byte[] input) {
            try (ZstdCompressContext ctx = Zstd.newCompressContext().magicless(true)) {
                return ctx.compress(input);
            }
        }
    }

    @Nested
    class Ratio {

        @Test
        void longDistanceMatchingFindsAMatchTheDefaultSearchMisses() {
            // Given a 1 MiB block repeated after 8 MiB of unrelated filler — far
            // enough back that FASTEST's normal hash table has long since been
            // overwritten by the filler, so only LDM's separate long-range index
            // can still find the repeat.
            byte[] block = randomBytes(0x1D, 1 << 20);
            byte[] filler = randomBytes(0x2D, 1 << 23);
            byte[] data = new byte[block.length + filler.length + block.length];
            System.arraycopy(block, 0, data, 0, block.length);
            System.arraycopy(filler, 0, data, block.length, filler.length);
            System.arraycopy(block, 0, data, block.length + filler.length, block.length);

            byte[] withoutLdm;
            try (ZstdCompressContext ctx = Zstd.newCompressContext().level(ZstdCompressionLevel.FASTEST)) {
                withoutLdm = ctx.compress(data);
            }
            byte[] withLdm;
            try (ZstdCompressContext ctx =
                         Zstd.newCompressContext().level(ZstdCompressionLevel.FASTEST).longDistanceMatching(true)) {
                withLdm = ctx.compress(data);
            }

            // Then LDM recovers the repeated block the default search missed,
            // shrinking the frame by roughly its size — proving the parameter
            // actually reached the encoder rather than being a silent no-op —
            // and the frame still decodes back to the original bytes
            assertThat(withLdm).hasSizeLessThan(withoutLdm.length - block.length / 2);
            assertThat(Zstd.decompress(withLdm)).isEqualTo(data);
        }

        @Test
        void explicitWindowLogRoundTrips() {
            byte[] frame;
            try (ZstdCompressContext ctx = Zstd.newCompressContext().windowLog(ZstdWindowLog.of(24))) {
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
            try (ZstdCompressContext ctx = Zstd.newCompressContext().parameter(parameter, value)) {
                frame = ctx.compress(PAYLOAD);
            }
            // Then the frame still decompresses
            assertThat(Zstd.decompress(frame)).isEqualTo(PAYLOAD);
        }
    }

    @Nested
    class Decompress {

        @ParameterizedTest
        @ValueSource(ints = {0, 10})
        void oneShotDecodeIgnoresWindowLogMax(int windowLogMax) {
            // Given a frame declaring a 2^28 window, above both the default limit (27) and 10
            byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
            byte[] frame = rawFrameWithWindowLog(28, content);
            assertThat(ZstdFrame.header(frame).windowSize()).contains(ZstdByteSize.of(1L << 28));

            // When decoding it one-shot under that limit
            byte[] restored;
            try (ZstdDecompressContext sut = Zstd.newDecompressContext()
                    .windowLogMax(ZstdWindowLog.of(windowLogMax))) {
                restored = sut.decompress(frame, ZstdByteSize.of(content.length));
            }

            // Then it decodes anyway: one-shot decoding never checks the limit
            assertThat(restored).isEqualTo(content);
        }

        @Test
        void windowLogMaxIsAccepted() {
            // Given a decompressor configured with a raised window limit
            byte[] frame = Zstd.compress(PAYLOAD);
            try (ZstdDecompressContext ctx = Zstd.newDecompressContext().windowLogMax(ZstdWindowLog.of(31))) {
                // Then normal frames still decode
                assertThat(ctx.decompress(frame, ZstdByteSize.of(PAYLOAD.length))).isEqualTo(PAYLOAD);
            }
        }

        @Test
        void rejectsOutOfRangeValue() {
            // Given a decompression context
            try (ZstdDecompressContext sut = Zstd.newDecompressContext()) {
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
            try (ZstdCompressContext sut = Zstd.newCompressContext().level(ZstdCompressionLevel.of(19))) {
                sut.compress(PAYLOAD);
                sut.reset(ZstdResetDirective.SESSION_ONLY);
                reused = sut.compress(PAYLOAD);
            }
            try (ZstdCompressContext ctx = Zstd.newCompressContext().level(ZstdCompressionLevel.of(19))) {
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
            try (ZstdCompressContext sut = Zstd.newCompressContext().level(ZstdCompressionLevel.of(19))) {
                sut.compress(PAYLOAD);
                sut.reset(directive);
                afterReset = sut.compress(PAYLOAD);
            }
            try (ZstdCompressContext ctx = Zstd.newCompressContext()) {
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
            try (ZstdCompressContext sut = Zstd.newCompressContext().level(ZstdCompressionLevel.of(19))) {
                sut.compress(PAYLOAD, dict);
                sut.reset(ZstdResetDirective.SESSION_AND_PARAMETERS);

                // When it compresses against the dictionary again after the reset
                frame = sut.compress(PAYLOAD, dict);
            }

            // Then the frame still round-trips through the same dictionary
            try (ZstdDecompressContext dctx = Zstd.newDecompressContext()) {
                assertThat(dctx.decompress(frame, ZstdByteSize.of(PAYLOAD.length), dict)).isEqualTo(PAYLOAD);
            }
        }

        @Test
        void decompressContextStillDecodesAfterReset() {
            // Given a decompression context reset between frames
            byte[] frame = Zstd.compress(PAYLOAD);
            try (ZstdDecompressContext sut = Zstd.newDecompressContext()) {
                sut.decompress(frame, ZstdByteSize.of(PAYLOAD.length));
                sut.reset(ZstdResetDirective.SESSION_AND_PARAMETERS);

                // Then the next frame still decodes
                assertThat(sut.decompress(frame, ZstdByteSize.of(PAYLOAD.length))).isEqualTo(PAYLOAD);
            }
        }

        @Test
        void resetReturnsTheSameContext() {
            // Given both contexts
            try (ZstdCompressContext cctx = Zstd.newCompressContext();
                 ZstdDecompressContext dctx = Zstd.newDecompressContext()) {
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
            try (ZstdCompressContext sut = Zstd.newCompressContext().level(ZstdCompressionLevel.of(19))) {
                sut.compress(PAYLOAD, dict);
                sut.reset(ZstdResetDirective.SESSION_ONLY);
                afterSessionReset = sut.compress(PAYLOAD, dict);
            }
            byte[] freshLevel19;
            try (ZstdCompressContext ctx = Zstd.newCompressContext().level(ZstdCompressionLevel.of(19))) {
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
            try (ZstdCompressContext sut = Zstd.newCompressContext().level(ZstdCompressionLevel.of(19))) {
                sut.compress(PAYLOAD, dict);
                sut.reset(ZstdResetDirective.PARAMETERS);
                afterParameterReset = sut.compress(PAYLOAD, dict);
            }
            byte[] freshDefaultLevel;
            try (ZstdCompressContext ctx = Zstd.newCompressContext()) {
                freshDefaultLevel = ctx.compress(PAYLOAD, dict);
            }

            // Then the cached level fell back to the default
            assertThat(afterParameterReset).isEqualTo(freshDefaultLevel);
        }

        @Test
        void rejectsNullDirective() {
            // Given a compression context
            try (ZstdCompressContext sut = Zstd.newCompressContext()) {
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
            try (ZstdCompressContext a = Zstd.newCompressContext().parameter(ZstdCompressParameter.COMPRESSION_LEVEL, 17);
                 ZstdCompressContext b = Zstd.newCompressContext().level(ZstdCompressionLevel.of(17))) {
                viaParam = a.compress(PAYLOAD);
                viaMethod = b.compress(PAYLOAD);
            }

            // Then both produce the same frame
            assertThat(viaParam).isEqualTo(viaMethod);
        }

        @Test
        void rejectsOutOfRangeValue() {
            // Given a compression context
            try (ZstdCompressContext sut = Zstd.newCompressContext()) {
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
            try (ZstdCompressContext low = Zstd.newCompressContext().level(ZstdCompressionLevel.FASTEST);
                 ZstdCompressContext high = Zstd.newCompressContext().level(ZstdCompressionLevel.MAX)) {
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
