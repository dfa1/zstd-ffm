package io.github.dfa1.zstd;

/// Advanced decompression parameters settable on a [ZstdDecompressContext], mirroring
/// `ZSTD_dParameter` from the
/// [official manual](https://facebook.github.io/zstd/doc/api_manual_latest.html).
///
/// Set them with [ZstdDecompressContext#parameter(ZstdDecompressParameter, int)].
public enum ZstdDecompressParameter {

    /// Largest back-reference window the decoder will accept, as a power of two.
    /// Frames needing a larger window are rejected; raise this to decode them.
    WINDOW_LOG_MAX(100),
    /// Checksum validation: `0` validates the frame's content checksum (the
    /// default), `1` skips it. Prefer [ZstdDecompressContext#ignoreChecksum(boolean)].
    ///
    /// **Experimental** in upstream zstd (`ZSTD_d_forceIgnoreChecksum`): the
    /// value and its semantics may change between zstd releases. Skipping the
    /// check means corrupted payloads are no longer detected.
    FORCE_IGNORE_CHECKSUM(1002);

    private final int value;

    ZstdDecompressParameter(int value) {
        this.value = value;
    }

    /// The native `ZSTD_dParameter` integer value.
    ///
    /// @return the enum value as defined by zstd
    int value() {
        return value;
    }

    /// The inclusive `[lower, upper]` range of values this parameter accepts,
    /// queried from zstd via `ZSTD_dParam_getBounds`.
    ///
    /// @return the valid bounds for this parameter
    /// @throws ZstdException if zstd reports no bounds for this parameter
    public ZstdBounds bounds() {
        return ZstdBounds.query(Bindings.DPARAM_GET_BOUNDS, value);
    }
}
