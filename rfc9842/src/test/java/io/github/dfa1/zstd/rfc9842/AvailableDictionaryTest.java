package io.github.dfa1.zstd.rfc9842;

import io.github.dfa1.zstd.ZstdDictionary;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvailableDictionaryTest {

    private static final byte[] DICT_BYTES =
            "dictionary sample payload ".repeat(64).getBytes(StandardCharsets.UTF_8);

    @Test
    void parseMatchesTheRfc9842ExampleHeaderValue() {
        // Given the RFC's own example header value

        // When
        AvailableDictionary sut = AvailableDictionary.parse(":pZGm1Av0IEBKARczz7exkNYsZb8LzaMrV7J32a2fFG4=:");

        // Then it round-trips back to the identical wire form
        assertThat(sut.toHeaderValue()).isEqualTo(":pZGm1Av0IEBKARczz7exkNYsZb8LzaMrV7J32a2fFG4=:");
        assertThat(sut.hash()).hasSize(32);
    }

    @Test
    void ofComputesTheDictionarysSha256Hash() throws NoSuchAlgorithmException {
        // Given a dictionary
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);

        // When
        AvailableDictionary sut = AvailableDictionary.of(dict);

        // Then it matches an independently computed SHA-256
        byte[] expected = MessageDigest.getInstance("SHA-256").digest(DICT_BYTES);
        assertThat(sut.hash()).isEqualTo(expected);
    }

    @Test
    void ofAgreesWithRfc9842FrameOnTheSameDictionary() {
        // Given a dictionary compressed and wrapped via the dcz codec
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
        byte[] fakeFrame = {1, 2, 3};
        byte[] dcz = Rfc9842Frame.wrap(fakeFrame, dict);

        // When computing the Available-Dictionary hash for the same dictionary
        AvailableDictionary sut = AvailableDictionary.of(dict);

        // Then it matches the hash embedded in the dcz header (bytes 8..40)
        byte[] embeddedHash = Arrays.copyOfRange(dcz, 8, 40);
        assertThat(sut.hash()).isEqualTo(embeddedHash);
    }

    @Test
    void constructorRejectsAWrongLengthHash() {
        ThrowingCallable result = () -> new AvailableDictionary(new byte[16]);
        assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseRejectsAWrongLengthHash() {
        // Given a byte sequence that isn't 32 bytes
        String tooShort = Sfv.serializeByteSequence(new byte[16]);

        ThrowingCallable result = () -> AvailableDictionary.parse(tooShort);

        assertThatThrownBy(result).isInstanceOf(Rfc9842Exception.class);
    }

    @Test
    void parseRejectsMalformedByteSequenceSyntax() {
        ThrowingCallable result = () -> AvailableDictionary.parse("not a byte sequence at all");
        assertThatThrownBy(result).isInstanceOf(Rfc9842Exception.class);
    }

    @Test
    void equalsAndHashCodeAreContentBased() {
        AvailableDictionary a = new AvailableDictionary(new byte[32]);
        AvailableDictionary b = new AvailableDictionary(new byte[32]);

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
    }

    @Test
    void hashReturnsADefensiveCopy() {
        byte[] original = new byte[32];
        AvailableDictionary sut = new AvailableDictionary(original);

        sut.hash()[0] = 42; // mutate the returned copy

        assertThat(sut.hash()[0]).isZero();
    }

    @Test
    void ofRejectsNullDictionary() {
        assertThatThrownBy(() -> AvailableDictionary.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void parseRejectsNullHeaderValue() {
        assertThatThrownBy(() -> AvailableDictionary.parse(null)).isInstanceOf(NullPointerException.class);
    }
}
