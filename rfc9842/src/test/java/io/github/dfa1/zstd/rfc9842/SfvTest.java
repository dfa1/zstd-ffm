package io.github.dfa1.zstd.rfc9842;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SfvTest {

    @Test
    void stringRoundTripsPlainText() {
        // Given
        String value = "dictionary-12345";

        // When
        String serialized = Sfv.serializeString(value);
        String parsed = Sfv.parseString(Sfv.cursor(serialized));

        // Then
        assertThat(serialized).isEqualTo("\"dictionary-12345\"");
        assertThat(parsed).isEqualTo(value);
    }

    @Test
    void stringRoundTripsEscapedCharacters() {
        // Given a value containing both characters that must be escaped
        String value = "a \"quoted\" \\backslash\\";

        // When
        String serialized = Sfv.serializeString(value);
        String parsed = Sfv.parseString(Sfv.cursor(serialized));

        // Then the escapes appear in the wire form, and parsing undoes them exactly
        assertThat(serialized).isEqualTo("\"a \\\"quoted\\\" \\\\backslash\\\\\"");
        assertThat(parsed).isEqualTo(value);
    }

    @Test
    void parseStringRejectsAnUnterminatedString() {
        ThrowingCallable result = () -> Sfv.parseString(Sfv.cursor("\"no closing quote"));
        assertThatThrownBy(result).isInstanceOf(Rfc9842Exception.class);
    }

    @Test
    void parseStringRejectsMissingOpeningQuote() {
        ThrowingCallable result = () -> Sfv.parseString(Sfv.cursor("no opening quote\""));
        assertThatThrownBy(result).isInstanceOf(Rfc9842Exception.class);
    }

    @Test
    void tokenRoundTrips() {
        // Given
        String value = "raw";

        // When
        String serialized = Sfv.serializeToken(value);
        String parsed = Sfv.parseToken(Sfv.cursor(serialized));

        // Then
        assertThat(serialized).isEqualTo("raw");
        assertThat(parsed).isEqualTo(value);
    }

    @Test
    void serializeTokenRejectsATokenNotStartingWithALetterOrStar() {
        ThrowingCallable result = () -> Sfv.serializeToken("1abc");
        assertThatThrownBy(result).isInstanceOf(Rfc9842Exception.class);
    }

    @Test
    void parseTokenStopsAtTheFirstInvalidCharacter() {
        // Given a token followed by a character that can't be part of one
        Sfv.Cursor c = Sfv.cursor("raw,more");

        // When
        String token = Sfv.parseToken(c);

        // Then only the token itself is consumed, leaving the rest for the caller
        assertThat(token).isEqualTo("raw");
        assertThat(c.peek()).isEqualTo(',');
    }

    @Test
    void byteSequenceRoundTrips() {
        // Given the RFC 9842 example hash, decoded from the RFC's own base64 text
        byte[] hash = Base64.getDecoder().decode("pZGm1Av0IEBKARczz7exkNYsZb8LzaMrV7J32a2fFG4=");

        // When
        String serialized = Sfv.serializeByteSequence(hash);
        byte[] parsed = Sfv.parseByteSequence(Sfv.cursor(serialized));

        // Then it matches the RFC's own example encoding
        assertThat(serialized).isEqualTo(":pZGm1Av0IEBKARczz7exkNYsZb8LzaMrV7J32a2fFG4=:");
        assertThat(parsed).isEqualTo(hash);
    }

    @Test
    void parseByteSequenceRejectsAnUnterminatedSequence() {
        ThrowingCallable result = () -> Sfv.parseByteSequence(Sfv.cursor(":bm90IGNsb3NlZA=="));
        assertThatThrownBy(result).isInstanceOf(Rfc9842Exception.class);
    }

    @Test
    void parseByteSequenceRejectsInvalidBase64() {
        ThrowingCallable result = () -> Sfv.parseByteSequence(Sfv.cursor(":not valid base64!:"));
        assertThatThrownBy(result).isInstanceOf(Rfc9842Exception.class);
    }

    @Test
    void innerListOfStringsRoundTripsMultipleItems() {
        // Given
        List<String> items = List.of("document", "worker");

        // When
        String serialized = Sfv.serializeInnerListOfStrings(items);
        List<String> parsed = Sfv.parseInnerListOfStrings(Sfv.cursor(serialized));

        // Then
        assertThat(serialized).isEqualTo("(\"document\" \"worker\")");
        assertThat(parsed).isEqualTo(items);
    }

    @Test
    void innerListOfStringsRoundTripsEmpty() {
        // Given
        List<String> items = List.of();

        // When
        String serialized = Sfv.serializeInnerListOfStrings(items);
        List<String> parsed = Sfv.parseInnerListOfStrings(Sfv.cursor(serialized));

        // Then
        assertThat(serialized).isEqualTo("()");
        assertThat(parsed).isEmpty();
    }

    @Test
    void parseInnerListRejectsMissingClosingParen() {
        ThrowingCallable result = () -> Sfv.parseInnerListOfStrings(Sfv.cursor("(\"document\""));
        assertThatThrownBy(result).isInstanceOf(Rfc9842Exception.class);
    }

    @Test
    void parseKeyAcceptsHyphensAndDigits() {
        Sfv.Cursor c = Sfv.cursor("match-dest2=x");
        assertThat(Sfv.parseKey(c)).isEqualTo("match-dest2");
        assertThat(c.peek()).isEqualTo('=');
    }

    @Test
    void parseKeyRejectsAnUppercaseStart() {
        ThrowingCallable result = () -> Sfv.parseKey(Sfv.cursor("Match=x"));
        assertThatThrownBy(result).isInstanceOf(Rfc9842Exception.class);
    }
}
