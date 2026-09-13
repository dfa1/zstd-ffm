package io.github.dfa1.zstd.rfc9842;

import io.github.dfa1.zstd.ZstdDictionary;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.security.DigestException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/// RFC 9842 §5 `dcz` (Dictionary-Compressed Zstandard) framing: wraps a zstd
/// frame compressed against a dictionary with a fixed 40-byte header
/// identifying that dictionary by its SHA-256 hash, so a decoder can
/// self-verify the right dictionary is in hand before decompressing —
/// independent of whatever HTTP headers negotiated it, and still correct when
/// a cached response is replayed over a connection that never saw them.
///
/// This is layer 1 only, the wire format: no HTTP dependency, no
/// `Use-As-Dictionary`/`Available-Dictionary`/`Dictionary-ID` header parsing,
/// no dictionary storage/cache lifecycle. `wrap`/`unwrap` only add or strip
/// the header — the actual compression stays with the existing `zstd` APIs,
/// so this composes equally well with the per-call dictionary path
/// (`ZstdCompressContext#compress(byte[], ZstdDictionary)`) or a pre-digested
/// `ZstdCompressDictionary`/`ZstdDecompressDictionary` for the hot path.
///
/// Both a `byte[]` and a zero-copy [MemorySegment] form are provided, and
/// neither is built on top of the other: the header logic here is pure Java
/// arithmetic and copies, with no native call for either shape, so routing a
/// `byte[]` caller through a native segment (or vice versa) would only add a
/// pointless round trip. [#unwrap(MemorySegment, ZstdDictionary)] is
/// genuinely zero-allocation — it returns a slice of its input, not a copy.
/// Unlike most `MemorySegment` overloads elsewhere in this library, the ones
/// here do not require a native segment: they never reach native code, so a
/// heap-backed segment (e.g. [MemorySegment#ofArray(byte[])]) works too.
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

    /// Total size of a `dcz` header: the skippable-frame header plus the SHA-256 hash.
    ///
    /// @see #wrap(MemorySegment, MemorySegment, ZstdDictionary)
    public static final int HEADER_SIZE = SKIPPABLE_HEADER_SIZE + HASH_LENGTH;

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

    /// The wire format is little-endian regardless of host order (see
    /// [#SKIPPABLE_PREFIX]) — every currently supported host happens to be
    /// little-endian too, but this reads/writes the standard explicitly
    /// rather than relying on that coincidence.
    private static final ValueLayout.OfInt LITTLE_ENDIAN_INT =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    // ---- byte[]: heap callers ----

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

    // ---- MemorySegment: zero-copy ----

    /// Wraps `compressedFrame` into `dst`, the zero-copy counterpart of
    /// [#wrap(byte[], ZstdDictionary)].
    ///
    /// @param dst             the destination segment, at least
    ///                        [#HEADER_SIZE]` + compressedFrame.byteSize()` bytes
    /// @param compressedFrame a zstd frame compressed against `dictionary`
    /// @param dictionary      the dictionary `compressedFrame` was compressed against
    /// @return the number of bytes written to `dst`
    ///         (always `HEADER_SIZE + compressedFrame.byteSize()`)
    public static long wrap(MemorySegment dst, MemorySegment compressedFrame, ZstdDictionary dictionary) {
        Objects.requireNonNull(dst, "dst");
        Objects.requireNonNull(compressedFrame, "compressedFrame");
        Objects.requireNonNull(dictionary, "dictionary");
        MemorySegment.copy(SKIPPABLE_PREFIX, 0, dst, JAVA_BYTE, 0, SKIPPABLE_HEADER_SIZE);
        byte[] hash = sha256(dictionary);
        MemorySegment.copy(hash, 0, dst, JAVA_BYTE, SKIPPABLE_HEADER_SIZE, HASH_LENGTH);
        MemorySegment.copy(compressedFrame, 0, dst, HEADER_SIZE, compressedFrame.byteSize());
        return HEADER_SIZE + compressedFrame.byteSize();
    }

    /// Wraps `compressedFrame`, allocating the result in `arena`.
    ///
    /// @param arena           the arena to allocate the result in
    /// @param compressedFrame a zstd frame compressed against `dictionary`
    /// @param dictionary      the dictionary `compressedFrame` was compressed against
    /// @return the `dcz` header followed by `compressedFrame`, an arena-owned segment
    public static MemorySegment wrap(Arena arena, MemorySegment compressedFrame, ZstdDictionary dictionary) {
        Objects.requireNonNull(arena, "arena");
        Objects.requireNonNull(compressedFrame, "compressedFrame");
        MemorySegment dst = arena.allocate(HEADER_SIZE + compressedFrame.byteSize());
        wrap(dst, compressedFrame, dictionary);
        return dst;
    }

    /// Verifies the RFC 9842 `dcz` header in `dcz` and returns a slice of
    /// `dcz` with it stripped — the zero-copy counterpart of
    /// [#unwrap(byte[], ZstdDictionary)]. No bytes are copied: the returned
    /// segment is a view of `dcz`, valid for exactly as long as `dcz` is.
    ///
    /// @param dcz        a `dcz`-framed segment produced by
    ///                    [#wrap(MemorySegment, MemorySegment, ZstdDictionary)]
    /// @param dictionary the dictionary to verify `dcz` against before decompressing
    /// @return a slice of `dcz`: the compressed zstd frame, with the header stripped
    /// @throws Rfc9842Exception if `dcz` does not carry a `dcz` header, or its
    ///                           hash does not match `dictionary`
    public static MemorySegment unwrap(MemorySegment dcz, ZstdDictionary dictionary) {
        Objects.requireNonNull(dcz, "dcz");
        Objects.requireNonNull(dictionary, "dictionary");
        if (dcz.byteSize() < SKIPPABLE_HEADER_SIZE) {
            throw new Rfc9842Exception("not a dcz frame: shorter than the skippable-frame header");
        }
        int magic = dcz.get(LITTLE_ENDIAN_INT, 0);
        int variant = magic - SKIPPABLE_MAGIC_BASE;
        if (variant != MAGIC_VARIANT) {
            throw new Rfc9842Exception(
                    "not a dcz frame: expected magic variant " + MAGIC_VARIANT + ", got " + variant);
        }
        int contentLength = dcz.get(LITTLE_ENDIAN_INT, 4);
        if (contentLength != HASH_LENGTH) {
            throw new Rfc9842Exception(
                    "malformed dcz header: expected a " + HASH_LENGTH + "-byte hash, got " + contentLength);
        }
        if (dcz.byteSize() < HEADER_SIZE) {
            throw new Rfc9842Exception("malformed dcz header: declares a " + HASH_LENGTH
                    + "-byte hash but the input is too short to contain it");
        }
        MemorySegment hash = MemorySegment.ofArray(sha256(dictionary));
        if (MemorySegment.mismatch(hash, 0, HASH_LENGTH, dcz, SKIPPABLE_HEADER_SIZE, HEADER_SIZE) != -1) {
            throw new Rfc9842Exception("dcz hash does not match the given dictionary");
        }
        return dcz.asSlice(HEADER_SIZE, dcz.byteSize() - HEADER_SIZE);
    }

    // ---- shared ----

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
