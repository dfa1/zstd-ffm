package io.github.dfa1.zstd.rfc9842;

import io.github.dfa1.zstd.ZstdDictionary;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Rfc9842DictionaryHashTest {

    private static final byte[] DICT_BYTES =
            "dictionary sample payload ".repeat(64).getBytes(StandardCharsets.UTF_8);

    @Test
    void ofComputesTheDictionarysSha256Hash() throws NoSuchAlgorithmException {
        // Given a dictionary
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);

        // When
        Rfc9842DictionaryHash sut = Rfc9842DictionaryHash.of(dict);

        // Then it matches an independently computed SHA-256
        byte[] expected = MessageDigest.getInstance("SHA-256").digest(DICT_BYTES);
        assertThat(sut.bytes()).isEqualTo(expected);
    }

    @Test
    void ofAgreesWithAvailableDictionaryOnTheSameDictionary() {
        // Given a dictionary
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);

        // When computing both the wire-format hash and the HTTP header hash
        Rfc9842DictionaryHash sut = Rfc9842DictionaryHash.of(dict);
        AvailableDictionary availableDictionary = AvailableDictionary.of(dict);

        // Then they carry the identical bytes, despite being independent types
        assertThat(sut.bytes()).isEqualTo(availableDictionary.hash());
    }

    @Test
    void wrapWithAPrecomputedHashMatchesWrapWithTheDictionary() {
        // Given a dictionary and a precomputed hash for it
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
        Rfc9842DictionaryHash hash = Rfc9842DictionaryHash.of(dict);
        byte[] frame = {1, 2, 3, 4};

        // When wrapped through both the ZstdDictionary and precomputed-hash overloads
        byte[] viaDictionary = Rfc9842Frame.wrap(frame, dict);
        byte[] viaHash = Rfc9842Frame.wrap(frame, hash);

        // Then the results are byte-for-byte identical
        assertThat(viaHash).isEqualTo(viaDictionary);
    }

    @Test
    void unwrapWithAPrecomputedHashRoundTripsLikeUnwrapWithTheDictionary() {
        // Given a dcz-framed blob and a precomputed hash for the dictionary it was wrapped against
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
        Rfc9842DictionaryHash hash = Rfc9842DictionaryHash.of(dict);
        byte[] frame = {1, 2, 3, 4};
        byte[] dcz = Rfc9842Frame.wrap(frame, dict);

        // When unwrapped via the precomputed hash
        byte[] unwrapped = Rfc9842Frame.unwrap(dcz, hash);

        // Then it recovers the original frame, same as unwrapping via the dictionary
        assertThat(unwrapped).isEqualTo(frame);
        assertThat(unwrapped).isEqualTo(Rfc9842Frame.unwrap(dcz, dict));
    }

    @Test
    void constructorRejectsAWrongLengthHash() {
        assertThatThrownBy(() -> new Rfc9842DictionaryHash(new byte[16]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalsAndHashCodeAreContentBased() {
        Rfc9842DictionaryHash a = new Rfc9842DictionaryHash(new byte[32]);
        Rfc9842DictionaryHash b = new Rfc9842DictionaryHash(new byte[32]);

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
    }

    @Test
    void toStringShowsTheHashContentHexEncoded() {
        // Given
        byte[] bytes = new byte[32];
        bytes[0] = (byte) 0xAB;
        bytes[31] = (byte) 0xCD;
        Rfc9842DictionaryHash sut = new Rfc9842DictionaryHash(bytes);

        // When
        String result = sut.toString();

        // Then the content is hex-encoded, not the array's default reference form
        // ("Rfc9842DictionaryHash[bytes=..." would otherwise print something like
        // "[B@1a2b3c4d")
        assertThat(result).startsWith("Rfc9842DictionaryHash[bytes=ab")
                .endsWith("cd]")
                .doesNotContain("@");
    }

    @Test
    void bytesReturnsADefensiveCopy() {
        byte[] original = new byte[32];
        Rfc9842DictionaryHash sut = new Rfc9842DictionaryHash(original);

        sut.bytes()[0] = 42; // mutate the returned copy

        assertThat(sut.bytes()[0]).isZero();
    }

    @Test
    void constructorDefensivelyCopiesTheInputArray() {
        byte[] original = new byte[32];
        Rfc9842DictionaryHash sut = new Rfc9842DictionaryHash(original);

        original[0] = 42; // mutate the array after construction

        assertThat(sut.bytes()[0]).isZero();
    }

    @Test
    void ofRejectsNullDictionary() {
        assertThatThrownBy(() -> Rfc9842DictionaryHash.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructorRejectsNullBytes() {
        assertThatThrownBy(() -> new Rfc9842DictionaryHash(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void notEqualToADifferentHash() {
        Rfc9842DictionaryHash a = new Rfc9842DictionaryHash(new byte[32]);
        byte[] differentBytes = new byte[32];
        differentBytes[0] = 1;
        Rfc9842DictionaryHash b = new Rfc9842DictionaryHash(differentBytes);

        assertThat(a).isNotEqualTo(b);
        assertThat(Arrays.equals(a.bytes(), b.bytes())).isFalse();
    }
}
