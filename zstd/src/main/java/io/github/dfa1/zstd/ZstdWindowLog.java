package io.github.dfa1.zstd;

/// A validated zstd window-log — the base-2 logarithm of the back-reference
/// window size — replacing a naked `int` at every public API boundary that takes
/// one ([ZstdCompressContext#windowLog(ZstdWindowLog)] and
/// [ZstdDecompressContext#windowLogMax(ZstdWindowLog)]).
///
/// A larger window trades memory for ratio. [#AUTO] (value `0`) leaves the choice
/// to the library — derived from the compression level on the encoder side, and
/// meaning "impose no extra limit" on the decoder side. Any other value must fall
/// within the range the linked libzstd accepts (the [ZstdBounds] of
/// [ZstdCompressParameter#WINDOW_LOG], which match the decoder's window limit on
/// supported platforms), validated in Java before it reaches native code.
///
/// @param value the base-2 log of the window size, or `0` for [#AUTO]
public record ZstdWindowLog(int value) {

    // Queried once from the linked libzstd, fixed for the life of the process.
    private static final int MIN_ACCEPTED = ZstdCompressParameter.WINDOW_LOG.bounds().lowerBound();
    private static final int MAX_ACCEPTED = ZstdCompressParameter.WINDOW_LOG.bounds().upperBound();

    /// Let the library select the window size (value `0`).
    public static final ZstdWindowLog AUTO = new ZstdWindowLog(0);

    /// Validates `value` is `0` ([#AUTO]) or within the linked libzstd's accepted range.
    public ZstdWindowLog {
        if (value != 0 && (value < MIN_ACCEPTED || value > MAX_ACCEPTED)) {
            throw new IllegalArgumentException(
                    "windowLog " + value + " must be 0 or in [" + MIN_ACCEPTED + ", " + MAX_ACCEPTED + "]");
        }
    }
}
