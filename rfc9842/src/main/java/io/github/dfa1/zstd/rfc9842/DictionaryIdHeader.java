package io.github.dfa1.zstd.rfc9842;

import java.util.Objects;

/// The RFC 9842 §2.3 `Dictionary-ID` request header — a client echoing the
/// server-assigned [UseAsDictionaryHeader#id()] back — a Structured Field String
/// of at most 1024 characters (after decoding).
///
/// @param value the identifier text
public record DictionaryIdHeader(String value) {

    /// The HTTP header name this type's value belongs on.
    public static final String HTTP_HEADER = "Dictionary-ID";

    private static final int MAX_LENGTH = 1024;

    /// Validates `value` is at most 1024 characters.
    public DictionaryIdHeader {
        Objects.requireNonNull(value, "value");
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("id must be at most " + MAX_LENGTH + " characters, was " + value.length());
        }
    }

    /// Parses a `Dictionary-ID` header value.
    ///
    /// @param headerValue the raw header value (a Structured Field String)
    /// @return the parsed identifier
    /// @throws Rfc9842Exception if `headerValue` is not a valid string
    ///                          structured field value, or exceeds 1024 characters
    public static DictionaryIdHeader parse(String headerValue) {
        Objects.requireNonNull(headerValue, "headerValue");
        Sfv.Cursor c = Sfv.cursor(headerValue.strip());
        String value = Sfv.parseString(c);
        if (!c.isEmpty()) {
            throw new Rfc9842Exception("malformed Dictionary-ID header: trailing data after the string");
        }
        try {
            return new DictionaryIdHeader(value);
        } catch (IllegalArgumentException e) {
            throw new Rfc9842Exception(e.getMessage(), e);
        }
    }

    /// Renders this as the raw `Dictionary-ID` header value.
    ///
    /// @return the header value, e.g. `"dictionary-12345"`
    public String toHeaderValue() {
        return Sfv.serializeString(value);
    }
}
