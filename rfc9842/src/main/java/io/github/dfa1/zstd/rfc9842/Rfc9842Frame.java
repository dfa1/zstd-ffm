package io.github.dfa1.zstd.rfc9842;

import io.github.dfa1.zstd.ZstdDictionary;
import io.github.dfa1.zstd.ZstdException;
import io.github.dfa1.zstd.ZstdFrame;
import io.github.dfa1.zstd.ZstdMagicVariant;
import io.github.dfa1.zstd.ZstdSkippableContent;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/// RFC 9842 §5 `dcz` (Dictionary-Compressed Zstandard) framing: wraps a zstd
/// frame compressed against a dictionary with a fixed 40-byte header
/// identifying that dictionary by its SHA-256 hash, so a decoder can
/// self-verify the right dictionary is in hand before decompressing —
/// independent of whatever HTTP headers negotiated it, and still correct when
/// a cached response is replayed over a connection that never saw them.
///
/// This is layer 1 only, the wire format: no HTTP dependency, no
/// `Use-As-Dictionary`/`Available-Dictionary`/`Dictionary-ID` header parsing,
/// no dictionary storage/cache lifecycle. [#wrap] and [#unwrap] only add or
/// strip the header — the actual compression stays with the existing `zstd`
/// APIs, so this composes equally well with the per-call dictionary path
/// (`ZstdCompressContext#compress(byte[], ZstdDictionary)`) or a pre-digested
/// `ZstdCompressDictionary`/`ZstdDecompressDictionary` for the hot path.
///
/// {@snippet :
/// byte[] frame = cctx.compress(payload, dict);   // any existing dictionary path
/// byte[] dcz = Rfc9842Frame.wrap(frame, dict);    // add the RFC 9842 header
///
/// byte[] frame2 = Rfc9842Frame.unwrap(dcz, dict);        // verify + strip the header
/// byte[] payload2 = dctx.decompress(frame2, maxSize, dict);
/// }
public final class Rfc9842Frame {

    /// SHA-256 digest length, in bytes — also the `dcz` header's content length.
    private static final int HASH_LENGTH = 32;

    /// Skippable-frame header size: 4-byte magic + 4-byte little-endian content length.
    private static final int SKIPPABLE_HEADER_SIZE = 8;

    /// Total `dcz` header size: the skippable-frame header plus the SHA-256 hash.
    private static final int HEADER_SIZE = SKIPPABLE_HEADER_SIZE + HASH_LENGTH;

    /// The skippable-frame magic variant RFC 9842 fixes for `dcz` headers
    /// (magic number `0x184D2A5E`) — not user-selectable.
    private static final ZstdMagicVariant MAGIC_VARIANT = new ZstdMagicVariant(14);

    /// Wraps `compressedFrame` — a zstd frame already compressed against
    /// `dictionary` — with the RFC 9842 `dcz` header.
    ///
    /// @param compressedFrame a zstd frame compressed against `dictionary`
    /// @param dictionary      the dictionary `compressedFrame` was compressed against
    /// @return the `dcz` header followed by `compressedFrame`
    public static byte[] wrap(byte[] compressedFrame, ZstdDictionary dictionary) {
        Objects.requireNonNull(compressedFrame, "compressedFrame");
        Objects.requireNonNull(dictionary, "dictionary");
        byte[] header = ZstdFrame.writeSkippableFrame(sha256(dictionary), MAGIC_VARIANT);
        byte[] result = new byte[header.length + compressedFrame.length];
        System.arraycopy(header, 0, result, 0, header.length);
        System.arraycopy(compressedFrame, 0, result, header.length, compressedFrame.length);
        return result;
    }

    /// Verifies and strips the RFC 9842 `dcz` header from `dcz`, returning the
    /// zstd frame underneath — still compressed, ready for the existing
    /// decompress APIs.
    ///
    /// @param dcz        a `dcz`-framed blob produced by [#wrap(byte[], ZstdDictionary)]
    /// @param dictionary the dictionary to verify `dcz` against before decompressing
    /// @return the compressed zstd frame, with the header stripped
    /// @throws Rfc9842Exception if `dcz` does not carry a `dcz` header, or its
    ///                           hash does not match `dictionary`
    public static byte[] unwrap(byte[] dcz, ZstdDictionary dictionary) {
        Objects.requireNonNull(dcz, "dcz");
        Objects.requireNonNull(dictionary, "dictionary");
        ZstdSkippableContent header;
        try {
            header = ZstdFrame.readSkippableFrame(dcz);
        } catch (ZstdException e) {
            throw new Rfc9842Exception("not a dcz frame: missing or invalid skippable header", e);
        }
        if (!MAGIC_VARIANT.equals(header.magicVariant())) {
            throw new Rfc9842Exception("not a dcz frame: expected magic variant "
                    + MAGIC_VARIANT.value() + ", got " + header.magicVariant().value());
        }
        byte[] hash = header.content();
        if (hash.length != HASH_LENGTH) {
            throw new Rfc9842Exception("malformed dcz header: expected a " + HASH_LENGTH
                    + "-byte hash, got " + hash.length);
        }
        if (!Arrays.equals(hash, sha256(dictionary))) {
            throw new Rfc9842Exception("dcz hash does not match the given dictionary");
        }
        return Arrays.copyOfRange(dcz, HEADER_SIZE, dcz.length);
    }

    private static byte[] sha256(ZstdDictionary dictionary) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(dictionary.toByteArray());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory algorithm every JDK implementation must support
            // (Java Cryptography Architecture Standard Algorithm Names).
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private Rfc9842Frame() {
        // no instances
    }
}
