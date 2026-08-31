package io.github.dfa1.zstd;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZstdByteSizeTest {

    @Nested
    class Construction {

        @ParameterizedTest
        @ValueSource(longs = {0L, 1L, 1024L, Integer.MAX_VALUE, (long) Integer.MAX_VALUE + 1, Long.MAX_VALUE})
        void acceptsAnyNonNegativeValue(long value) {
            // Given a non-negative byte size
            // When wrapped
            ZstdByteSize sut = new ZstdByteSize(value);

            // Then it carries that value
            assertThat(sut.value()).isEqualTo(value);
        }
    }

    @Nested
    class Validation {

        @ParameterizedTest
        @ValueSource(longs = {-1L, Long.MIN_VALUE})
        void rejectsNegativeValues(long value) {
            // Given a negative byte size

            // When wrapped
            ThrowingCallable result = () -> new ZstdByteSize(value);

            // Then it is rejected before reaching native code
            assertThatThrownBy(result)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(String.valueOf(value));
        }
    }

    @Nested
    class FromUnsignedFrameHeaderField {

        @Test
        void wrapsZeroAsAPresentSize() {
            // Given the frame header field reading zero (a legitimate empty size,
            // not the "not stored" sentinel)

            // When decoded
            Optional<ZstdByteSize> result = ZstdByteSize.fromUnsignedFrameHeaderField(0L);

            // Then it is present with value zero
            assertThat(result).contains(new ZstdByteSize(0L));
        }

        @Test
        void wrapsAPositiveValue() {
            // Given an in-range positive field reading
            // When decoded
            Optional<ZstdByteSize> result = ZstdByteSize.fromUnsignedFrameHeaderField(1024L);

            // Then it is present with that value
            assertThat(result).contains(new ZstdByteSize(1024L));
        }

        @ParameterizedTest
        @ValueSource(longs = {-1L, Long.MIN_VALUE})
        void isEmptyForAnUnrepresentableValue(long raw) {
            // Given a field reading that wraps negative as a signed long (an
            // unsigned value at or above 2^63)

            // When decoded
            Optional<ZstdByteSize> result = ZstdByteSize.fromUnsignedFrameHeaderField(raw);

            // Then no representable size is reported
            assertThat(result).isEmpty();
        }
    }

    @Nested
    class ToArraySize {

        @ParameterizedTest
        @ValueSource(longs = {0L, 1L, 1024L, Integer.MAX_VALUE})
        void returnsTheValueForAnInRangeSize(long value) {
            // Given a size that fits an int
            ZstdByteSize sut = new ZstdByteSize(value);

            // When narrowed
            int narrowed = sut.toArraySize();

            // Then it is the same value as an int
            assertThat(narrowed).isEqualTo((int) value);
        }

        @Test
        void throwsForAValueAboveIntegerMaxValue() {
            // Given a size that exceeds the maximum array length
            ZstdByteSize sut = new ZstdByteSize((long) Integer.MAX_VALUE + 1);

            // When narrowed
            ThrowingCallable result = sut::toArraySize;

            // Then it is rejected as too large for an array
            assertThatThrownBy(result)
                    .isInstanceOf(ZstdException.class)
                    .hasMessageContaining("exceeds the maximum array length");
        }
    }

    @Nested
    class OfKiB {

        @Test
        void multipliesByOneThousandTwentyFour() {
            // Given a count of KiB
            // When wrapped
            ZstdByteSize sut = ZstdByteSize.ofKiB(8);

            // Then it holds the byte count
            assertThat(sut).isEqualTo(new ZstdByteSize(8 * 1024L));
        }

        @Test
        void rejectsANegativeCount() {
            // Given a negative count of KiB

            // When wrapped
            ThrowingCallable result = () -> ZstdByteSize.ofKiB(-1);

            // Then it is rejected, same as the constructor
            assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void throwsOnOverflowInsteadOfWrapping() {
            // Given a count so large that *1024 overflows a long
            // When wrapped
            ThrowingCallable result = () -> ZstdByteSize.ofKiB(Long.MAX_VALUE / 512);

            // Then it fails fast instead of silently wrapping to a bogus size
            assertThatThrownBy(result).isInstanceOf(ArithmeticException.class);
        }
    }

    @Nested
    class OfMiB {

        @Test
        void multipliesByOneMebibyte() {
            // Given a count of MiB
            // When wrapped
            ZstdByteSize sut = ZstdByteSize.ofMiB(4);

            // Then it holds the byte count
            assertThat(sut).isEqualTo(new ZstdByteSize(4 * 1024L * 1024L));
        }

        @Test
        void rejectsANegativeCount() {
            // Given a negative count of MiB

            // When wrapped
            ThrowingCallable result = () -> ZstdByteSize.ofMiB(-1);

            // Then it is rejected, same as the constructor
            assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void throwsOnOverflowInsteadOfWrapping() {
            // Given a count so large that *1024*1024 overflows a long
            // When wrapped
            ThrowingCallable result = () -> ZstdByteSize.ofMiB(Long.MAX_VALUE / 1024);

            // Then it fails fast instead of silently wrapping to a bogus size
            assertThatThrownBy(result).isInstanceOf(ArithmeticException.class);
        }
    }
}
