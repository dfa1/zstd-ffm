package io.github.dfa1.zstd;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZstdMagicVariantTest {

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 7, 15})
    void acceptsVariantsInRange(int value) {
        // Given a variant selector in 0..15
        // When wrapped
        ZstdMagicVariant sut = new ZstdMagicVariant(value);

        // Then it holds that value
        assertThat(sut.value()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 16, 100})
    void rejectsVariantsOutOfRange(int value) {
        // Given a variant selector outside 0..15
        // When wrapped
        ThrowingCallable result = () -> new ZstdMagicVariant(value);

        // Then it is rejected before reaching native code
        assertThatThrownBy(result)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(value));
    }
}
