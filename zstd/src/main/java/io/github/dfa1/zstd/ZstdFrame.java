package io.github.dfa1.zstd;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Objects;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/// Inspection of zstd frames without decompressing them — useful for parsing a
/// stream of concatenated frames, sizing buffers, or routing by dictionary id.
///
/// Each method has a `byte[]` form and a zero-copy [MemorySegment] form for
/// data already off-heap (e.g. an mmap slice); see `docs/zero-copy.md`.
public final class ZstdFrame {

    /// Tests whether `data` begins with a valid zstd frame (standard or skippable).
    ///
    /// @param data the bytes to inspect
    /// @return `true` if `data` starts with a zstd frame
    public static boolean isZstdFrame(byte[] data) {
        try (Arena arena = Arena.ofConfined()) {
            return isZstdFrame(Zstd.copyIn(arena, data), data.length);
        }
    }

    /// Tests whether the native `data` begins with a valid zstd frame.
    ///
    /// @param data the native bytes to inspect
    /// @return `true` if `data` starts with a zstd frame
    public static boolean isZstdFrame(MemorySegment data) {
        return isZstdFrame(data, data.byteSize());
    }

    /// Compressed size of the first frame in `data`, including its header and
    /// epilogue — i.e. where the next frame would begin.
    ///
    /// @param data one or more concatenated zstd frames
    /// @return the byte length of the first frame
    /// @throws ZstdException if the input is not a valid frame
    public static ZstdByteSize compressedSize(byte[] data) {
        try (Arena arena = Arena.ofConfined()) {
            return new ZstdByteSize(compressedSize(Zstd.copyIn(arena, data), data.length));
        }
    }

    /// Compressed size of the first frame in native `data`.
    ///
    /// @param data one or more concatenated zstd frames
    /// @return the byte length of the first frame
    /// @throws ZstdException if the input is not a valid frame
    public static ZstdByteSize compressedSize(MemorySegment data) {
        return new ZstdByteSize(compressedSize(data, data.byteSize()));
    }

    /// Upper bound on the total decompressed size of all frames in `data`,
    /// large enough to never overflow during decompression.
    ///
    /// @param data one or more concatenated zstd frames
    /// @return an upper bound on the combined decompressed size
    /// @throws ZstdException if the input is not valid zstd data
    public static ZstdByteSize decompressedBound(byte[] data) {
        try (Arena arena = Arena.ofConfined()) {
            return decompressedBound(Zstd.copyIn(arena, data), data.length);
        }
    }

    /// Upper bound on the total decompressed size of all frames in native `data`.
    ///
    /// @param data one or more concatenated zstd frames
    /// @return an upper bound on the combined decompressed size
    /// @throws ZstdException if the input is not valid zstd data
    public static ZstdByteSize decompressedBound(MemorySegment data) {
        return decompressedBound(data, data.byteSize());
    }

    /// Exact total decompressed size of all frames in `data`, summed from each
    /// frame header.
    ///
    /// Unlike [#decompressedBound(byte[])], which over-estimates so a buffer never
    /// overflows, this is the precise combined size — but only when every frame
    /// records it. Use it to size an exact destination; fall back to the bound when
    /// a frame was produced by a stream that did not pledge its size.
    ///
    /// @param data one or more concatenated zstd frames
    /// @return the exact combined decompressed size
    /// @throws ZstdException if the input is not valid zstd data, or any frame does
    ///                       not record its decompressed size
    public static ZstdByteSize decompressedSize(byte[] data) {
        try (Arena arena = Arena.ofConfined()) {
            return decompressedSize(Zstd.copyIn(arena, data), data.length);
        }
    }

    /// Exact total decompressed size of all frames in native `data`.
    /// Otherwise identical to [#decompressedSize(byte[])].
    ///
    /// @param data one or more concatenated zstd frames
    /// @return the exact combined decompressed size
    /// @throws ZstdException if the input is not valid zstd data, or any frame does
    ///                       not record its decompressed size
    public static ZstdByteSize decompressedSize(MemorySegment data) {
        return decompressedSize(data, data.byteSize());
    }

    /// Extra room needed for **in-place** decompression of `data`, where the input
    /// and output buffers overlap.
    ///
    /// zstd can decompress into a buffer that also holds the compressed input, with
    /// no second allocation: make the output buffer at least
    /// `decompressedSize + margin` bytes and place the compressed frame(s) at its
    /// **end**, then decompress from that tail slice into the start. This returns
    /// that `margin`. Supports multi-frame input.
    ///
    /// @param data one or more concatenated zstd frames
    /// @return the in-place decompression margin in bytes
    /// @throws ZstdException if the input is not valid zstd data
    public static ZstdByteSize decompressionMargin(byte[] data) {
        try (Arena arena = Arena.ofConfined()) {
            return new ZstdByteSize(decompressionMargin(Zstd.copyIn(arena, data), data.length));
        }
    }

    /// Extra room needed for in-place decompression of native `data`.
    /// Otherwise identical to [#decompressionMargin(byte[])].
    ///
    /// @param data one or more concatenated zstd frames
    /// @return the in-place decompression margin in bytes
    /// @throws ZstdException if the input is not valid zstd data
    public static ZstdByteSize decompressionMargin(MemorySegment data) {
        return new ZstdByteSize(decompressionMargin(data, data.byteSize()));
    }

    /// Dictionary id recorded in the first frame's header.
    ///
    /// @param data a zstd frame
    /// @return the dictionary id, or [ZstdDictionaryId#NONE] if the frame uses no dictionary
    ///         or does not record one
    public static ZstdDictionaryId dictId(byte[] data) {
        try (Arena arena = Arena.ofConfined()) {
            return dictId(Zstd.copyIn(arena, data), data.length);
        }
    }

    /// Dictionary id recorded in native frame `data`.
    ///
    /// @param data a zstd frame
    /// @return the dictionary id, or [ZstdDictionaryId#NONE] if none
    public static ZstdDictionaryId dictId(MemorySegment data) {
        return dictId(data, data.byteSize());
    }

    /// Size in bytes of the first frame's header, without parsing it.
    ///
    /// A lighter probe than [#header(byte[])]: it reads only the few leading bytes
    /// needed to compute the header length (as few as 5 for a standard frame), so
    /// you can learn how many bytes to buffer before a full [#header(byte[])] parse.
    /// For an already-parsed header the same value is [ZstdFrameHeader#headerSize()].
    ///
    /// @param data the start of a zstd frame, at least its first few bytes
    /// @return the header size in bytes
    /// @throws ZstdException if `data` is too short to determine the header size, or
    ///                       is not a valid zstd frame
    public static ZstdByteSize headerSize(byte[] data) {
        try (Arena arena = Arena.ofConfined()) {
            return new ZstdByteSize(headerSize(Zstd.copyIn(arena, data), data.length));
        }
    }

    /// Size in bytes of the first frame's header in native `data`.
    /// Otherwise identical to [#headerSize(byte[])].
    ///
    /// @param data the start of a zstd frame, at least its first few bytes
    /// @return the header size in bytes
    /// @throws ZstdException if `data` is too short to determine the header size, or
    ///                       is not a valid zstd frame
    public static ZstdByteSize headerSize(MemorySegment data) {
        return new ZstdByteSize(headerSize(data, data.byteSize()));
    }

    /// Parses the header of the first frame in `data` without decompressing it.
    ///
    /// @param data a complete zstd frame (or at least its header)
    /// @return the parsed frame header
    /// @throws ZstdException if the input is not a valid frame or the header is incomplete
    public static ZstdFrameHeader header(byte[] data) {
        try (Arena arena = Arena.ofConfined()) {
            return header(Zstd.copyIn(arena, data), data.length);
        }
    }

    /// Parses the header of the first frame in native `data`.
    ///
    /// @param data a complete zstd frame (or at least its header)
    /// @return the parsed frame header
    /// @throws ZstdException if the input is not a valid frame or the header is incomplete
    public static ZstdFrameHeader header(MemorySegment data) {
        return header(data, data.byteSize());
    }

    /// Tests whether `data` begins with a skippable frame (user data a decoder ignores).
    ///
    /// @param data the bytes to inspect
    /// @return `true` if `data` starts with a skippable frame
    public static boolean isSkippableFrame(byte[] data) {
        try (Arena arena = Arena.ofConfined()) {
            return isSkippableFrame(Zstd.copyIn(arena, data), data.length);
        }
    }

    /// Tests whether native `data` begins with a skippable frame.
    ///
    /// @param data the native bytes to inspect
    /// @return `true` if `data` starts with a skippable frame
    public static boolean isSkippableFrame(MemorySegment data) {
        return isSkippableFrame(data, data.byteSize());
    }

    /// Wraps `content` in a skippable frame — arbitrary metadata that a zstd
    /// decoder skips over, letting you interleave it with compressed frames.
    ///
    /// @param content      the user bytes to embed
    /// @param magicVariant the variant selecting one of the skippable magic numbers
    /// @return the skippable frame bytes
    public static byte[] writeSkippableFrame(byte[] content, ZstdMagicVariant magicVariant) {
        Objects.requireNonNull(magicVariant, "magicVariant");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = Zstd.copyIn(arena, content);
            long cap = content.length + 8L;
            MemorySegment dst = arena.allocate(cap);
            long written = NativeCall.checkReturnValue(() -> (long) Bindings.WRITE_SKIPPABLE_FRAME.invokeExact(
                    dst, cap, src, (long) content.length, magicVariant.value()));
            return Zstd.copyOut(dst, written);
        }
    }

    /// Reads the content of a skippable frame produced by
    /// [#writeSkippableFrame(byte[], ZstdMagicVariant)].
    ///
    /// @param frame a skippable frame
    /// @return the embedded content and its magic variant
    /// @throws ZstdException if `frame` is not a skippable frame
    public static ZstdSkippableContent readSkippableFrame(byte[] frame) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = Zstd.copyIn(arena, frame);
            MemorySegment magic = arena.allocate(JAVA_INT);
            MemorySegment dst = arena.allocate(Math.max(frame.length, 1));
            long written = NativeCall.checkReturnValue(() -> (long) Bindings.READ_SKIPPABLE_FRAME.invokeExact(
                    dst, (long) frame.length, magic, src, (long) frame.length));
            return new ZstdSkippableContent(Zstd.copyOut(dst, written), new ZstdMagicVariant(magic.get(JAVA_INT, 0)));
        }
    }

    private static ZstdFrameHeader header(MemorySegment data, long size) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment zfh = arena.allocate(48);
            long remaining = NativeCall.checkReturnValue(() -> (long) Bindings.GET_FRAME_HEADER.invokeExact(zfh, data, size));
            if (remaining != 0) {
                throw new ZstdException("incomplete frame header: need " + remaining + " more bytes");
            }
            return new ZstdFrameHeader(
                    ZstdByteSize.fromUnsignedFrameHeaderField(zfh.get(JAVA_LONG, 0)), // contentSize
                    ZstdByteSize.fromUnsignedFrameHeaderField(zfh.get(JAVA_LONG, 8)), // windowSize
                    new ZstdByteSize(zfh.get(JAVA_INT, 16) & 0xFFFFFFFFL),            // blockSizeMax
                    ZstdFrameType.of(zfh.get(JAVA_INT, 20)),                          // frameType
                    new ZstdByteSize(zfh.get(JAVA_INT, 24)),                          // headerSize
                    ZstdDictionaryId.of(zfh.get(JAVA_INT, 28)),                       // dictID
                    zfh.get(JAVA_INT, 32) != 0);                                      // checksumFlag
        }
    }

    private static boolean isSkippableFrame(MemorySegment data, long size) {
        try {
            return ((int) Bindings.IS_SKIPPABLE_FRAME.invokeExact(data, size)) != 0;
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    private static boolean isZstdFrame(MemorySegment data, long size) {
        try {
            return ((int) Bindings.IS_FRAME.invokeExact(data, size)) != 0;
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    private static long compressedSize(MemorySegment data, long size) {
        return NativeCall.checkReturnValue(() -> (long) Bindings.FIND_FRAME_COMPRESSED_SIZE.invokeExact(data, size));
    }

    private static ZstdByteSize decompressedBound(MemorySegment data, long size) {
        long bound;
        try {
            bound = (long) Bindings.DECOMPRESS_BOUND.invokeExact(data, size);
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
        // ZSTD_decompressBound never returns CONTENTSIZE_UNKNOWN, only a bound or the error sentinel
        return ZstdByteSize.fromFrameContentSize(bound);
    }

    private static ZstdByteSize decompressedSize(MemorySegment data, long size) {
        long total;
        try {
            total = (long) Bindings.FIND_DECOMPRESSED_SIZE.invokeExact(data, size);
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
        return ZstdByteSize.fromFrameContentSize(total);
    }

    private static long decompressionMargin(MemorySegment data, long size) {
        return NativeCall.checkReturnValue(() -> (long) Bindings.DECOMPRESSION_MARGIN.invokeExact(data, size));
    }

    private static long headerSize(MemorySegment data, long size) {
        return NativeCall.checkReturnValue(() -> (long) Bindings.FRAME_HEADER_SIZE.invokeExact(data, size));
    }

    private static ZstdDictionaryId dictId(MemorySegment data, long size) {
        try {
            return ZstdDictionaryId.of((int) Bindings.GET_DICT_ID_FROM_FRAME.invokeExact(data, size));
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    private ZstdFrame() {
        // no instances
    }
}
