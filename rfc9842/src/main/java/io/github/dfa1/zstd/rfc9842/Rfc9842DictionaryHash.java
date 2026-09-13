package io.github.dfa1.zstd.rfc9842;

import io.github.dfa1.zstd.ZstdDictionary;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/// A dictionary's SHA-256 hash, precomputed once and cached — the value
/// [Rfc9842Frame#wrap(byte[], Rfc9842DictionaryHash)] embeds in, and
/// [Rfc9842Frame#unwrap(byte[], Rfc9842DictionaryHash)] verifies against, a
/// `dcz` header, without re-hashing the dictionary on every call.
///
/// Computing this once and reusing it matters for exactly the reason
/// [io.github.dfa1.zstd.ZstdCompressDictionary] exists: hashing a dictionary
/// is cheap for a single call, but redoing it on every `wrap`/`unwrap` in a
/// hot path — once per HTTP request, say — is pure waste once the dictionary
/// itself is fixed. This is intentionally its own type rather than reusing
/// [AvailableDictionary] (the same 32 bytes, coincidentally): `Rfc9842Frame`
/// is the wire-format layer and has no HTTP dependency, while
/// `AvailableDictionary` models an HTTP header value specifically.
///
/// {@snippet :
/// Rfc9842DictionaryHash hash = Rfc9842DictionaryHash.of(dictionary); // once, at startup
/// // ... per request:
/// byte[] dcz = Rfc9842Frame.wrap(frame, hash);
/// }
///
/// @param bytes the SHA-256 hash, exactly 32 bytes
public record Rfc9842DictionaryHash(byte[] bytes) {

    private static final int HASH_LENGTH = 32;

    /// Validates `bytes` is exactly a SHA-256-length digest and defensively
    /// copies it so this record owns its bytes.
    public Rfc9842DictionaryHash {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length != HASH_LENGTH) {
            throw new IllegalArgumentException(
                    "hash must be " + HASH_LENGTH + " bytes (SHA-256), was " + bytes.length);
        }
        bytes = bytes.clone();
    }

    /// The hash bytes, as a fresh copy so the record stays immutable.
    ///
    /// @return a copy of the SHA-256 hash
    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    /// Computes and caches `dictionary`'s SHA-256 hash.
    ///
    /// @param dictionary the dictionary to hash
    /// @return the cached hash
    public static Rfc9842DictionaryHash of(ZstdDictionary dictionary) {
        Objects.requireNonNull(dictionary, "dictionary");
        try {
            return new Rfc9842DictionaryHash(MessageDigest.getInstance("SHA-256").digest(dictionary.toByteArray()));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory algorithm every JDK implementation must support
            // (Java Cryptography Architecture Standard Algorithm Names).
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /// Internal: direct view of the hash bytes for `Rfc9842Frame`'s hot path. Not exposed.
    byte[] raw() {
        return bytes;
    }

    /// Value equality over the hash bytes rather than array identity (the record default).
    ///
    /// @param o the object to compare with
    /// @return `true` if `o` is an [Rfc9842DictionaryHash] with an equal hash
    @Override
    public boolean equals(Object o) {
        return o instanceof Rfc9842DictionaryHash(byte[] otherBytes) && Arrays.equals(bytes, otherBytes);
    }

    /// Hash code consistent with [#equals(Object)], derived from the hash bytes.
    ///
    /// @return the content-based hash code
    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
