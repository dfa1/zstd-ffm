package io.github.dfa1.zstd;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZstdVersionTest {

    @Nested
    class Encoding {

        @Test
        void decodesThePackedNumberIntoComponents() {
            // Given zstd's packed number for 1.5.7
            // When decoded
            ZstdVersion sut = ZstdVersion.ofNumber(10507);

            // Then the components are recovered
            assertThat(sut).isEqualTo(new ZstdVersion(1, 5, 7));
        }

        @Test
        void reEncodesToThePackedNumber() {
            // Given a version
            ZstdVersion sut = new ZstdVersion(1, 5, 7);

            // Then number() is MAJOR * 10000 + MINOR * 100 + PATCH
            assertThat(sut.number()).isEqualTo(10507);
        }

        @Test
        void rendersAsAnXYZString() {
            // Given a version
            ZstdVersion sut = new ZstdVersion(1, 6, 0);

            // Then it renders as x.y.z
            assertThat(sut).hasToString("1.6.0");
        }
    }

    @Nested
    class Ordering {

        @Test
        void ordersByMajorThenMinorThenPatch() {
            // Then a later patch/minor/major compares greater
            assertThat(new ZstdVersion(1, 5, 7)).isGreaterThan(new ZstdVersion(1, 5, 6));
            assertThat(new ZstdVersion(1, 6, 0)).isGreaterThan(new ZstdVersion(1, 5, 9));
            assertThat(new ZstdVersion(2, 0, 0)).isGreaterThan(new ZstdVersion(1, 9, 9));
        }
    }

    @Nested
    class Validation {

        @Test
        void rejectsANegativeComponent() {
            // When a negative patch is wrapped
            ThrowingCallable result = () -> new ZstdVersion(1, 5, -1);

            // Then it is rejected
            assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void acceptsZeroComponents() {
            // Given every component at its lower boundary
            // When wrapped
            ZstdVersion sut = new ZstdVersion(0, 0, 0);

            // Then zero is accepted, not rejected as if it were negative
            assertThat(sut.major()).isZero();
            assertThat(sut.minor()).isZero();
            assertThat(sut.patch()).isZero();
        }
    }
}
