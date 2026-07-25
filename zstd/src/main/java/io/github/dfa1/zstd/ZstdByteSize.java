package io.github.dfa1.zstd;

import java.util.Optional;

/// A validated, non-negative byte size or count, replacing a naked `int`/`long`
/// at every public API boundary that takes one.
///
/// Backed by `long` uniformly: some sizes here are native-segment or streaming
/// totals that can exceed [Integer#MAX_VALUE] (e.g.
/// [ZstdOutputStream#withPledgedSize(java.io.OutputStream, ZstdCompressionLevel, ZstdByteSize)]),
/// while others end up sizing a `byte[]` and so must additionally fit an `int`
/// (e.g. [Zstd#decompress(byte[], ZstdByteSize)]). The latter narrow with
/// [#toArraySize()] rather than silently truncating.
///
/// @param value the byte size or count, non-negative
public record ZstdByteSize(long value) {

    // zstd's own sentinels for "not stored" / "error" on a frame-content-size
    // read (ZSTD_CONTENTSIZE_UNKNOWN / ZSTD_CONTENTSIZE_ERROR) — an implementation
    // detail of interpreting those native results, not part of the public type.
    private static final long CONTENTSIZE_UNKNOWN = -1L;
    private static final long CONTENTSIZE_ERROR = -2L;

    /// Validates `value` is non-negative.
    public ZstdByteSize {
        if (value < 0) {
            throw new IllegalArgumentException("size " + value + " must not be negative");
        }
    }

    /// Narrows this size to an `int`, for callers that must size a `byte[]`.
    ///
    /// @return this size as an `int`
    /// @throws ZstdException if the size exceeds the maximum array length
    public int toArraySize() {
        try {
            return Math.toIntExact(value);
        } catch (ArithmeticException _) {
            throw new ZstdException("size " + value + " exceeds the maximum array length");
        }
    }

    /// `value` KiB (1024 bytes each) — e.g. `ZstdByteSize.ofKiB(8)` for an 8 KiB
    /// buffer, clearer than spelling out `new ZstdByteSize(8 * 1024)`.
    ///
    /// @param value the count of KiB
    /// @return a size of `value * 1024` bytes
    /// @throws IllegalArgumentException if `value` is negative
    /// @throws ArithmeticException      if `value * 1024` overflows a `long`
    public static ZstdByteSize ofKiB(long value) {
        return new ZstdByteSize(Math.multiplyExact(value, 1024L));
    }

    /// `value` MiB (1024 * 1024 bytes each).
    ///
    /// @param value the count of MiB
    /// @return a size of `value * 1024 * 1024` bytes
    /// @throws IllegalArgumentException if `value` is negative
    /// @throws ArithmeticException      if `value * 1024 * 1024` overflows a `long`
    public static ZstdByteSize ofMiB(long value) {
        return new ZstdByteSize(Math.multiplyExact(value, 1024L * 1024L));
    }

    /// Wraps a `ZSTD_getFrameContentSize` / `ZSTD_findDecompressedSize` /
    /// `ZSTD_decompressBound` result, turning zstd's negative sentinels — and any
    /// other negative reading, which can only mean the field's true value (zstd
    /// reads it as `unsigned long long`) landed at or above `2^63` and wrapped
    /// around a signed `long` — into a [ZstdException] instead of a bogus
    /// negative size or the wrong exception type.
    ///
    /// @param raw a content size, or a zstd "not stored" / "error" sentinel
    /// @return `raw` wrapped as a [ZstdByteSize] when it is a real length
    /// @throws ZstdException if the size is not stored, or the input is not valid zstd data
    static ZstdByteSize fromFrameContentSize(long raw) {
        if (raw == CONTENTSIZE_UNKNOWN) {
            throw new ZstdException("decompressed size not stored in frame");
        }
        if (raw == CONTENTSIZE_ERROR) {
            throw new ZstdException("not a valid zstd frame");
        }
        if (raw < 0) {
            throw new ZstdException("decompressed size " + raw + " is not a valid zstd frame content size");
        }
        return new ZstdByteSize(raw);
    }

    /// Wraps a `ZSTD_getFrameHeader`-parsed content size, returning empty instead
    /// of throwing when the frame does not record a usable one — unlike
    /// [#fromFrameContentSize(long)], a [ZstdFrameHeader] was already validated
    /// by the time this runs, so nothing here is an error.
    ///
    /// Any negative reading maps to empty, not just the "not stored" sentinel:
    /// `ZSTD_getFrameHeader` copies the 8-byte Frame_Content_Size field verbatim
    /// without validating its magnitude, so a hostile header can declare an
    /// unsigned value at or above `2^63` that wraps negative in a signed `long` —
    /// no such size is representable in any buffer this library can produce, so
    /// "absent" is the honest answer rather than an exception.
    ///
    /// @param raw a content size, the "not stored" sentinel, or an unrepresentable
    ///            declared value read as a negative `long`
    /// @return the wrapped size, or empty if the frame does not store a usable one
    static Optional<ZstdByteSize> fromFrameHeaderContentSize(long raw) {
        return raw < 0 ? Optional.empty() : Optional.of(new ZstdByteSize(raw));
    }
}
