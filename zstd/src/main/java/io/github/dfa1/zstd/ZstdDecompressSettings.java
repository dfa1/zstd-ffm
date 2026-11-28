package io.github.dfa1.zstd;

import java.util.Objects;

/// The decompression settings shared by [ZstdDecompressContext],
/// [ZstdDecompressStream] and [ZstdInputStream].
///
/// One setter per zstd advanced parameter (`ZSTD_dParameter`).
///
/// Settings are sticky until changed or cleared by a parameter
/// [ZstdDecompressContext#reset(ZstdResetDirective)]. On a stream, set them
/// before the first read or between frames; zstd rejects a change mid-frame with
/// [ZstdException]. On a closed context or stream a valid setter call throws
/// [IllegalStateException].
///
/// @param <S> the implementing type, returned by every setter for chaining
public sealed interface ZstdDecompressSettings<S extends ZstdDecompressSettings<S>>
        permits ZstdDecompressContext, ZstdDecompressStream, ZstdInputStream {

    /// Sets the largest back-reference window a streaming decoder will allocate
    /// a buffer for, as a power of two: a memory limit against hostile or
    /// oversized frames. A frame declaring a larger window fails with
    /// [ZstdErrorCode#FRAME_PARAMETER_WINDOW_TOO_LARGE]. Raise it to stream
    /// frames built with a [ZstdCompressSettings#windowLog(int)] above the
    /// default limit, `27` (128 MiB); `0` restores that default.
    ///
    /// zstd checks the limit only when it buffers. On [ZstdInputStream] and
    /// [ZstdDecompressStream], a frame that declares its content size and fits in
    /// one output step is decoded in a single shot, needs no window buffer, and
    /// is not checked. On [ZstdDecompressContext] it has **no effect at all**: its
    /// `decompress` methods are all one-shot, decoding straight into a buffer
    /// whose size the caller bounds.
    ///
    /// @param windowLogMax the base-2 log of the maximum accepted window size, or `0` for the default
    /// @return `this`, for chaining
    /// @throws IllegalArgumentException if `windowLogMax` is neither `0` nor within zstd's bounds
    default S windowLogMax(int windowLogMax) {
        return apply(ZstdDecompressParameter.WINDOW_LOG_MAX,
                ZstdDecompressParameter.WINDOW_LOG_MAX.checked(windowLogMax));
    }

    /// Rejects any block whose uncompressed content is larger than
    /// `maxBlockSize`, from 1 KiB to 128 KiB; zero, the default, restores the
    /// 128 KiB maximum. A larger block fails with [ZstdException].
    ///
    /// [ZstdInputStream] and [ZstdDecompressStream] then size their buffers for
    /// the cap instead of 128 KiB, saving memory when many are alive at once.
    /// One-shot [ZstdDecompressContext] decoding enforces the cap too, but saves
    /// nothing.
    ///
    /// **Experimental** in upstream zstd. It rejects otherwise valid frames, so
    /// use it only on frames written with a matching
    /// [ZstdCompressSettings#maxBlockSize(ZstdByteSize)].
    ///
    /// @param maxBlockSize the largest accepted block size, or zero for the default
    /// @return `this`, for chaining
    /// @throws IllegalArgumentException if `maxBlockSize` is neither zero nor within zstd's bounds
    default S maxBlockSize(ZstdByteSize maxBlockSize) {
        Objects.requireNonNull(maxBlockSize, "maxBlockSize");
        return apply(ZstdDecompressParameter.MAX_BLOCK_SIZE,
                ZstdDecompressParameter.MAX_BLOCK_SIZE.checked(maxBlockSize.value()));
    }

    /// Skips validation of each frame's content checksum (written with
    /// [ZstdCompressSettings#checksum(boolean)]). Off by default, so a checksum
    /// mismatch fails with [ZstdErrorCode#CHECKSUM_WRONG].
    ///
    /// Use it when something else already guarantees integrity (TLS, a
    /// filesystem or object-store checksum, a content-addressed key), or to
    /// recover data from a frame whose stored checksum no longer matches.
    ///
    /// **Experimental** in upstream zstd. While on, **corrupted payloads are
    /// no longer detected** by the checksum; other malformed input still fails.
    ///
    /// @param enabled whether to skip checksum validation
    /// @return `this`, for chaining
    default S ignoreChecksum(boolean enabled) {
        return apply(ZstdDecompressParameter.FORCE_IGNORE_CHECKSUM, enabled ? 1 : 0);
    }

    /// Accepts only magicless frames, which omit the 4-byte magic number, as
    /// written with [ZstdCompressSettings#magicless(boolean)]. Off by default;
    /// while on, standard frames are rejected.
    ///
    /// **Experimental** in upstream zstd. The [ZstdFrame] inspection helpers
    /// assume the standard format, so [ZstdDecompressContext]'s overloads that
    /// read the decompressed size from the frame header
    /// ([ZstdDecompressContext#decompress(byte[])] and
    /// [ZstdDecompressContext#decompress(java.lang.foreign.Arena, java.lang.foreign.MemorySegment)])
    /// reject magicless frames: supply the size with
    /// [ZstdDecompressContext#decompress(byte[], ZstdByteSize)] or
    /// [ZstdDecompressContext#decompress(java.lang.foreign.MemorySegment, java.lang.foreign.MemorySegment)].
    ///
    /// @param enabled whether to expect magicless frames
    /// @return `this`, for chaining
    default S magicless(boolean enabled) {
        return apply(ZstdDecompressParameter.FORMAT, enabled ? 1 : 0);
    }

    private S apply(ZstdDecompressParameter parameter, int value) {
        switch (this) {
            case ZstdDecompressContext ctx -> ctx.setParameter(parameter, value);
            case ZstdDecompressStream stream -> stream.setParameter(parameter, value);
            case ZstdInputStream in -> in.setParameter(parameter, value);
        }
        @SuppressWarnings("unchecked") // S is the implementing type: every permitted subtype binds S to itself
        S self = (S) this;
        return self;
    }
}
