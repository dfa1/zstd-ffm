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
///
/// @param dictionary     the parsed dictionary
/// @param useAsDictionary where/how this dictionary applies, from the `Use-As-Dictionary` response header
/// @param hash           the dictionary's hash — both the `Available-Dictionary` header
///                        value ([AvailableDictionaryHeader#toHeaderValue()]) and the `dcz` wire-format
///                        hash [Rfc9842Frame#wrap(byte[], AvailableDictionaryHeader)]/
///                        [Rfc9842Frame#unwrap(byte[], AvailableDictionaryHeader)] verify against
/// @param dictionaryId   the `Dictionary-ID` header value to echo back, or empty if
///                        [UseAsDictionaryHeader#id()] was empty (the server assigned no id)
public record NegotiatedDictionary(ZstdDictionary dictionary, UseAsDictionaryHeader useAsDictionary,
                                    AvailableDictionaryHeader hash, Optional<DictionaryIdHeader> dictionaryId) {

    /// Validates every component is present (use [#from(byte[], String)] for
    /// hostile/unparsed input — this constructor assumes already-valid parts).
    public NegotiatedDictionary {
        Objects.requireNonNull(dictionary, "dictionary");
        Objects.requireNonNull(useAsDictionary, "useAsDictionary");
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(dictionaryId, "dictionaryId");
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
    public static NegotiatedDictionary from(byte[] dictionaryBytes, String useAsDictionaryHeaderValue) {
        Objects.requireNonNull(dictionaryBytes, "dictionaryBytes");
        Objects.requireNonNull(useAsDictionaryHeaderValue, "useAsDictionaryHeaderValue");
        ZstdDictionary dictionary = ZstdDictionary.of(dictionaryBytes);
        UseAsDictionaryHeader useAsDictionary = UseAsDictionaryHeader.parse(useAsDictionaryHeaderValue);
        AvailableDictionaryHeader hash = AvailableDictionaryHeader.of(dictionary);
        Optional<DictionaryIdHeader> dictionaryId = useAsDictionary.id().isEmpty()
                ? Optional.empty()
                : Optional.of(new DictionaryIdHeader(useAsDictionary.id()));
        return new NegotiatedDictionary(dictionary, useAsDictionary, hash, dictionaryId);
    }
}
