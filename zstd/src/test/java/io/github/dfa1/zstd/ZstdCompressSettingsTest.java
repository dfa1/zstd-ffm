package io.github.dfa1.zstd;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The setters of [ZstdCompressSettings]: each must store exactly the native
/// value a raw `ZSTD_CCtx_setParameter` call would, on every implementing type.
class ZstdCompressSettingsTest {

    private static final int LOWER = Integer.MIN_VALUE;

    private static final byte[] PAYLOAD =
            "typed compression settings payload ".repeat(500).getBytes(StandardCharsets.UTF_8);

    @Nested
    class NativeValue {

        static Stream<Arguments> settings() {
            return Stream.of(
                    setting("checksum(true)", s -> s.checksum(true), ZstdCompressParameter.CHECKSUM_FLAG, 1),
                    setting("contentSize(false)", s -> s.contentSize(false), ZstdCompressParameter.CONTENT_SIZE_FLAG, 0),
                    setting("dictId(false)", s -> s.dictId(false), ZstdCompressParameter.DICT_ID_FLAG, 0),
                    setting("magicless(true)", s -> s.magicless(true), ZstdCompressParameter.FORMAT, 1),
                    numeric("windowLog", (s, v) -> s.windowLog(v), ZstdCompressParameter.WINDOW_LOG, 20),
                    setting("strategy(BTULTRA2)", s -> s.strategy(ZstdStrategy.BTULTRA2), ZstdCompressParameter.STRATEGY, 9),
                    setting("strategy(FAST)", s -> s.strategy(ZstdStrategy.FAST), ZstdCompressParameter.STRATEGY, 1),
                    setting("longDistanceMatching(true)", s -> s.longDistanceMatching(true),
                            ZstdCompressParameter.ENABLE_LONG_DISTANCE_MATCHING, 1),
                    setting("longDistanceMatching(false)", s -> s.longDistanceMatching(false),
                            ZstdCompressParameter.ENABLE_LONG_DISTANCE_MATCHING, 2),
                    numeric("hashLog", (s, v) -> s.hashLog(v), ZstdCompressParameter.HASH_LOG, LOWER),
                    numeric("chainLog", (s, v) -> s.chainLog(v), ZstdCompressParameter.CHAIN_LOG, LOWER),
                    numeric("searchLog", (s, v) -> s.searchLog(v), ZstdCompressParameter.SEARCH_LOG, LOWER),
                    numeric("minMatch", (s, v) -> s.minMatch(v), ZstdCompressParameter.MIN_MATCH, LOWER),
                    numeric("targetLength", (s, v) -> s.targetLength(v), ZstdCompressParameter.TARGET_LENGTH, 64),
                    numeric("targetCBlockSize", (s, v) -> s.targetCBlockSize(ZstdByteSize.of(v)), ZstdCompressParameter.TARGET_C_BLOCK_SIZE, LOWER),
                    numeric("ldmHashLog", (s, v) -> s.ldmHashLog(v), ZstdCompressParameter.LDM_HASH_LOG, LOWER),
                    numeric("ldmMinMatch", (s, v) -> s.ldmMinMatch(v), ZstdCompressParameter.LDM_MIN_MATCH, LOWER),
                    numeric("ldmBucketSizeLog", (s, v) -> s.ldmBucketSizeLog(v), ZstdCompressParameter.LDM_BUCKET_SIZE_LOG, LOWER),
                    numeric("ldmHashRateLog", (s, v) -> s.ldmHashRateLog(v), ZstdCompressParameter.LDM_HASH_RATE_LOG, 4),
                    numeric("workers", (s, v) -> s.workers(v), ZstdCompressParameter.NB_WORKERS, 2),
                    numeric("jobSize", (s, v) -> s.jobSize(ZstdByteSize.of(v)), ZstdCompressParameter.JOB_SIZE, 1 << 20),
                    numeric("overlapLog", (s, v) -> s.overlapLog(v), ZstdCompressParameter.OVERLAP_LOG, 5),
                    setting("level(19)", s -> s.level(ZstdCompressionLevel.of(19)),
                            ZstdCompressParameter.COMPRESSION_LEVEL, 19));
        }

        @ParameterizedTest
        @MethodSource("settings")
        void contextStoresTheExpectedNativeValue(Consumer<ZstdCompressSettings<?>> setter,
                ZstdCompressParameter parameter, int expected) throws Throwable {
            // Given a compression context
            try (ZstdCompressContext sut = Zstd.newCompressContext()) {
                // When applying the setter
                setter.accept(sut);

                // Then zstd holds exactly the expected raw value
                assertThat(nativeValue(sut.ptr(), parameter)).isEqualTo(expected);
            }
        }

        @ParameterizedTest
        @MethodSource("settings")
        void segmentStreamStoresTheExpectedNativeValue(Consumer<ZstdCompressSettings<?>> setter,
                ZstdCompressParameter parameter, int expected) throws Throwable {
            // Given a segment compress stream
            try (ZstdCompressStream sut = Zstd.newCompressStream()) {
                // When applying the setter
                setter.accept(sut);

                // Then zstd holds exactly the expected raw value
                assertThat(nativeValue(sut.ptr(), parameter)).isEqualTo(expected);
            }
        }
    }

    @Nested
    class OutputStreamSettings {

        @Test
        void checksumReachesTheOutputStreamFrame() throws IOException {
            // Given an output stream with checksums on
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (ZstdOutputStream sut = Zstd.newOutputStream(sink).checksum(true)) {
                // When writing a frame
                sut.write(PAYLOAD);
            }

            // Then the frame header announces a checksum
            assertThat(ZstdFrame.header(sink.toByteArray()).hasChecksum()).isTrue();
        }

        @Test
        void magiclessReachesTheOutputStreamFrame() throws IOException {
            // Given an output stream writing magicless frames
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (ZstdOutputStream sut = Zstd.newOutputStream(sink).magicless(true)) {
                // When writing a frame
                sut.write(PAYLOAD);
            }
            byte[] frame = sink.toByteArray();

            // Then it is not a standard frame, and decodes with a magicless decoder
            assertThat(ZstdFrame.isZstdFrame(frame)).isFalse();
            try (ZstdDecompressContext dctx = Zstd.newDecompressContext().magicless(true)) {
                assertThat(dctx.decompress(frame, ZstdByteSize.lengthOf(PAYLOAD))).isEqualTo(PAYLOAD);
            }
        }

        @Test
        void numericSetterReachesTheOutputStream() throws IOException {
            // Given an output stream whose window is capped by windowLog
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (ZstdOutputStream sut = Zstd.newOutputStream(sink).windowLog(10)) {
                // When writing a frame larger than that window
                sut.write(PAYLOAD);
            }

            // Then the frame header announces the capped window
            assertThat(ZstdFrame.header(sink.toByteArray()).windowSize().orElseThrow().value())
                    .isEqualTo(1L << 10);
        }
    }

    @Nested
    class Bounds {

        static Stream<Arguments> numericSetters() {
            return Stream.of(
                    bounded("windowLog", (s, v) -> s.windowLog(v), ZstdCompressParameter.WINDOW_LOG),
                    bounded("hashLog", (s, v) -> s.hashLog(v), ZstdCompressParameter.HASH_LOG),
                    bounded("chainLog", (s, v) -> s.chainLog(v), ZstdCompressParameter.CHAIN_LOG),
                    bounded("searchLog", (s, v) -> s.searchLog(v), ZstdCompressParameter.SEARCH_LOG),
                    bounded("minMatch", (s, v) -> s.minMatch(v), ZstdCompressParameter.MIN_MATCH),
                    bounded("targetLength", (s, v) -> s.targetLength(v), ZstdCompressParameter.TARGET_LENGTH),
                    bounded("targetCBlockSize", (s, v) -> s.targetCBlockSize(ZstdByteSize.of(v)), ZstdCompressParameter.TARGET_C_BLOCK_SIZE),
                    bounded("ldmHashLog", (s, v) -> s.ldmHashLog(v), ZstdCompressParameter.LDM_HASH_LOG),
                    bounded("ldmMinMatch", (s, v) -> s.ldmMinMatch(v), ZstdCompressParameter.LDM_MIN_MATCH),
                    bounded("ldmBucketSizeLog", (s, v) -> s.ldmBucketSizeLog(v), ZstdCompressParameter.LDM_BUCKET_SIZE_LOG),
                    bounded("ldmHashRateLog", (s, v) -> s.ldmHashRateLog(v), ZstdCompressParameter.LDM_HASH_RATE_LOG),
                    bounded("workers", (s, v) -> s.workers(v), ZstdCompressParameter.NB_WORKERS),
                    bounded("jobSize", (s, v) -> s.jobSize(ZstdByteSize.of(v)), ZstdCompressParameter.JOB_SIZE),
                    bounded("overlapLog", (s, v) -> s.overlapLog(v), ZstdCompressParameter.OVERLAP_LOG));
        }

        @ParameterizedTest
        @MethodSource("numericSetters")
        void acceptsZeroAndBothBounds(BiConsumer<ZstdCompressSettings<?>, Integer> setter,
                ZstdCompressParameter parameter, String setterName) {
            // Given a context and the parameter's native bounds
            ZstdBounds bounds = parameter.bounds();
            try (ZstdCompressContext sut = Zstd.newCompressContext()) {
                // When setting 0, the lower bound and the upper bound
                ThrowingCallable result = () -> {
                    setter.accept(sut, 0);
                    setter.accept(sut, bounds.lowerBound());
                    setter.accept(sut, bounds.upperBound());
                };

                // Then all are accepted
                assertThatCode(result).doesNotThrowAnyException();
            }
        }

        @ParameterizedTest
        @MethodSource("numericSetters")
        void rejectsJustAboveTheUpperBound(BiConsumer<ZstdCompressSettings<?>, Integer> setter,
                ZstdCompressParameter parameter, String setterName) {
            // Given a context and a value just above the upper bound
            int above = parameter.bounds().upperBound() + 1;
            try (ZstdCompressContext sut = Zstd.newCompressContext()) {
                // When setting it
                ThrowingCallable result = () -> setter.accept(sut, above);

                // Then it is rejected before reaching zstd, naming the parameter
                assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining(setterName + "(" + above + ")");
            }
        }

        @ParameterizedTest
        @MethodSource("numericSetters")
        void rejectsJustBelowTheLowerBound(BiConsumer<ZstdCompressSettings<?>, Integer> setter,
                ZstdCompressParameter parameter, String setterName) {
            // Given a context and a non-zero value just below the lower bound (0 itself is always valid)
            int lower = parameter.bounds().lowerBound();
            int below = lower == 1 ? -1 : lower - 1;
            try (ZstdCompressContext sut = Zstd.newCompressContext()) {
                // When setting it
                ThrowingCallable result = () -> setter.accept(sut, below);

                // Then it is rejected before reaching zstd (for a size, already by ZstdByteSize)
                assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class);
            }
        }

        @ParameterizedTest
        @ValueSource(longs = {Integer.MAX_VALUE + 1L, 1L << 32})
        void sizeSettersRejectSizesBeyondTheIntRange(long size) {
            // Given a context and a size that would wrap if narrowed to int before validation
            ZstdByteSize tooLarge = ZstdByteSize.of(size);
            try (ZstdCompressContext sut = Zstd.newCompressContext()) {
                // When setting it as the job size or target block size
                ThrowingCallable jobSize = () -> sut.jobSize(tooLarge);
                ThrowingCallable blockSize = () -> sut.targetCBlockSize(tooLarge);

                // Then both are rejected rather than silently becoming another value
                assertThatThrownBy(jobSize).isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("jobSize(");
                assertThatThrownBy(blockSize).isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("targetCBlockSize(");
            }
        }

        private static Arguments bounded(String name, BiConsumer<ZstdCompressSettings<?>, Integer> setter,
                ZstdCompressParameter parameter) {
            return Arguments.of(Named.of(name, setter), parameter, name);
        }
    }

    @Nested
    class Validation {

        @Test
        void levelAlsoDrivesThePerCallDictionaryPath() {
            // Given varied text, a small unrelated dictionary, and contexts at level 19 and the default
            byte[] text = wordSalad();
            ZstdDictionary dict = ZstdDictionary.of("an unrelated dictionary sample ".repeat(8)
                    .getBytes(StandardCharsets.UTF_8));
            byte[] atNineteen;
            byte[] atDefault;
            try (ZstdCompressContext a = Zstd.newCompressContext();
                 ZstdCompressContext b = Zstd.newCompressContext()) {
                a.level(ZstdCompressionLevel.of(19));
                // When compressing through the per-call dictionary overload, which reads the cached level
                atNineteen = a.compress(text, dict);
                atDefault = b.compress(text, dict);
            }

            // Then level 19 took effect there too
            assertThat(atNineteen).isNotEqualTo(atDefault);
        }

        static Stream<Arguments> nullArguments() {
            return Stream.of(
                    nullCase("level", s -> s.level(null)),
                    nullCase("strategy", s -> s.strategy(null)));
        }

        @ParameterizedTest
        @MethodSource("nullArguments")
        void settersRejectNull(Consumer<ZstdCompressSettings<?>> setter) {
            // Given a compression context
            try (ZstdCompressContext sut = Zstd.newCompressContext()) {
                // When passing null
                ThrowingCallable result = () -> setter.accept(sut);

                // Then it is rejected
                assertThatThrownBy(result).isInstanceOf(NullPointerException.class);
            }
        }

        @Test
        void settersOnAClosedContextThrow() {
            // Given a closed context
            ZstdCompressContext sut = Zstd.newCompressContext();
            sut.close();

            // When setting anything
            ThrowingCallable result = () -> sut.workers(2);

            // Then it fails fast instead of touching the freed context
            assertThatThrownBy(result).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void settersReturnTheSameInstanceForChaining() {
            // Given a context
            try (ZstdCompressContext sut = Zstd.newCompressContext()) {
                // When chaining setters
                ZstdCompressContext result = sut.strategy(ZstdStrategy.LAZY2)
                        .workers(0)
                        .dictId(true);

                // Then each returns the context itself
                assertThat(result).isSameAs(sut);
            }
        }

        /// Seeded, varied text: compressible, but enough so that levels 3 and 19
        /// produce different frames.
        private static byte[] wordSalad() {
            String[] words = {"zstd", "frame", "window", "dictionary", "level", "match", "block", "entropy"};
            Random random = new Random(0x5E7);
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 20_000; i++) {
                out.append(words[random.nextInt(words.length)]).append(random.nextInt(100)).append(' ');
            }
            return out.toString().getBytes(StandardCharsets.UTF_8);
        }

        private static Arguments nullCase(String name, Consumer<ZstdCompressSettings<?>> setter) {
            return Arguments.of(Named.of(name, setter));
        }
    }

    private static Arguments setting(String name, Consumer<ZstdCompressSettings<?>> setter,
            ZstdCompressParameter parameter, int expected) {
        return Arguments.of(Named.of(name, setter), parameter, expected);
    }

    /// A numeric setter applied with `value`; [#LOWER] stands for the parameter's lower bound.
    private static Arguments numeric(String name, BiConsumer<ZstdCompressSettings<?>, Integer> setter,
            ZstdCompressParameter parameter, int value) {
        int actual = value == LOWER ? parameter.bounds().lowerBound() : value;
        return setting(name + "(" + actual + ")", s -> setter.accept(s, actual), parameter, actual);
    }

    private static int nativeValue(MemorySegment cctx, ZstdCompressParameter parameter) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ValueLayout.JAVA_INT);
            long rc = (long) Bindings.CCTX_GET_PARAMETER.invokeExact(cctx, parameter.value(), out);
            assertThat(rc).isZero();
            return out.get(ValueLayout.JAVA_INT, 0);
        }
    }
}
