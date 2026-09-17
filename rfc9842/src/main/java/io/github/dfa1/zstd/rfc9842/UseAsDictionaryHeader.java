package io.github.dfa1.zstd.rfc9842;

import java.util.Objects;
import java.util.regex.Pattern;

/// The RFC 9842 §2.1 `Use-As-Dictionary` response header: tells a client it
/// may store the response body as a dictionary for future requests matching
/// `match`.
///
/// This models the header's *value* only — parsing/building the raw header
/// string and matching a request path against it. Storage/cache lifecycle,
/// freshness, and cross-origin checks are the caller's concern.
///
/// **`match-dest` is not modeled.** It restricts a dictionary to browser
/// fetch destinations (`Sec-Fetch-Dest` values like `"document"`/`"script"`)
/// — meaningless for a non-browser caller. [#parse(String)] still accepts and
/// discards it so a header shared with browser clients keeps parsing;
/// [#toHeaderValue()] never emits it.
///
/// **Match pattern scope**: RFC 9842 itself restricts `match` to a subset of
/// WHATWG URL Pattern with no regex groups. This implementation goes further
/// and supports only literal text plus `*` wildcards (matching any sequence
/// of characters, including none) against the percent-encoded request path
/// — enough for the RFC's own examples (`/app/*/main.js`, `/product/*`).
/// WHATWG named groups (`:name`), optional groups (`{...}`), and custom
/// regex groups are **not** interpreted specially: characters like `{`, `}`,
/// `(`, `)`, and `:` are matched literally, so a pattern relying on them will
/// simply fail to match as a full URL Pattern implementation would expect.
///
/// @param match the URL pattern text (percent-encoded path), matched against
///              request paths via [#matchesPath(String)]
/// @param id    an opaque identifier the client echoes back via
///              [DictionaryIdHeader], or `""` if none
/// @param type  the dictionary content type, or `"raw"` (the only type this
///              library — or RFC 9842 itself, currently — defines)
public record UseAsDictionaryHeader(String match, String id, String type) {

    /// The HTTP header name this type's value belongs on.
    public static final String HTTP_HEADER = "Use-As-Dictionary";

    /// The default, and only currently defined, dictionary content type.
    public static final String TYPE_RAW = "raw";

    private static final int MAX_ID_LENGTH = 1024;

    /// Validates `match`/`id`/`type`.
    public UseAsDictionaryHeader {
        Objects.requireNonNull(match, "match");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        if (match.isEmpty()) {
            throw new IllegalArgumentException("match must not be empty");
        }
        if (id.length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException("id must be at most " + MAX_ID_LENGTH + " characters, was " + id.length());
        }
    }

    /// A `Use-As-Dictionary` value with just a match pattern — no id, the
    /// default `"raw"` type.
    ///
    /// @param match the URL pattern text
    public UseAsDictionaryHeader(String match) {
        this(match, "", TYPE_RAW);
    }

    /// A `Use-As-Dictionary` value with a match pattern and an id to echo
    /// back via [DictionaryIdHeader] — the default `"raw"` type.
    ///
    /// @param match the URL pattern text
    /// @param id    the identifier the client should echo back
    public UseAsDictionaryHeader(String match, String id) {
        this(match, id, TYPE_RAW);
    }

    /// Tests whether `requestPath` matches [#match()], per this
    /// implementation's restricted pattern support (see the class
    /// documentation) — literal text and `*` wildcards only.
    ///
    /// @param requestPath the percent-encoded request path to test
    /// @return `true` if `requestPath` matches [#match()]
    public boolean matchesPath(String requestPath) {
        Objects.requireNonNull(requestPath, "requestPath");
        return compileGlob(match).matcher(requestPath).matches();
    }

    /// Parses a `Use-As-Dictionary` header value.
    ///
    /// @param headerValue the raw header value (a Structured Field Dictionary
    ///                    with `match`/`match-dest`/`id`/`type` members)
    /// @return the parsed value
    /// @throws Rfc9842Exception if `headerValue` is malformed, missing the
    ///                          required `match` member, or uses a member
    ///                          this implementation does not recognize
    public static UseAsDictionaryHeader parse(String headerValue) {
        Objects.requireNonNull(headerValue, "headerValue");
        Sfv.Cursor c = Sfv.cursor(headerValue.strip());
        String match = null;
        String id = "";
        String type = TYPE_RAW;

        while (!c.isEmpty()) {
            String key = Sfv.parseKey(c);
            if (c.isEmpty() || c.peek() != '=') {
                throw new Rfc9842Exception("malformed Use-As-Dictionary header: member '" + key + "' has no value");
            }
            c.consume(); // '='
            switch (key) {
                case "match" -> match = Sfv.parseString(c);
                case "match-dest" -> Sfv.parseInnerListOfStrings(c); // accepted, not modeled — see class doc
                case "id" -> id = Sfv.parseString(c);
                case "type" -> type = Sfv.parseToken(c);
                default -> throw new Rfc9842Exception(
                        "malformed Use-As-Dictionary header: unrecognized member '" + key + "'");
            }
            c.discardOws();
            if (c.isEmpty()) {
                break;
            }
            c.expect(',', "member separator");
            c.discardOws();
            if (c.isEmpty()) {
                throw new Rfc9842Exception("malformed Use-As-Dictionary header: trailing comma");
            }
        }
        if (match == null) {
            throw new Rfc9842Exception("malformed Use-As-Dictionary header: missing required 'match' member");
        }
        try {
            return new UseAsDictionaryHeader(match, id, type);
        } catch (IllegalArgumentException e) {
            throw new Rfc9842Exception(e.getMessage(), e);
        }
    }

    /// Renders this as the raw `Use-As-Dictionary` header value, omitting
    /// members left at their default (`id` empty, `type` `"raw"`).
    ///
    /// @return the header value
    public String toHeaderValue() {
        StringBuilder out = new StringBuilder();
        out.append("match=").append(Sfv.serializeString(match));
        if (!id.isEmpty()) {
            out.append(", id=").append(Sfv.serializeString(id));
        }
        if (!type.equals(TYPE_RAW)) {
            out.append(", type=").append(Sfv.serializeToken(type));
        }
        return out.toString();
    }

    /// Compiles `pattern` into a regex treating `*` as "any characters" and
    /// every other character literally.
    private static Pattern compileGlob(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (String literal : pattern.split("\\*", -1)) {
            regex.append(Pattern.quote(literal)).append(".*");
        }
        regex.setLength(regex.length() - 2); // drop the trailing ".*" added after the last literal
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }
}
