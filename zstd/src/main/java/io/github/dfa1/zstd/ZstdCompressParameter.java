package io.github.dfa1.zstd;

/// Advanced compression parameters settable on a [ZstdCompressContext], mirroring
/// `ZSTD_cParameter` from the
/// [official manual](https://facebook.github.io/zstd/doc/api_manual_latest.html).
///
/// Set them with [ZstdCompressContext#parameter(ZstdCompressParameter, int)]; the
/// context applies them on its next [ZstdCompressContext#compress(byte[])]. Values
/// are validated natively — an out-of-range value raises a [ZstdException].
public enum ZstdCompressParameter {

    /// Frame format: `0` for the standard format (the default), `1` for the
    /// magicless format, which omits the 4-byte magic number. Prefer
    /// [ZstdCompressContext#magicless(boolean)].
    ///
    /// **Experimental** in upstream zstd (`ZSTD_c_format`): the value and its
    /// semantics may change between zstd releases. A magicless frame decodes
    /// only with a decoder set to the same mode via [ZstdDecompressParameter#FORMAT].
    FORMAT(10),
    /// Compression level. Prefer [ZstdCompressContext#level(ZstdCompressionLevel)].
    COMPRESSION_LEVEL(100),
    /// Maximum back-reference distance, as a power of two (larger = better ratio, more memory).
    WINDOW_LOG(101),
    /// Size of the initial probe table, as a power of two.
    HASH_LOG(102),
    /// Size of the multi-probe search table, as a power of two.
    CHAIN_LOG(103),
    /// Number of search attempts, as a power of two.
    SEARCH_LOG(104),
    /// Minimum match length, in bytes.
    MIN_MATCH(105),
    /// Target match length; impact depends on the strategy.
    TARGET_LENGTH(106),
    /// Compression strategy (1..9: fast … btultra2).
    STRATEGY(107),
    /// Target uncompressed block size, in bytes (0 = no target); trades ratio for latency.
    TARGET_C_BLOCK_SIZE(130),
    /// Long-distance matching, for a better ratio on large inputs: `0` lets zstd
    /// decide (the default; on for a btopt-or-stronger strategy with an
    /// effective window log of 27+), `1` forces it on, `2` forces it off. Prefer
    /// [ZstdCompressContext#longDistanceMatching(boolean)].
    ENABLE_LONG_DISTANCE_MATCHING(160),
    /// Long-distance matching hash table size, as a power of two.
    LDM_HASH_LOG(161),
    /// Minimum match length for the long-distance matcher, in bytes.
    LDM_MIN_MATCH(162),
    /// Log size of each bucket in the long-distance matcher's hash table.
    LDM_BUCKET_SIZE_LOG(163),
    /// How often to insert entries into the long-distance matcher's hash table, as a log.
    LDM_HASH_RATE_LOG(164),
    /// Write the decompressed size into the frame header (1, the default) or not (0).
    CONTENT_SIZE_FLAG(200),
    /// Append a 32-bit content checksum to the frame (1) for integrity, or not (0, the default).
    CHECKSUM_FLAG(201),
    /// Write the dictionary id into the frame header (1, the default) or not (0).
    DICT_ID_FLAG(202),
    /// Number of worker threads (0 = single-threaded, the default).
    ///
    /// Workers engage only when the input exceeds zstd's internal job-size
    /// minimum (512 KiB for one-shot compression); smaller inputs compress
    /// single-threaded regardless. Multithreaded output is a valid zstd frame
    /// but is not byte-identical to single-threaded output. Out-of-range
    /// values are clamped into [#bounds()] rather than rejected.
    ///
    /// The worker threads spawn lazily on the first compress call and belong
    /// to the context: [ZstdCompressContext#reset(ZstdResetDirective)] does
    /// not release them, only [ZstdCompressContext#close()] does. A context
    /// that compresses with workers also holds large native job buffers
    /// (tens of MB, scaling with workers and job size) until closed.
    NB_WORKERS(400),
    /// Size of a compression job, in bytes, when multithreading (0 = automatic).
    JOB_SIZE(401),
    /// Overlap between consecutive multithreading jobs, as a log fraction of the window.
    OVERLAP_LOG(402);

    private final int value;

    ZstdCompressParameter(int value) {
        this.value = value;
    }

    /// The native `ZSTD_cParameter` integer value.
    ///
    /// @return the enum value as defined by zstd
    int value() {
        return value;
    }

    /// The inclusive `[lower, upper]` range of values this parameter accepts,
    /// queried from zstd via `ZSTD_cParam_getBounds`.
    ///
    /// @return the valid bounds for this parameter
    /// @throws ZstdException if zstd reports no bounds for this parameter
    public ZstdBounds bounds() {
        return ZstdBounds.query(Bindings.CPARAM_GET_BOUNDS, value);
    }
}
