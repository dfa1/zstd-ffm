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

class AvailableDictionaryHeaderTest {

    private static final byte[] DICT_BYTES =
            "dictionary sample payload ".repeat(64).getBytes(StandardCharsets.UTF_8);

    @Test
    void httpHeaderIsTheRfc9842HeaderName() {
        assertThat(AvailableDictionaryHeader.HTTP_HEADER).isEqualTo("Available-Dictionary");
    }

    @Test
    void parseMatchesTheRfc9842ExampleHeaderValue() {
        // Given the RFC's own example header value

        // When
        AvailableDictionaryHeader sut = AvailableDictionaryHeader.parse(":pZGm1Av0IEBKARczz7exkNYsZb8LzaMrV7J32a2fFG4=:");

        // Then it round-trips back to the identical wire form
        assertThat(sut.toHeaderValue()).isEqualTo(":pZGm1Av0IEBKARczz7exkNYsZb8LzaMrV7J32a2fFG4=:");
    }

    @Test
    void ofComputesTheDictionarysSha256Hash() throws NoSuchAlgorithmException {
        // Given a dictionary
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);

        // When
        AvailableDictionaryHeader sut = AvailableDictionaryHeader.of(dict);

        // Then it matches an independently computed SHA-256, via the one public
        // way to read this type's content back out
        byte[] expected = MessageDigest.getInstance("SHA-256").digest(DICT_BYTES);
        assertThat(sut.toHeaderValue()).isEqualTo(Sfv.serializeByteSequence(expected));
    }

    @Test
    void ofAgreesWithRfc9842FrameOnTheSameDictionary() {
        // Given a dictionary compressed and wrapped via the dcz codec
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
        byte[] fakeFrame = {1, 2, 3};
        byte[] dcz = Rfc9842Frame.wrap(fakeFrame, dict);

        // When computing the Available-Dictionary hash for the same dictionary
        AvailableDictionaryHeader sut = AvailableDictionaryHeader.of(dict);

        // Then it matches the hash embedded in the dcz header (bytes 8..40)
        byte[] embeddedHash = Arrays.copyOfRange(dcz, 8, 40);
        assertThat(sut.toHeaderValue()).isEqualTo(Sfv.serializeByteSequence(embeddedHash));
    }

    @Test
    void wrapWithAPrecomputedHashMatchesWrapWithTheDictionary() {
        // Given a dictionary and a precomputed hash for it
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
        AvailableDictionaryHeader hash = AvailableDictionaryHeader.of(dict);
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
        AvailableDictionaryHeader hash = AvailableDictionaryHeader.of(dict);
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
        ThrowingCallable result = () -> new AvailableDictionaryHeader(new byte[16]);
        assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorRejectsNullHash() {
        assertThatThrownBy(() -> new AvailableDictionaryHeader(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructorDefensivelyCopiesTheInputArray() {
        byte[] original = new byte[32];
        AvailableDictionaryHeader sut = new AvailableDictionaryHeader(original);

        original[0] = 42; // mutate the caller's array after construction

        // sut kept its own snapshot: it still equals a fresh instance built from
        // the all-zero bytes original started as, not the now-mutated array
        assertThat(sut).isEqualTo(new AvailableDictionaryHeader(new byte[32]));
    }

    @Test
    void parseRejectsAWrongLengthHash() {
        // Given a byte sequence that isn't 32 bytes
        String tooShort = Sfv.serializeByteSequence(new byte[16]);

        ThrowingCallable result = () -> AvailableDictionaryHeader.parse(tooShort);

        assertThatThrownBy(result).isInstanceOf(Rfc9842Exception.class);
    }

    @Test
    void parseRejectsMalformedByteSequenceSyntax() {
        ThrowingCallable result = () -> AvailableDictionaryHeader.parse("not a byte sequence at all");
        assertThatThrownBy(result).isInstanceOf(Rfc9842Exception.class);
    }

    @Test
    void equalsAndHashCodeAreContentBased() {
        AvailableDictionaryHeader a = new AvailableDictionaryHeader(new byte[32]);
        AvailableDictionaryHeader b = new AvailableDictionaryHeader(new byte[32]);

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
    }

    @Test
    void notEqualToADifferentHash() {
        AvailableDictionaryHeader a = new AvailableDictionaryHeader(new byte[32]);
        byte[] differentBytes = new byte[32];
        differentBytes[0] = 1;
        AvailableDictionaryHeader b = new AvailableDictionaryHeader(differentBytes);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void toStringShowsTheHashContentBase64Encoded() {
        // Given
        AvailableDictionaryHeader sut = AvailableDictionaryHeader.parse(":pZGm1Av0IEBKARczz7exkNYsZb8LzaMrV7J32a2fFG4=:");

        // When
        String result = sut.toString();

        // Then the content is the header's base64 form, not the array's default reference form
        assertThat(result).isEqualTo("AvailableDictionaryHeader[hash=:pZGm1Av0IEBKARczz7exkNYsZb8LzaMrV7J32a2fFG4=:]")
                .doesNotContain("@");
    }

    @Test
    void ofRejectsNullDictionary() {
        assertThatThrownBy(() -> AvailableDictionaryHeader.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void parseRejectsNullHeaderValue() {
        assertThatThrownBy(() -> AvailableDictionaryHeader.parse(null)).isInstanceOf(NullPointerException.class);
    }
}
