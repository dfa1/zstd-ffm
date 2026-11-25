package io.github.dfa1.zstd;

import java.util.Objects;

/// The compression settings shared by [ZstdCompressContext],
/// [ZstdCompressStream] and [ZstdOutputStream].
///
/// One setter per zstd advanced parameter (`ZSTD_cParameter`). Numeric
/// setters accept `0`, meaning "let the library choose", or a value within the
/// bounds the linked libzstd reports for that parameter; anything else throws
/// [IllegalArgumentException] naming the valid range, before reaching native
/// code.
///
/// Settings are sticky: they apply to every following frame until changed, or
/// until a parameter [ZstdCompressContext#reset(ZstdResetDirective)] restores the
/// defaults. On a stream, set them before the first write; zstd rejects most
/// changes mid-frame with [ZstdException]. On a closed context or stream a
/// valid setter call throws [IllegalStateException]. The per-call dictionary overloads of
/// [ZstdCompressContext] ignore these settings: `compress(src, ZstdDictionary)`
/// uses only the level, and the [ZstdCompressDictionary] overloads use none of
/// them (the level comes from the dictionary).
///
/// @param <S> the implementing type, returned by every setter for chaining
public sealed interface ZstdCompressSettings<S extends ZstdCompressSettings<S>>
        permits ZstdCompressContext, ZstdCompressStream, ZstdOutputStream {

    /// Sets the compression level: a preset for every match-finding parameter.
    /// Parameters set explicitly take precedence over the preset, whatever the
    /// call order.
    ///
    /// @param level the compression level to use
    /// @return `this`, for chaining
    default S level(ZstdCompressionLevel level) {
        Objects.requireNonNull(level, "level");
        return apply(ZstdCompressParameter.COMPRESSION_LEVEL, level.value());
    }

    /// Appends a 32-bit content checksum to each frame, which decompression
    /// verifies and rejects on mismatch. Off by default.
    ///
    /// @param enabled whether to write a checksum
    /// @return `this`, for chaining
    default S checksum(boolean enabled) {
        return apply(ZstdCompressParameter.CHECKSUM_FLAG, enabled ? 1 : 0);
    }

    /// Writes the decompressed size into each frame header when it is known. On
    /// by default; turning it off saves up to 8 bytes per frame, but decoders
    /// then cannot size their output from the header.
    ///
    /// @param enabled whether to write the content size
    /// @return `this`, for chaining
    default S contentSize(boolean enabled) {
        return apply(ZstdCompressParameter.CONTENT_SIZE_FLAG, enabled ? 1 : 0);
    }

    /// Writes the dictionary id into each frame header when compressing with a
    /// dictionary. On by default; turning it off saves up to 4 bytes per frame,
    /// but decoders then cannot tell which dictionary a frame needs.
    ///
    /// @param enabled whether to write the dictionary id
    /// @return `this`, for chaining
    default S dictId(boolean enabled) {
        return apply(ZstdCompressParameter.DICT_ID_FLAG, enabled ? 1 : 0);
    }

    /// Writes magicless frames, which omit the 4-byte magic number `28 B5 2F FD`,
    /// saving 4 bytes per frame when storing many tiny frames. Off by default.
    ///
    /// **Experimental** in upstream zstd. The frames decode only with a decoder
    /// set to magicless as well ([ZstdDecompressContext#magicless(boolean)],
    /// [ZstdInputStream#magicless(boolean)], [ZstdDecompressStream#magicless(boolean)]),
    /// and are not recognized by [Zstd#decompress(byte[])] or the [ZstdFrame]
    /// inspection helpers, which assume the standard format.
    ///
    /// @param enabled whether to omit the magic number
    /// @return `this`, for chaining
    default S magicless(boolean enabled) {
        return apply(ZstdCompressParameter.FORMAT, enabled ? 1 : 0);
    }

    /// Overrides the match-finding strategy the compression level chose. Only a
    /// parameter [ZstdCompressContext#reset(ZstdResetDirective)] returns to the
    /// level's choice.
    ///
    /// @param strategy the strategy to use
    /// @return `this`, for chaining
    default S strategy(ZstdStrategy strategy) {
        Objects.requireNonNull(strategy, "strategy");
        return apply(ZstdCompressParameter.STRATEGY, strategy.value());
    }

    /// Forces long-distance matching on (`true`) or off (`false`). On, it gives a
    /// better ratio on large, repetitive inputs at the cost of memory.
    ///
    /// Without this call zstd decides on its own, from the *effective*
    /// parameters (after it shrinks the window to fit an input of known size):
    /// it enables long-distance matching when the strategy is btopt or stronger
    /// (level 16+ on large inputs) and the window log is at least 27. Out of the
    /// box that means level 22, or a [#windowLog(int)] of 27+, and only for
    /// inputs over 64 MiB or of unknown size.
    ///
    /// @param enabled `true` to force long-distance matching on, `false` to force it off
    /// @return `this`, for chaining
    default S longDistanceMatching(boolean enabled) {
        // ZSTD_c_enableLongDistanceMatching is a ZSTD_ParamSwitch_e, not a boolean:
        // 0 = auto, 1 = enable, 2 = disable. Sending 0 for `false` would select auto.
        return apply(ZstdCompressParameter.ENABLE_LONG_DISTANCE_MATCHING, enabled ? 1 : 2);
    }

    /// Sets the maximum back-reference distance, as a power of two (larger window =
    /// better ratio, more memory). A frame whose window exceeds `27` (128 MiB) is
    /// rejected by streaming decoders until their limit is raised with
    /// [ZstdDecompressSettings#windowLogMax(int)]; one-shot decompression accepts
    /// it as is.
    ///
    /// @param windowLog the base-2 log of the window size, or `0` to let the library choose
    /// @return `this`, for chaining
    /// @throws IllegalArgumentException if `windowLog` is neither `0` nor within zstd's bounds
    default S windowLog(int windowLog) {
        return apply(ZstdCompressParameter.WINDOW_LOG, ZstdCompressParameter.WINDOW_LOG.checked(windowLog));
    }

    /// Sets the size of the initial probe table, as a power of two. Larger improves
    /// ratio, at the cost of memory and speed.
    ///
    /// @param hashLog the base-2 log of the table size, or `0` to let the library choose
    /// @return `this`, for chaining
    /// @throws IllegalArgumentException if `hashLog` is neither `0` nor within zstd's bounds
    default S hashLog(int hashLog) {
        return apply(ZstdCompressParameter.HASH_LOG, ZstdCompressParameter.HASH_LOG.checked(hashLog));
    }

    /// Sets the size of the multi-probe search table, as a power of two. Larger
    /// improves ratio, at the cost of memory and speed; unused by
    /// [ZstdStrategy#FAST].
    ///
    /// @param chainLog the base-2 log of the table size, or `0` to let the library choose
    /// @return `this`, for chaining
    /// @throws IllegalArgumentException if `chainLog` is neither `0` nor within zstd's bounds
    default S chainLog(int chainLog) {
        return apply(ZstdCompressParameter.CHAIN_LOG, ZstdCompressParameter.CHAIN_LOG.checked(chainLog));
    }

    /// Sets the number of search attempts, as a power of two. More attempts improve
    /// ratio, at the cost of speed.
    ///
    /// @param searchLog the base-2 log of the number of attempts, or `0` to let the library choose
    /// @return `this`, for chaining
    /// @throws IllegalArgumentException if `searchLog` is neither `0` nor within zstd's bounds
    default S searchLog(int searchLog) {
        return apply(ZstdCompressParameter.SEARCH_LOG, ZstdCompressParameter.SEARCH_LOG.checked(searchLog));
    }

    /// Sets the minimum match length, in bytes. Larger is faster and loses ratio.
    ///
    /// @param minMatch the minimum match length, in bytes, or `0` to let the library choose
    /// @return `this`, for chaining
    /// @throws IllegalArgumentException if `minMatch` is neither `0` nor within zstd's bounds
    default S minMatch(int minMatch) {
        return apply(ZstdCompressParameter.MIN_MATCH, ZstdCompressParameter.MIN_MATCH.checked(minMatch));
    }

    /// Sets the target match length; its effect depends on the strategy.
    ///
    /// @param targetLength the target match length, or `0` to let the library choose
    /// @return `this`, for chaining
    /// @throws IllegalArgumentException if `targetLength` is neither `0` nor within zstd's bounds
    default S targetLength(int targetLength) {
        return apply(ZstdCompressParameter.TARGET_LENGTH, ZstdCompressParameter.TARGET_LENGTH.checked(targetLength));
    }

    /// Sets a target compressed block size, trading ratio for lower latency on
    /// streamed data. Zero, the default, sets no target.
    ///
    /// @param targetCBlockSize the target compressed block size, or zero for no target
    /// @return `this`, for chaining
    /// @throws IllegalArgumentException if `targetCBlockSize` is neither zero nor within zstd's bounds
    default S targetCBlockSize(ZstdByteSize targetCBlockSize) {
        Objects.requireNonNull(targetCBlockSize, "targetCBlockSize");
        return apply(ZstdCompressParameter.TARGET_C_BLOCK_SIZE,
                ZstdCompressParameter.TARGET_C_BLOCK_SIZE.checked(targetCBlockSize.value()));
    }

    /// Sets the size of the long-distance matcher's hash table, as a power of two.
    /// Only applies with [#longDistanceMatching(boolean)].
    ///
    /// @param ldmHashLog the base-2 log of the table size, or `0` to let the library choose
    /// @return `this`, for chaining
    /// @throws IllegalArgumentException if `ldmHashLog` is neither `0` nor within zstd's bounds
    default S ldmHashLog(int ldmHashLog) {
        return apply(ZstdCompressParameter.LDM_HASH_LOG, ZstdCompressParameter.LDM_HASH_LOG.checked(ldmHashLog));
    }

    /// Sets the long-distance matcher's minimum match length, in bytes. Only
    /// applies with [#longDistanceMatching(boolean)].
    ///
    /// @param ldmMinMatch the minimum match length, in bytes, or `0` to let the library choose
    /// @return `this`, for chaining
    /// @throws IllegalArgumentException if `ldmMinMatch` is neither `0` nor within zstd's bounds
    default S ldmMinMatch(int ldmMinMatch) {
        return apply(ZstdCompressParameter.LDM_MIN_MATCH, ZstdCompressParameter.LDM_MIN_MATCH.checked(ldmMinMatch));
    }

    /// Sets the size of each bucket in the long-distance matcher's hash table, as
    /// a power of two. Only applies with [#longDistanceMatching(boolean)].
    ///
    /// @param ldmBucketSizeLog the base-2 log of the bucket size, or `0` to let the library choose
    /// @return `this`, for chaining
    /// @throws IllegalArgumentException if `ldmBucketSizeLog` is neither `0` nor within zstd's bounds
    default S ldmBucketSizeLog(int ldmBucketSizeLog) {
        return apply(ZstdCompressParameter.LDM_BUCKET_SIZE_LOG, ZstdCompressParameter.LDM_BUCKET_SIZE_LOG.checked(ldmBucketSizeLog));
    }

    /// Sets how often the long-distance matcher inserts into its hash table, as a
    /// power of two. Only applies with [#longDistanceMatching(boolean)].
    ///
    /// @param ldmHashRateLog the base-2 log of the insertion interval, or `0` to let the library choose
    /// @return `this`, for chaining
    /// @throws IllegalArgumentException if `ldmHashRateLog` is neither `0` nor within zstd's bounds
    default S ldmHashRateLog(int ldmHashRateLog) {
        return apply(ZstdCompressParameter.LDM_HASH_RATE_LOG, ZstdCompressParameter.LDM_HASH_RATE_LOG.checked(ldmHashRateLog));
    }

    /// Sets the number of compression worker threads; `0`, the default, compresses
    /// on the calling thread.
    ///
    /// Workers engage only when the input exceeds zstd's internal job-size
    /// minimum (512 KiB for one-shot compression); smaller inputs compress
    /// single-threaded regardless. Multithreaded output is a valid zstd frame but
    /// is not byte-identical to single-threaded output. The worker threads spawn
    /// lazily on the first compression and belong to the context or stream:
    /// [ZstdCompressContext#reset(ZstdResetDirective)] does not release them, only
    /// closing does, and a context that compressed with workers also holds large
    /// native job buffers (tens of MB) until closed.
    ///
    /// @param workers the number of worker threads, or `0` to compress on the calling thread
    /// @return `this`, for chaining
    /// @throws IllegalArgumentException if `workers` is neither `0` nor within zstd's bounds
    default S workers(int workers) {
        return apply(ZstdCompressParameter.NB_WORKERS, ZstdCompressParameter.NB_WORKERS.checked(workers));
    }

    /// Sets the size of each multithreaded compression job; zero, the default,
    /// lets the library choose. zstd raises smaller values to its
    /// 512 KiB minimum. Only applies with [#workers(int)].
    ///
    /// @param jobSize the job size, or zero to let the library choose
    /// @return `this`, for chaining
    /// @throws IllegalArgumentException if `jobSize` is neither zero nor within zstd's bounds
    default S jobSize(ZstdByteSize jobSize) {
        Objects.requireNonNull(jobSize, "jobSize");
        return apply(ZstdCompressParameter.JOB_SIZE, ZstdCompressParameter.JOB_SIZE.checked(jobSize.value()));
    }

    /// Sets how much consecutive multithreaded jobs overlap, as a log fraction of
    /// the window. Only applies with [#workers(int)].
    ///
    /// @param overlapLog the overlap, as a log fraction of the window, or `0` to let the library choose
    /// @return `this`, for chaining
    /// @throws IllegalArgumentException if `overlapLog` is neither `0` nor within zstd's bounds
    default S overlapLog(int overlapLog) {
        return apply(ZstdCompressParameter.OVERLAP_LOG, ZstdCompressParameter.OVERLAP_LOG.checked(overlapLog));
    }

    private S apply(ZstdCompressParameter parameter, int value) {
        switch (this) {
            case ZstdCompressContext ctx -> ctx.setParameter(parameter, value);
            case ZstdCompressStream stream -> stream.setParameter(parameter, value);
            case ZstdOutputStream out -> out.setParameter(parameter, value);
        }
        @SuppressWarnings("unchecked") // S is the implementing type: every permitted subtype binds S to itself
        S self = (S) this;
        return self;
    }
}
