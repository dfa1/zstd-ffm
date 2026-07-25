package io.github.dfa1.zstd;

import java.util.Optional;

/// Parsed contents of a zstd frame header, from `ZSTD_getFrameHeader`.
///
/// `contentSize` and `windowSize` are [Optional] rather than a plain
/// [ZstdByteSize]: a hostile header can declare either as an unsigned value at or
/// above `2^63` (zstd copies the content-size field verbatim, and for a
/// single-segment frame sets `windowSize` equal to it), which is not
/// representable as a non-negative size — so it maps to empty rather than
/// throwing while the header is parsed.
///
/// @param contentSize  the stored decompressed size, or empty if the frame does
///                     not record a representable one
/// @param windowSize   the back-reference window size needed to decode the frame,
///                     or empty if the frame declares an unrepresentable one
/// @param blockSizeMax the maximum block size used in the frame
/// @param frameType    whether this is a standard or skippable frame
/// @param headerSize   the size of the frame header in bytes
/// @param dictId       the dictionary id, or [ZstdDictionaryId#NONE] if none (for a
///                     skippable frame, [ZstdDictionaryId#raw()] is the magic variant 0..15)
/// @param hasChecksum  whether a 4-byte content checksum follows the frame
public record ZstdFrameHeader(
        Optional<ZstdByteSize> contentSize,
        Optional<ZstdByteSize> windowSize,
        ZstdByteSize blockSizeMax,
        ZstdFrameType frameType,
        ZstdByteSize headerSize,
        ZstdDictionaryId dictId,
        boolean hasChecksum) {
}
