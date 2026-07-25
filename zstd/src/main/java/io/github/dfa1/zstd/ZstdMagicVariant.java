package io.github.dfa1.zstd;

/// A validated zstd skippable-frame magic variant — the low nibble `0..15` that
/// selects one of the sixteen skippable magic numbers (`0x184D2A50`..`0x184D2A5F`).
/// Replaces a naked `int` at the skippable-frame API boundary
/// ([ZstdFrame#writeSkippableFrame(byte[], ZstdMagicVariant)] and
/// [ZstdSkippableContent#magicVariant()]), rejecting an out-of-range selector in
/// Java rather than deferring to native code.
///
/// @param value the variant selector, `0..15`
public record ZstdMagicVariant(int value) {

    private static final int MIN = 0;
    private static final int MAX = 15;

    /// Validates `value` is in `0..15`.
    public ZstdMagicVariant {
        if (value < MIN || value > MAX) {
            throw new IllegalArgumentException(
                    "magic variant " + value + " must be in [" + MIN + ", " + MAX + "]");
        }
    }
}
