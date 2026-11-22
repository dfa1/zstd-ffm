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
public final class ZstdWindowLog {

    // Queried once from the linked libzstd, fixed for the life of the process.
    private static final int MIN_ACCEPTED = ZstdCompressParameter.WINDOW_LOG.bounds().lowerBound();
    private static final int MAX_ACCEPTED = ZstdCompressParameter.WINDOW_LOG.bounds().upperBound();

    /// Let the library select the window size (value `0`).
    public static final ZstdWindowLog AUTO = new ZstdWindowLog(0);

    private final int value;

    private ZstdWindowLog(int value) {
        if (value != 0 && (value < MIN_ACCEPTED || value > MAX_ACCEPTED)) {
            throw new IllegalArgumentException(
                    "windowLog " + value + " must be 0 or in [" + MIN_ACCEPTED + ", " + MAX_ACCEPTED + "]");
        }
        this.value = value;
    }

    /// Wraps `value`, validating it.
    ///
    /// @param value the base-2 log of the window size, or `0` for [#AUTO]
    /// @return the new [ZstdWindowLog]
    /// @throws IllegalArgumentException if `value` is neither `0` nor within the linked libzstd's window-log bounds
    public static ZstdWindowLog of(int value) {
        return new ZstdWindowLog(value);
    }

    /// The base-2 log of the window size, or `0` for [#AUTO].
    ///
    /// @return the base-2 log of the window size, or `0` for [#AUTO]
    public int value() {
        return value;
    }

    /// Value equality over all components.
    ///
    /// @param o the object to compare with
    /// @return `true` if `o` is a [ZstdWindowLog] with equal components
    @Override
    public boolean equals(Object o) {
        return o instanceof ZstdWindowLog other
                && value == other.value;
    }

    /// Hash code consistent with [#equals(Object)].
    ///
    /// @return the component-based hash code
    @Override
    public int hashCode() {
        return Integer.hashCode(value);
    }

    /// Record-style description listing every component.
    ///
    /// @return `ZstdWindowLog[component=value, ...]`
    @Override
    public String toString() {
        return "ZstdWindowLog[value=" + value + "]";
    }
}
