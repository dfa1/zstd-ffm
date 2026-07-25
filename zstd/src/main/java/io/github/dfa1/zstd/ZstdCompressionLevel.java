package io.github.dfa1.zstd;

/// A validated zstd compression level, replacing a naked `int` at every public
/// API boundary that takes one.
///
/// zstd levels span two very different ranges under one number. `1..`[#MAX] are
/// the discrete, named levels most people mean by "zstd level"; everything from
/// [#FASTEST] up to (but not including) `1` is a continuous fast-mode
/// acceleration knob (`ZSTD_c_targetLength` under the hood), not discrete named
/// levels. This type does not attempt to model that split — it validates that a
/// raw level falls within the range the linked libzstd actually accepts
/// ([#FASTEST]..[#MAX]), catching a bad
/// level in Java before it reaches native code, rather than relying on zstd's
/// own clamping/error behavior.
///
/// Prefer the [#DEFAULT] / [#FASTEST] / [#MAX] constants for those specific
/// levels; construct directly for any other level.
///
/// @param value the raw zstd compression level
public record ZstdCompressionLevel(int value) {

    // Queried once: the linked libzstd's accepted range is fixed for the life of
    // the process, so caching it here saves two native calls
    // (ZSTD_minCLevel/ZSTD_maxCLevel) on every construction.
    private static final int MIN_ACCEPTED = minCompressionLevel();
    private static final int MAX_ACCEPTED = maxCompressionLevel();

    /// The level [Zstd#compress(byte[])] uses when none is given.
    public static final ZstdCompressionLevel DEFAULT = new ZstdCompressionLevel(defaultCompressionLevel());

    /// The fastest, lowest-ratio level this linked libzstd accepts.
    public static final ZstdCompressionLevel FASTEST = new ZstdCompressionLevel(MIN_ACCEPTED);

    /// The slowest, highest-ratio level this linked libzstd accepts.
    public static final ZstdCompressionLevel MAX = new ZstdCompressionLevel(MAX_ACCEPTED);

    /// Validates `value` against the linked libzstd's accepted range.
    public ZstdCompressionLevel {
        if (value < MIN_ACCEPTED || value > MAX_ACCEPTED) {
            throw new IllegalArgumentException("level " + value + " outside [" + MIN_ACCEPTED + ", " + MAX_ACCEPTED + "]");
        }
    }

    /// Highest supported compression level, as a naked `int` for internal
    /// bootstrap. Public callers get it as a value object via [#MAX].
    ///
    /// @return the maximum valid compression level
    static int maxCompressionLevel() {
        try {
            return (int) Bindings.MAX_C_LEVEL.invokeExact();
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    /// Lowest supported compression level (negative levels trade ratio for speed),
    /// as a naked `int` for internal bootstrap. Public callers get it as a value
    /// object via [#FASTEST].
    ///
    /// @return the minimum valid compression level
    static int minCompressionLevel() {
        try {
            return (int) Bindings.MIN_C_LEVEL.invokeExact();
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    /// The level [Zstd#compress(byte[])] uses when none is given, as a naked `int`
    /// for internal bootstrap. Public callers get it as a value object via
    /// [#DEFAULT].
    ///
    /// @return the default compression level
    static int defaultCompressionLevel() {
        try {
            return (int) Bindings.DEFAULT_C_LEVEL.invokeExact();
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }
}
