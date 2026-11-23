package io.github.dfa1.zstd.rfc9842;

import io.github.dfa1.zstd.ZstdDictionary;

import java.util.Objects;
import java.util.Optional;

/// Everything a client needs after fetching a dictionary once: the parsed
/// dictionary itself, where it applies, and the values a later request
/// offering it needs — computed once per fetch rather than left for every
/// caller to re-derive by hand.
///
/// Framework-agnostic like the rest of this package: [#from(byte[], String)]
/// takes the dictionary's raw bytes and the `Use-As-Dictionary` response
/// header's raw value, not an HTTP client's response type, so any HTTP
/// client's caller can build one from what it already fetched.
public final class Rfc9842Negotiation {

    private final ZstdDictionary dictionary;
    private final UseAsDictionaryHeader useAsDictionary;
    private final AvailableDictionaryHeader availableDictionary;
    /// `null` when the server assigned no id.
    private final DictionaryIdHeader dictionaryId;

    private Rfc9842Negotiation(
            ZstdDictionary dictionary,
            UseAsDictionaryHeader useAsDictionary,
            AvailableDictionaryHeader availableDictionary,
            DictionaryIdHeader dictionaryId) {
        Objects.requireNonNull(dictionary, "dictionary");
        Objects.requireNonNull(useAsDictionary, "useAsDictionary");
        Objects.requireNonNull(availableDictionary, "availableDictionary");
        this.dictionary = dictionary;
        this.useAsDictionary = useAsDictionary;
        this.availableDictionary = availableDictionary;
        this.dictionaryId = dictionaryId;
    }

    /// Creates a [Rfc9842Negotiation] from its components; only the library itself builds these.
    ///
    /// @param dictionary          the parsed dictionary
    /// @param useAsDictionary     where/how this dictionary applies, from the
    ///                            `Use-As-Dictionary` response header
    /// @param availableDictionary the dictionary's hash — both the `Available-Dictionary`
    ///                            header value ([AvailableDictionaryHeader#toHeaderValue()])
    ///                            and the `dcz` wire-format hash
    ///                            [Rfc9842Frame#wrap(byte[], AvailableDictionaryHeader)]/
    ///                            [Rfc9842Frame#unwrap(byte[], AvailableDictionaryHeader)]
    ///                            verify against
    /// @param dictionaryId        the `Dictionary-ID` header value to echo back, or `null` if
    ///                            [UseAsDictionaryHeader#id()] was empty (the server assigned
    ///                            no id)
    /// @return the new [Rfc9842Negotiation]
    static Rfc9842Negotiation of(
            ZstdDictionary dictionary,
            UseAsDictionaryHeader useAsDictionary,
            AvailableDictionaryHeader availableDictionary,
            DictionaryIdHeader dictionaryId) {
        return new Rfc9842Negotiation(dictionary, useAsDictionary, availableDictionary, dictionaryId);
    }

    /// Parses `useAsDictionaryHeaderValue` and derives everything a client
    /// needs from `dictionaryBytes` in one call: the dictionary itself, its
    /// hash (for both the `Available-Dictionary` header and the `dcz` wire
    /// format), and the `Dictionary-ID` to echo back if the server assigned
    /// one.
    ///
    /// @param dictionaryBytes            the fetched dictionary's raw bytes
    /// @param useAsDictionaryHeaderValue the `Use-As-Dictionary` response header's raw value
    /// @return everything derived from them
    /// @throws Rfc9842Exception if `useAsDictionaryHeaderValue` is not a valid `Use-As-Dictionary` header
    public static Rfc9842Negotiation from(byte[] dictionaryBytes, String useAsDictionaryHeaderValue) {
        Objects.requireNonNull(dictionaryBytes, "dictionaryBytes");
        Objects.requireNonNull(useAsDictionaryHeaderValue, "useAsDictionaryHeaderValue");
        ZstdDictionary dictionary = ZstdDictionary.of(dictionaryBytes);
        UseAsDictionaryHeader useAsDictionary = UseAsDictionaryHeader.parse(useAsDictionaryHeaderValue);
        AvailableDictionaryHeader availableDictionary = AvailableDictionaryHeader.of(dictionary);
        DictionaryIdHeader dictionaryId = useAsDictionary.id().isEmpty()
                ? null
                : DictionaryIdHeader.of(useAsDictionary.id());
        return new Rfc9842Negotiation(dictionary, useAsDictionary, availableDictionary, dictionaryId);
    }

    /// The parsed dictionary.
    ///
    /// @return the parsed dictionary
    public ZstdDictionary dictionary() {
        return dictionary;
    }

    /// Where/how this dictionary applies, from the `Use-As-Dictionary` response header.
    ///
    /// @return where/how this dictionary applies, from the `Use-As-Dictionary` response header
    public UseAsDictionaryHeader useAsDictionary() {
        return useAsDictionary;
    }

    /// The dictionary's hash — both the `Available-Dictionary` header value
    /// ([AvailableDictionaryHeader#toHeaderValue()]) and the `dcz` wire-format hash
    /// [Rfc9842Frame#wrap(byte[], AvailableDictionaryHeader)]/ [Rfc9842Frame#unwrap(byte[],
    /// AvailableDictionaryHeader)] verify against.
    ///
    /// @return the `availableDictionary` component
    public AvailableDictionaryHeader availableDictionary() {
        return availableDictionary;
    }

    /// The `Dictionary-ID` header value to echo back, or empty if [UseAsDictionaryHeader#id()]
    /// was empty (the server assigned no id).
    ///
    /// @return the `dictionaryId` component
    public Optional<DictionaryIdHeader> dictionaryId() {
        return Optional.ofNullable(dictionaryId);
    }

    /// Value equality over all components.
    ///
    /// @param o the object to compare with
    /// @return `true` if `o` is a [Rfc9842Negotiation] with equal components
    @Override
    public boolean equals(Object o) {
        return o instanceof Rfc9842Negotiation other
                && dictionary.equals(other.dictionary)
                && useAsDictionary.equals(other.useAsDictionary)
                && availableDictionary.equals(other.availableDictionary)
                && Objects.equals(dictionaryId, other.dictionaryId);
    }

    /// Hash code consistent with [#equals(Object)].
    ///
    /// @return the component-based hash code
    @Override
    public int hashCode() {
        return Objects.hash(dictionary, useAsDictionary, availableDictionary, dictionaryId);
    }

    /// Record-style description listing every component.
    ///
    /// @return `Rfc9842Negotiation[component=value, ...]`
    @Override
    public String toString() {
        return "Rfc9842Negotiation[dictionary=" + dictionary + ", useAsDictionary=" + useAsDictionary + ", availableDictionary=" + availableDictionary + ", dictionaryId=" + dictionaryId() + "]";
    }
}
