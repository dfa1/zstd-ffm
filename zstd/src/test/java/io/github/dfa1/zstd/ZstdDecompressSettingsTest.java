package io.github.dfa1.zstd;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.github.dfa1.zstd.ZstdTestSupport.withWrongChecksum;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The setters of [ZstdDecompressSettings]: each must store exactly the native value a raw `ZSTD_DCtx_setParameter` call
/// would, on every implementing type.
class ZstdDecompressSettingsTest {

    private static final int WINDOW_LOG_MAX_LOWER = ZstdDecompressParameter.WINDOW_LOG_MAX.bounds().lowerBound();

    private static final byte[] PAYLOAD =
            "typed decompression settings payload ".repeat(500).getBytes(StandardCharsets.UTF_8);

    static Stream<Arguments> settings() {
        return Stream.of(
                setting("ignoreChecksum(true)", s -> s.ignoreChecksum(true),
                        ZstdDecompressParameter.FORCE_IGNORE_CHECKSUM, 1),
                setting("magicless(true)", s -> s.magicless(true), ZstdDecompressParameter.FORMAT, 1),
                setting("windowLogMax(28)", s -> s.windowLogMax(28), ZstdDecompressParameter.WINDOW_LOG_MAX, 28),
                setting("windowLogMax(lower)", s -> s.windowLogMax(WINDOW_LOG_MAX_LOWER),
                        ZstdDecompressParameter.WINDOW_LOG_MAX, WINDOW_LOG_MAX_LOWER));
    }

    @ParameterizedTest
    @MethodSource("settings")
    void contextStoresTheExpectedNativeValue(Consumer<ZstdDecompressSettings<?>> setter,
            ZstdDecompressParameter parameter, int expected) throws Throwable {
        // Given a decompression context
        try (ZstdDecompressContext sut = Zstd.newDecompressContext()) {
            // When applying the setter
            setter.accept(sut);

            // Then zstd holds exactly the expected raw value
            assertThat(nativeValue(sut.ptr(), parameter)).isEqualTo(expected);
        }
    }

    @ParameterizedTest
    @MethodSource("settings")
    void segmentStreamStoresTheExpectedNativeValue(Consumer<ZstdDecompressSettings<?>> setter,
            ZstdDecompressParameter parameter, int expected) throws Throwable {
        // Given a segment decompress stream
        try (ZstdDecompressStream sut = Zstd.newDecompressStream()) {
            // When applying the setter
            setter.accept(sut);

            // Then zstd holds exactly the expected raw value
            assertThat(nativeValue(sut.ptr(), parameter)).isEqualTo(expected);
        }
    }

    @Test
    void ignoreChecksumReachesTheInputStream() throws IOException {
        // Given a frame whose stored checksum is flipped, read by a stream told
        // to skip checksum validation
        byte[] frame = withWrongChecksum(PAYLOAD);
        byte[] restored;
        try (ZstdInputStream sut = Zstd.newInputStream(new ByteArrayInputStream(frame))
                .ignoreChecksum(true)) {
            // When reading it
            restored = sut.readAllBytes();
        }

        // Then the setting took effect: the intact payload comes back
        assertThat(restored).isEqualTo(PAYLOAD);
    }

    @Test
    void settersOnAClosedContextThrow() {
        // Given a closed context
        ZstdDecompressContext sut = Zstd.newDecompressContext();
        sut.close();

        // When setting anything
        ThrowingCallable result = () -> sut.magicless(true);

        // Then it fails fast instead of touching the freed context
        assertThatThrownBy(result).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void windowLogMaxAcceptsZeroAndBothBounds() {
        // Given a context and the native bounds
        ZstdBounds bounds = ZstdDecompressParameter.WINDOW_LOG_MAX.bounds();
        try (ZstdDecompressContext sut = Zstd.newDecompressContext()) {
            // When setting 0, the lower bound and the upper bound
            ThrowingCallable result = () -> sut.windowLogMax(0)
                    .windowLogMax(bounds.lowerBound())
                    .windowLogMax(bounds.upperBound());

            // Then all are accepted
            assertThatCode(result).doesNotThrowAnyException();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 9, 32})
    void windowLogMaxRejectsValuesOutsideTheBounds(int windowLogMax) {
        // Given a context and a non-zero value outside [10, 31]
        try (ZstdDecompressContext sut = Zstd.newDecompressContext()) {
            // When setting it
            ThrowingCallable result = () -> sut.windowLogMax(windowLogMax);

            // Then it is rejected before reaching zstd, naming the parameter
            assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("windowLogMax(" + windowLogMax + ")");
        }
    }

    private static Arguments setting(String name, Consumer<ZstdDecompressSettings<?>> setter,
            ZstdDecompressParameter parameter, int expected) {
        return Arguments.of(Named.of(name, setter), parameter, expected);
    }

    private static int nativeValue(MemorySegment dctx, ZstdDecompressParameter parameter) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ValueLayout.JAVA_INT);
            long rc = (long) Bindings.DCTX_GET_PARAMETER.invokeExact(dctx, parameter.value(), out);
            assertThat(rc).isZero();
            return out.get(ValueLayout.JAVA_INT, 0);
        }
    }
}
