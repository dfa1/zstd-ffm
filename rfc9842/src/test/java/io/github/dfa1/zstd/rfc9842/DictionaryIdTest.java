package io.github.dfa1.zstd.rfc9842;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DictionaryIdTest {

    @Test
    void parseMatchesTheRfc9842ExampleHeaderValue() {
        // Given the RFC's own example header value

        // When
        DictionaryId sut = DictionaryId.parse("\"dictionary-12345\"");

        // Then
        assertThat(sut.value()).isEqualTo("dictionary-12345");
        assertThat(sut.toHeaderValue()).isEqualTo("\"dictionary-12345\"");
    }

    @Test
    void roundTripsAValueNeedingEscaping() {
        // Given
        DictionaryId sut = new DictionaryId("has \"quotes\" and \\backslashes\\");

        // When
        String header = sut.toHeaderValue();
        DictionaryId parsed = DictionaryId.parse(header);

        // Then
        assertThat(parsed).isEqualTo(sut);
    }

    @Test
    void acceptsExactlyTheMaximumLength() {
        String maxLength = "a".repeat(1024);

        DictionaryId sut = new DictionaryId(maxLength);

        assertThat(sut.value()).hasSize(1024);
    }

    @Test
    void rejectsOneCharacterOverTheMaximumLength() {
        ThrowingCallable result = () -> new DictionaryId("a".repeat(1025));

        assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseRejectsMalformedStringSyntax() {
        ThrowingCallable result = () -> DictionaryId.parse("not a quoted string");

        assertThatThrownBy(result).isInstanceOf(Rfc9842Exception.class);
    }

    @Test
    void parseRejectsTrailingDataAfterTheString() {
        ThrowingCallable result = () -> DictionaryId.parse("\"ok\" extra");

        assertThatThrownBy(result).isInstanceOf(Rfc9842Exception.class);
    }

    @Test
    void constructorRejectsNullValue() {
        assertThatThrownBy(() -> new DictionaryId(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void parseRejectsNullHeaderValue() {
        assertThatThrownBy(() -> DictionaryId.parse(null)).isInstanceOf(NullPointerException.class);
    }
}
