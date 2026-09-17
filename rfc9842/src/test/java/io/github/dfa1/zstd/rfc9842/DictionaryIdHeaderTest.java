package io.github.dfa1.zstd.rfc9842;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DictionaryIdHeaderTest {

    @Test
    void httpHeaderIsTheRfc9842HeaderName() {
        assertThat(DictionaryIdHeader.HTTP_HEADER).isEqualTo("Dictionary-ID");
    }

    @Test
    void parseMatchesTheRfc9842ExampleHeaderValue() {
        // Given the RFC's own example header value

        // When
        DictionaryIdHeader sut = DictionaryIdHeader.parse("\"dictionary-12345\"");

        // Then
        assertThat(sut.value()).isEqualTo("dictionary-12345");
        assertThat(sut.toHeaderValue()).isEqualTo("\"dictionary-12345\"");
    }

    @Test
    void roundTripsAValueNeedingEscaping() {
        // Given
        DictionaryIdHeader sut = new DictionaryIdHeader("has \"quotes\" and \\backslashes\\");

        // When
        String header = sut.toHeaderValue();
        DictionaryIdHeader parsed = DictionaryIdHeader.parse(header);

        // Then
        assertThat(parsed).isEqualTo(sut);
    }

    @Test
    void acceptsExactlyTheMaximumLength() {
        String maxLength = "a".repeat(1024);

        DictionaryIdHeader sut = new DictionaryIdHeader(maxLength);

        assertThat(sut.value()).hasSize(1024);
    }

    @Test
    void rejectsOneCharacterOverTheMaximumLength() {
        ThrowingCallable result = () -> new DictionaryIdHeader("a".repeat(1025));

        assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseRejectsMalformedStringSyntax() {
        ThrowingCallable result = () -> DictionaryIdHeader.parse("not a quoted string");

        assertThatThrownBy(result).isInstanceOf(Rfc9842Exception.class);
    }

    @Test
    void parseRejectsTrailingDataAfterTheString() {
        ThrowingCallable result = () -> DictionaryIdHeader.parse("\"ok\" extra");

        assertThatThrownBy(result).isInstanceOf(Rfc9842Exception.class);
    }

    @Test
    void constructorRejectsNullValue() {
        assertThatThrownBy(() -> new DictionaryIdHeader(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void parseRejectsNullHeaderValue() {
        assertThatThrownBy(() -> DictionaryIdHeader.parse(null)).isInstanceOf(NullPointerException.class);
    }
}
