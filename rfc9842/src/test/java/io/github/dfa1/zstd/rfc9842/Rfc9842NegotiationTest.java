package io.github.dfa1.zstd.rfc9842;

import io.github.dfa1.zstd.ZstdDictionary;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Rfc9842NegotiationTest {

    private static final byte[] DICT_BYTES =
            "dictionary sample payload ".repeat(64).getBytes(StandardCharsets.UTF_8);

    @Test
    void fromComposesEverythingFromOneFetch() {
        // Given a dictionary's bytes and its Use-As-Dictionary header, as a client would fetch them
        String useAsDictionary = "match=\"/api/*\", id=\"test-v1\"";

        // When
        Rfc9842Negotiation sut = Rfc9842Negotiation.from(DICT_BYTES, useAsDictionary);

        // Then the dictionary parses, the header parses, and the hash matches an
        // independently computed one for the same bytes
        assertThat(sut.dictionary().toByteArray()).isEqualTo(DICT_BYTES);
        assertThat(sut.useAsDictionary().match()).isEqualTo("/api/*");
        assertThat(sut.useAsDictionary().id()).isEqualTo("test-v1");
        assertThat(sut.availableDictionary()).isEqualTo(AvailableDictionaryHeader.of(ZstdDictionary.of(DICT_BYTES)));
        assertThat(sut.dictionaryId()).contains(new DictionaryIdHeader("test-v1"));
    }

    @Test
    void fromLeavesDictionaryIdEmptyWhenUseAsDictionaryHasNoId() {
        // Given a Use-As-Dictionary header with no id
        String useAsDictionary = "match=\"/api/*\"";

        // When
        Rfc9842Negotiation sut = Rfc9842Negotiation.from(DICT_BYTES, useAsDictionary);

        // Then there is nothing to echo back via Dictionary-ID
        assertThat(sut.dictionaryId()).isEmpty();
    }

    @Test
    void fromRejectsAMalformedUseAsDictionaryHeader() {
        ThrowingCallable result = () -> Rfc9842Negotiation.from(DICT_BYTES, "not a valid header");
        assertThatThrownBy(result).isInstanceOf(Rfc9842Exception.class);
    }

    @Test
    void fromRejectsNullDictionaryBytes() {
        assertThatThrownBy(() -> Rfc9842Negotiation.from(null, "match=\"/api/*\""))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void fromRejectsNullHeaderValue() {
        assertThatThrownBy(() -> Rfc9842Negotiation.from(DICT_BYTES, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructorRejectsNullComponents() {
        ZstdDictionary dictionary = ZstdDictionary.of(DICT_BYTES);
        UseAsDictionaryHeader useAsDictionary = new UseAsDictionaryHeader("/api/*");
        AvailableDictionaryHeader availableDictionary = AvailableDictionaryHeader.of(dictionary);

        assertThatThrownBy(() -> new Rfc9842Negotiation(null, useAsDictionary, availableDictionary, Optional.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Rfc9842Negotiation(dictionary, null, availableDictionary, Optional.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Rfc9842Negotiation(dictionary, useAsDictionary, null, Optional.empty()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Rfc9842Negotiation(dictionary, useAsDictionary, availableDictionary, null))
                .isInstanceOf(NullPointerException.class);
    }
}
