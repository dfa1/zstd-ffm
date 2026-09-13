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
/// @param hash the dictionary's SHA-256 hash, exactly 32 bytes
public record AvailableDictionary(byte[] hash) {

    private static final int HASH_LENGTH = 32;

    /// Validates `hash` is exactly a SHA-256-length digest and defensively
    /// copies it so this record owns its bytes.
    public AvailableDictionary {
        Objects.requireNonNull(hash, "hash");
        if (hash.length != HASH_LENGTH) {
            throw new IllegalArgumentException(
                    "hash must be " + HASH_LENGTH + " bytes (SHA-256), was " + hash.length);
        }
        hash = hash.clone();
    }

    /// The hash bytes, as a fresh copy so the record stays immutable.
    ///
    /// @return a copy of the SHA-256 hash
    @Override
    public byte[] hash() {
        return hash.clone();
    }

    /// Computes the `Available-Dictionary` value for `dictionary` — its
    /// SHA-256 hash, the same one [Rfc9842Frame] embeds in a `dcz` header.
    ///
    /// @param dictionary the dictionary content to hash
    /// @return the resulting header value
    public static AvailableDictionary of(ZstdDictionary dictionary) {
        Objects.requireNonNull(dictionary, "dictionary");
        try {
            return new AvailableDictionary(MessageDigest.getInstance("SHA-256").digest(dictionary.toByteArray()));
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
    public static AvailableDictionary parse(String headerValue) {
        Objects.requireNonNull(headerValue, "headerValue");
        Sfv.Cursor c = Sfv.cursor(headerValue.strip());
        byte[] bytes = Sfv.parseByteSequence(c);
        if (!c.isEmpty()) {
            throw new Rfc9842Exception("malformed Available-Dictionary header: trailing data after the byte sequence");
        }
        try {
            return new AvailableDictionary(bytes);
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

    /// Value equality over the hash bytes rather than array identity (the record default).
    ///
    /// @param o the object to compare with
    /// @return `true` if `o` is an [AvailableDictionary] with an equal hash
    @Override
    public boolean equals(Object o) {
        return o instanceof AvailableDictionary(byte[] otherHash) && Arrays.equals(hash, otherHash);
    }

    /// Hash code consistent with [#equals(Object)], derived from the hash bytes.
    ///
    /// @return the content-based hash code
    @Override
    public int hashCode() {
        return Arrays.hashCode(hash);
    }

    /// Short description carrying the hash's base64 form rather than the array's identity hash.
    ///
    /// @return a string with the base64-encoded hash
    @Override
    @SuppressWarnings("NullableProblems") // toString never returns null; we just don't pull in JB @NotNull
    public String toString() {
        return "AvailableDictionary[hash=" + toHeaderValue() + "]";
    }
}
