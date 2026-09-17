package io.github.dfa1.zstd.rfc9842;

import io.github.dfa1.zstd.ZstdDictionary;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/// The RFC 9842 §2.2 `Available-Dictionary` request header: a Structured
/// Field Byte Sequence carrying the SHA-256 hash of a dictionary the client
/// already has, so the server can decide whether to compress the response
/// against it.
///
/// This is also the value [Rfc9842Frame#wrap(byte[], AvailableDictionaryHeader)]/
/// [Rfc9842Frame#unwrap(byte[], AvailableDictionaryHeader)] embed in and verify
/// against a `dcz` header — the identical 32 bytes either way (§2.2 defines
/// `Available-Dictionary` as this same SHA-256 hash), so one type serves
/// both rather than hashing the dictionary twice for two separately-typed
/// wrappers around the same digest.
///
/// Precomputing this once and reusing it matters for exactly the reason
/// [io.github.dfa1.zstd.ZstdCompressDictionary] exists: hashing a dictionary
/// is cheap for a single call, but redoing it on every `wrap`/`unwrap` in a
/// hot path — once per HTTP request, say — is pure waste once the dictionary
/// itself is fixed.
///
/// A plain class, not a record: a record's canonical accessor for a `byte[]`
/// component is forced public and — unless explicitly overridden — returns
/// the live array, silently breaking immutability for anyone holding an
/// instance. Overriding it (as an earlier version of this type did) closes
/// that specific hole but not the underlying one: the accessor still can't
/// be made *less* visible than the record itself, so there is no way to keep
/// the bytes internal-only the way [#raw()] does here. A hand-written class
/// has no such constraint — `hash` is a private field, `raw()` is
/// package-private for `Rfc9842Frame`'s hot path, and there is no public way
/// to read the bytes back out at all (nothing needs one: `toHeaderValue()`,
/// `equals()`, and `toString()` cover every real use).
///
/// {@snippet :
/// AvailableDictionaryHeader hash = AvailableDictionaryHeader.of(dictionary); // once, at startup
/// // ... per request, on the wire-format side:
/// byte[] dcz = Rfc9842Frame.wrap(frame, hash);
/// // ... and/or on the HTTP header side:
/// String availableDictionary = hash.toHeaderValue();
/// }
public final class AvailableDictionaryHeader {

    /// The HTTP header name this type's value belongs on.
    public static final String HTTP_HEADER = "Available-Dictionary";

    private static final int HASH_LENGTH = 32;

    private final byte[] hash;

    /// Validates `hash` is exactly a SHA-256-length digest and defensively
    /// copies it so this instance owns its bytes.
    ///
    /// Package-private: [#of(ZstdDictionary)] and [#parse(String)] are the
    /// only public ways to get an instance, so every one is either a freshly
    /// computed digest or a value someone actually received on the wire —
    /// never an arbitrary 32 bytes a caller decided to call a hash.
    ///
    /// @param hash the dictionary's SHA-256 hash, exactly 32 bytes
    AvailableDictionaryHeader(byte[] hash) {
        Objects.requireNonNull(hash, "hash");
        if (hash.length != HASH_LENGTH) {
            throw new IllegalArgumentException(
                    "hash must be " + HASH_LENGTH + " bytes (SHA-256), was " + hash.length);
        }
        this.hash = hash.clone();
    }

    /// Computes the `Available-Dictionary` value for `dictionary` — its
    /// SHA-256 hash, the same one [Rfc9842Frame] embeds in a `dcz` header.
    ///
    /// @param dictionary the dictionary content to hash
    /// @return the resulting header value
    public static AvailableDictionaryHeader of(ZstdDictionary dictionary) {
        Objects.requireNonNull(dictionary, "dictionary");
        try {
            return new AvailableDictionaryHeader(MessageDigest.getInstance("SHA-256").digest(dictionary.toByteArray()));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory algorithm every JDK implementation must support
            // (Java Cryptography Architecture Standard Algorithm Names).
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /// Parses an `Available-Dictionary` header value.
    ///
    /// @param headerValue the raw header value (a Structured Field Byte Sequence)
    /// @return the parsed hash
    /// @throws Rfc9842Exception if `headerValue` is not a valid byte-sequence
    ///                          structured field value, or not 32 bytes
    public static AvailableDictionaryHeader parse(String headerValue) {
        Objects.requireNonNull(headerValue, "headerValue");
        Sfv.Cursor c = Sfv.cursor(headerValue.strip());
        byte[] bytes = Sfv.parseByteSequence(c);
        if (!c.isEmpty()) {
            throw new Rfc9842Exception("malformed Available-Dictionary header: trailing data after the byte sequence");
        }
        try {
            return new AvailableDictionaryHeader(bytes);
        } catch (IllegalArgumentException e) {
            throw new Rfc9842Exception(e.getMessage(), e);
        }
    }

    /// Renders this as the raw `Available-Dictionary` header value.
    ///
    /// @return the header value, e.g. `:pZGm1Av0IEBKARczz7exkNYsZb8LzaMrV7J32a2fFG4=:`
    public String toHeaderValue() {
        return Sfv.serializeByteSequence(hash);
    }

    /// Internal: direct view of the hash bytes for `Rfc9842Frame`'s hot path.
    /// Not, and never, exposed publicly — see the class doc.
    byte[] raw() {
        return hash;
    }

    /// Value equality over the hash bytes.
    ///
    /// @param o the object to compare with
    /// @return `true` if `o` is an [AvailableDictionaryHeader] with an equal hash
    @Override
    public boolean equals(Object o) {
        return o instanceof AvailableDictionaryHeader other && Arrays.equals(hash, other.hash);
    }

    /// Hash code consistent with [#equals(Object)], derived from the hash bytes.
    ///
    /// @return the content-based hash code
    @Override
    public int hashCode() {
        return Arrays.hashCode(hash);
    }

    /// String representation carrying the hash's base64 form rather than the
    /// array field's identity hash.
    ///
    /// @return a string with the base64-encoded hash
    @Override
    public String toString() {
        return "AvailableDictionaryHeader[hash=" + toHeaderValue() + "]";
    }
}
