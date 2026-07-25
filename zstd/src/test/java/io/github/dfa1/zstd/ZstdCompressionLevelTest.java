package io.github.dfa1.zstd;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZstdCompressionLevelTest {

    @Nested
    class Construction {

        @ParameterizedTest
        @MethodSource("io.github.dfa1.zstd.ZstdCompressionLevelTest#inRangeLevels")
        void acceptsAnyLevelWithinTheLibraryRange(int value) {
            // Given a raw level inside the linked libzstd's accepted range
            // When wrapped
            ZstdCompressionLevel sut = new ZstdCompressionLevel(value);

            // Then it carries that raw value
            assertThat(sut.value()).isEqualTo(value);
        }
    }

    @Nested
    class Constants {

        @Test
        void defaultMatchesTheLibraryDefault() {
            // Then DEFAULT wraps the library's default level
            assertThat(ZstdCompressionLevel.DEFAULT.value()).isEqualTo(ZstdCompressionLevel.defaultCompressionLevel());
        }

        @Test
        void fastestMatchesTheLibraryMinimum() {
            // Then FASTEST wraps the library's minimum level
            assertThat(ZstdCompressionLevel.FASTEST.value()).isEqualTo(ZstdCompressionLevel.minCompressionLevel());
        }

        @Test
        void maxMatchesTheLibraryMaximum() {
            // Then MAX wraps the library's maximum level
            assertThat(ZstdCompressionLevel.MAX.value()).isEqualTo(ZstdCompressionLevel.maxCompressionLevel());
        }
    }

    @Nested
    class Validation {

        @Test
        void rejectsOneBelowTheMinimum() {
            // Given a level one below the accepted minimum
            int belowMin = ZstdCompressionLevel.minCompressionLevel() - 1;

            // When wrapped
            ThrowingCallable result = () -> new ZstdCompressionLevel(belowMin);

            // Then it is rejected before reaching native code
            assertThatThrownBy(result)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(String.valueOf(belowMin));
        }

        @Test
        void rejectsOneAboveTheMaximum() {
            // Given a level one above the accepted maximum
            int aboveMax = ZstdCompressionLevel.maxCompressionLevel() + 1;

            // When wrapped
            ThrowingCallable result = () -> new ZstdCompressionLevel(aboveMax);

            // Then it is rejected before reaching native code
            assertThatThrownBy(result)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(String.valueOf(aboveMax));
        }
    }

    private static Stream<Integer> inRangeLevels() {
        return IntStream.of(
                        ZstdCompressionLevel.minCompressionLevel(),
                        0,
                        1,
                        ZstdCompressionLevel.defaultCompressionLevel(),
                        ZstdCompressionLevel.maxCompressionLevel())
                .boxed();
    }
}
