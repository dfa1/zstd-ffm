package io.github.dfa1.zstd;

/// The native `ZSTD_dParameter` ids. Internal: callers use the named setters of
/// [ZstdDecompressSettings], which validate through [#checked(long)].
enum ZstdDecompressParameter {

    WINDOW_LOG_MAX(100, "windowLogMax"),
    FORMAT(1000, "magicless"),
    FORCE_IGNORE_CHECKSUM(1002, "ignoreChecksum");

    private final int value;
    private final String setter;

    ZstdDecompressParameter(int value, String setter) {
        this.value = value;
        this.setter = setter;
    }

    /// The native `ZSTD_dParameter` id.
    int value() {
        return value;
    }

    /// The public setter for this parameter, named in validation errors.
    String setter() {
        return setter;
    }

    /// The inclusive range zstd accepts, from `ZSTD_dParam_getBounds`.
    ZstdBounds bounds() {
        return ZstdBounds.query(Bindings.DPARAM_GET_BOUNDS, value);
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
