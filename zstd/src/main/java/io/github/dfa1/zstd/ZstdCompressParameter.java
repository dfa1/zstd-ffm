package io.github.dfa1.zstd;

/// The native `ZSTD_cParameter` ids. Internal: callers use the named setters of
/// [ZstdCompressSettings], which validate through [#checked(long)].
enum ZstdCompressParameter {

    FORMAT(10, "magicless"),
    COMPRESSION_LEVEL(100, "level"),
    WINDOW_LOG(101, "windowLog"),
    HASH_LOG(102, "hashLog"),
    CHAIN_LOG(103, "chainLog"),
    SEARCH_LOG(104, "searchLog"),
    MIN_MATCH(105, "minMatch"),
    TARGET_LENGTH(106, "targetLength"),
    STRATEGY(107, "strategy"),
    TARGET_C_BLOCK_SIZE(130, "targetCBlockSize"),
    ENABLE_LONG_DISTANCE_MATCHING(160, "longDistanceMatching"),
    LDM_HASH_LOG(161, "ldmHashLog"),
    LDM_MIN_MATCH(162, "ldmMinMatch"),
    LDM_BUCKET_SIZE_LOG(163, "ldmBucketSizeLog"),
    LDM_HASH_RATE_LOG(164, "ldmHashRateLog"),
    CONTENT_SIZE_FLAG(200, "contentSize"),
    CHECKSUM_FLAG(201, "checksum"),
    DICT_ID_FLAG(202, "dictId"),
    NB_WORKERS(400, "workers"),
    JOB_SIZE(401, "jobSize"),
    OVERLAP_LOG(402, "overlapLog");

    private final int value;
    private final String setter;

    ZstdCompressParameter(int value, String setter) {
        this.value = value;
        this.setter = setter;
    }

    /// The native `ZSTD_cParameter` id.
    int value() {
        return value;
    }

    /// The public setter for this parameter, named in validation errors.
    String setter() {
        return setter;
    }

    /// The inclusive range zstd accepts, from `ZSTD_cParam_getBounds`.
    ZstdBounds bounds() {
        return ZstdBounds.query(Bindings.CPARAM_GET_BOUNDS, value);
    }

    /// Returns `value` if it is `0` (library default) or within [#bounds()].
    ///
    /// @throws IllegalArgumentException otherwise, naming the valid range
    int checked(long value) {
        ZstdBounds bounds = bounds();
        if (value != 0 && (value < bounds.lowerBound() || value > bounds.upperBound())) {
            throw new IllegalArgumentException(setter + "(" + value + "): must be 0 or in ["
                    + bounds.lowerBound() + ", " + bounds.upperBound() + "]");
        }
        return (int) value;
    }
}
