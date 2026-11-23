package io.github.dfa1.zstd;

import java.util.Objects;
import java.util.Optional;

/// Parsed contents of a zstd frame header, from `ZSTD_getFrameHeader`.
///
/// `contentSize` and `windowSize` are [Optional] rather than a plain
/// [ZstdByteSize]: a hostile header can declare either as an unsigned value at or
/// above `2^63` (zstd copies the content-size field verbatim, and for a
/// single-segment frame sets `windowSize` equal to it), which is not
/// representable as a non-negative size — so it maps to empty rather than
/// throwing while the header is parsed.
public final class ZstdFrameHeader {

    /// `null` when the frame does not record a representable content size.
    private final ZstdByteSize contentSize;
    /// `null` when the frame declares an unrepresentable window size.
    private final ZstdByteSize windowSize;
    private final ZstdByteSize blockSizeMax;
    private final ZstdFrameType frameType;
    private final ZstdByteSize headerSize;
    private final ZstdDictionaryId dictId;
    private final boolean hasChecksum;

    private ZstdFrameHeader(
            ZstdByteSize contentSize,
            ZstdByteSize windowSize,
            ZstdByteSize blockSizeMax,
            ZstdFrameType frameType,
            ZstdByteSize headerSize,
            ZstdDictionaryId dictId,
            boolean hasChecksum) {
        this.contentSize = contentSize;
        this.windowSize = windowSize;
        this.blockSizeMax = blockSizeMax;
        this.frameType = frameType;
        this.headerSize = headerSize;
        this.dictId = dictId;
        this.hasChecksum = hasChecksum;
    }

    /// Creates a [ZstdFrameHeader] from its components; only the library itself builds these.
    ///
    /// @param contentSize  the stored decompressed size, or `null` if the frame does not record
    ///                     a representable one
    /// @param windowSize   the back-reference window size needed to decode the frame, or `null`
    ///                     if the frame declares an unrepresentable one
    /// @param blockSizeMax the maximum block size used in the frame
    /// @param frameType    whether this is a standard or skippable frame
    /// @param headerSize   the size of the frame header in bytes
    /// @param dictId       the dictionary id, or [ZstdDictionaryId#NONE] if none (for a
    ///                     skippable frame, [ZstdDictionaryId#raw()] is the magic variant
    ///                     0..15)
    /// @param hasChecksum  whether the frame descriptor sets the content-checksum flag — a
    ///                     4-byte XXH64 checksum of the decompressed content then trails the
    ///                     frame, which the decoder verifies on decompress. Only this flag is
    ///                     header data; the checksum value itself lives in the frame trailer
    ///                     and is not exposed here. Gate on it to require integrity before
    ///                     decompressing
    /// @return the new [ZstdFrameHeader]
    static ZstdFrameHeader of(
            ZstdByteSize contentSize,
            ZstdByteSize windowSize,
            ZstdByteSize blockSizeMax,
            ZstdFrameType frameType,
            ZstdByteSize headerSize,
            ZstdDictionaryId dictId,
            boolean hasChecksum) {
        return new ZstdFrameHeader(contentSize, windowSize, blockSizeMax, frameType, headerSize, dictId, hasChecksum);
    }

    /// The stored decompressed size, or empty if the frame does not record a representable one.
    ///
    /// @return the `contentSize` component
    public Optional<ZstdByteSize> contentSize() {
        return Optional.ofNullable(contentSize);
    }

    /// The back-reference window size needed to decode the frame, or empty if the frame
    /// declares an unrepresentable one.
    ///
    /// @return the `windowSize` component
    public Optional<ZstdByteSize> windowSize() {
        return Optional.ofNullable(windowSize);
    }

    /// The maximum block size used in the frame.
    ///
    /// @return the maximum block size used in the frame
    public ZstdByteSize blockSizeMax() {
        return blockSizeMax;
    }

    /// Whether this is a standard or skippable frame.
    ///
    /// @return whether this is a standard or skippable frame
    public ZstdFrameType frameType() {
        return frameType;
    }

    /// The size of the frame header in bytes.
    ///
    /// @return the size of the frame header in bytes
    public ZstdByteSize headerSize() {
        return headerSize;
    }

    /// The dictionary id, or [ZstdDictionaryId#NONE] if none (for a skippable frame,
    /// [ZstdDictionaryId#raw()] is the magic variant 0..15).
    ///
    /// @return the `dictId` component
    public ZstdDictionaryId dictId() {
        return dictId;
    }

    /// Whether the frame descriptor sets the content-checksum flag — a 4-byte XXH64 checksum of
    /// the decompressed content then trails the frame, which the decoder verifies on
    /// decompress. Only this flag is header data; the checksum value itself lives in the frame
    /// trailer and is not exposed here. Gate on it to require integrity before decompressing.
    ///
    /// @return the `hasChecksum` component
    public boolean hasChecksum() {
        return hasChecksum;
    }

    /// Value equality over all components.
    ///
    /// @param o the object to compare with
    /// @return `true` if `o` is a [ZstdFrameHeader] with equal components
    @Override
    public boolean equals(Object o) {
        return o instanceof ZstdFrameHeader other
                && Objects.equals(contentSize, other.contentSize)
                && Objects.equals(windowSize, other.windowSize)
                && blockSizeMax.equals(other.blockSizeMax)
                && frameType.equals(other.frameType)
                && headerSize.equals(other.headerSize)
                && dictId.equals(other.dictId)
                && hasChecksum == other.hasChecksum;
    }

    /// Hash code consistent with [#equals(Object)].
    ///
    /// @return the component-based hash code
    @Override
    public int hashCode() {
        return Objects.hash(contentSize, windowSize, blockSizeMax, frameType, headerSize, dictId, hasChecksum);
    }

    /// Record-style description listing every component.
    ///
    /// @return `ZstdFrameHeader[component=value, ...]`
    @Override
    public String toString() {
        return "ZstdFrameHeader[contentSize=" + contentSize() + ", windowSize=" + windowSize() + ", blockSizeMax=" + blockSizeMax + ", frameType=" + frameType + ", headerSize=" + headerSize + ", dictId=" + dictId + ", hasChecksum=" + hasChecksum + "]";
    }
}
