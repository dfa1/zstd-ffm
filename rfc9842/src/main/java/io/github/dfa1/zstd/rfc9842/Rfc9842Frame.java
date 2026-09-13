package io.github.dfa1.zstd.rfc9842;

import io.github.dfa1.zstd.ZstdDictionary;

import java.security.DigestException;
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
/// The header bytes are written and read directly here rather than through
/// `ZstdFrame`'s general skippable-frame API: RFC 9842 fixes both the magic
/// variant and the content length (a SHA-256 digest, always 32 bytes)
/// permanently, so there is nothing left for zstd's native skippable-frame
/// code to compute — [#wrap] and [#unwrap] allocate exactly one array each
/// (the actual output) with no native round trip for the framing itself.
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

    /// `ZSTD_MAGIC_SKIPPABLE_START`: the base skippable magic number that
    /// [#MAGIC_VARIANT] is added to.
    private static final int SKIPPABLE_MAGIC_BASE = 0x184D2A50;

    /// The skippable-frame magic variant RFC 9842 fixes for `dcz` headers —
    /// not user-selectable. Skippable magic numbers run `0x184D2A50` (variant
    /// 0) to `0x184D2A5F` (variant 15); RFC 9842 fixes variant 14, i.e. magic
    /// `0x184D2A5E`.
    private static final int MAGIC_VARIANT = 14;

    /// The fixed 8-byte skippable-frame prefix every `dcz` header starts
    /// with: magic `0x184D2A5E` and content length 32, both little-endian.
    private static final byte[] SKIPPABLE_PREFIX = {0x5e, 0x2a, 0x4d, 0x18, 0x20, 0x00, 0x00, 0x00};

    /// Wraps `compressedFrame` — a zstd frame already compressed against
    /// `dictionary` — with the RFC 9842 `dcz` header.
    ///
    /// @param compressedFrame a zstd frame compressed against `dictionary`
    /// @param dictionary      the dictionary `compressedFrame` was compressed against
    /// @return the `dcz` header followed by `compressedFrame`
    public static byte[] wrap(byte[] compressedFrame, ZstdDictionary dictionary) {
        Objects.requireNonNull(compressedFrame, "compressedFrame");
        Objects.requireNonNull(dictionary, "dictionary");
        byte[] result = new byte[HEADER_SIZE + compressedFrame.length];
        System.arraycopy(SKIPPABLE_PREFIX, 0, result, 0, SKIPPABLE_HEADER_SIZE);
        hashInto(dictionary, result, SKIPPABLE_HEADER_SIZE);
        System.arraycopy(compressedFrame, 0, result, HEADER_SIZE, compressedFrame.length);
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
        if (dcz.length < SKIPPABLE_HEADER_SIZE) {
            throw new Rfc9842Exception("not a dcz frame: shorter than the skippable-frame header");
        }
        int magic = readLittleEndianInt(dcz, 0);
        int variant = magic - SKIPPABLE_MAGIC_BASE;
        if (variant != MAGIC_VARIANT) {
            throw new Rfc9842Exception(
                    "not a dcz frame: expected magic variant " + MAGIC_VARIANT + ", got " + variant);
        }
        int contentLength = readLittleEndianInt(dcz, 4);
        if (contentLength != HASH_LENGTH) {
            throw new Rfc9842Exception(
                    "malformed dcz header: expected a " + HASH_LENGTH + "-byte hash, got " + contentLength);
        }
        if (dcz.length < HEADER_SIZE) {
            throw new Rfc9842Exception("malformed dcz header: declares a " + HASH_LENGTH
                    + "-byte hash but the input is too short to contain it");
        }
        byte[] hash = sha256(dictionary);
        if (!Arrays.equals(hash, 0, HASH_LENGTH, dcz, SKIPPABLE_HEADER_SIZE, HEADER_SIZE)) {
            throw new Rfc9842Exception("dcz hash does not match the given dictionary");
        }
        return Arrays.copyOfRange(dcz, HEADER_SIZE, dcz.length);
    }

    private static int readLittleEndianInt(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | (data[offset + 1] & 0xFF) << 8
                | (data[offset + 2] & 0xFF) << 16
                | (data[offset + 3] & 0xFF) << 24;
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

    /// Digests `dictionary` directly into `dst` at `offset`, avoiding the
    /// separate 32-byte array [#sha256(ZstdDictionary)] would otherwise
    /// allocate just to be copied into `dst` immediately after.
    private static void hashInto(ZstdDictionary dictionary, byte[] dst, int offset) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(dictionary.toByteArray());
            digest.digest(dst, offset, HASH_LENGTH);
        } catch (NoSuchAlgorithmException | DigestException e) {
            // NoSuchAlgorithmException: SHA-256 is a mandatory JDK algorithm (see #sha256).
            // DigestException: only thrown if dst has less than HASH_LENGTH bytes
            // remaining at offset, which callers of this private method never pass.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private Rfc9842Frame() {
        // no instances
    }
}
