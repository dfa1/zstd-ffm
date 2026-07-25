package io.github.dfa1.zstd;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZstdWindowLogTest {

    private static final ZstdBounds BOUNDS = ZstdCompressParameter.WINDOW_LOG.bounds();

    @Nested
    class Construction {

        @Test
        void autoIsZero() {
            // Then AUTO wraps 0, the library-chooses sentinel
            assertThat(ZstdWindowLog.AUTO.value()).isZero();
        }

        @Test
        void acceptsAnInRangeValue() {
            // Given a window log at the low end of the accepted range
            int inRange = BOUNDS.lowerBound();

            // When wrapped
            ZstdWindowLog sut = new ZstdWindowLog(inRange);

            // Then it holds that value
            assertThat(sut.value()).isEqualTo(inRange);
        }

        @Test
        void zeroEqualsAuto() {
            // When zero is wrapped explicitly
            ZstdWindowLog sut = new ZstdWindowLog(0);

            // Then it equals AUTO
            assertThat(sut).isEqualTo(ZstdWindowLog.AUTO);
        }
    }

    @Nested
    class Validation {

        @Test
        void rejectsOneBelowTheMinimum() {
            // Given a non-zero value one below the accepted minimum
            int belowMin = BOUNDS.lowerBound() - 1;

            // When wrapped
            ThrowingCallable result = () -> new ZstdWindowLog(belowMin);

            // Then it is rejected before reaching native code
            assertThatThrownBy(result)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(String.valueOf(belowMin));
        }

        @Test
        void rejectsOneAboveTheMaximum() {
            // Given a value one above the accepted maximum
            int aboveMax = BOUNDS.upperBound() + 1;

            // When wrapped
            ThrowingCallable result = () -> new ZstdWindowLog(aboveMax);

            // Then it is rejected before reaching native code
            assertThatThrownBy(result)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(String.valueOf(aboveMax));
        }
    }
}
