package io.github.dfa1.zstd;

/// Advanced decompression parameters settable on a [ZstdDecompressContext], mirroring
/// `ZSTD_dParameter` from the
/// [official manual](https://facebook.github.io/zstd/doc/api_manual_latest.html).
///
/// Set them with [ZstdDecompressContext#parameter(ZstdDecompressParameter, int)].
public enum ZstdDecompressParameter {

    /// Memory limit for streaming decompression: the largest back-reference
    /// window, as a power of two, that a streaming decoder ([ZstdInputStream],
    /// [ZstdDecompressStream]) will allocate a buffer for. Frames declaring a
    /// larger window fail with [ZstdErrorCode#FRAME_PARAMETER_WINDOW_TOO_LARGE];
    /// `0` restores the default limit, `27` (128 MiB).
    ///
    /// One-shot decompression ignores it: it decodes straight into the caller's
    /// buffer, allocates no window buffer, and so has nothing to limit.
    WINDOW_LOG_MAX(100),
    /// Frame format: `0` for the standard format (the default), `1` for the
    /// magicless format, which omits the 4-byte magic number. Prefer
    /// [ZstdDecompressContext#magicless(boolean)].
    ///
    /// **Experimental** in upstream zstd (`ZSTD_d_format`): the value and its
    /// semantics may change between zstd releases. The decoder accepts only
    /// frames in the selected format, so it must match the encoder's
    /// [ZstdCompressParameter#FORMAT].
    FORMAT(1000),
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
