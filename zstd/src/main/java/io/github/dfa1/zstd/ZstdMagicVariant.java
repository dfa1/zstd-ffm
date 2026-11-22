package io.github.dfa1.zstd;

/// A validated zstd skippable-frame magic variant — the low nibble `0..15` that
/// selects one of the sixteen skippable magic numbers (`0x184D2A50`..`0x184D2A5F`).
/// Replaces a naked `int` at the skippable-frame API boundary
/// ([ZstdFrame#writeSkippableFrame(byte[], ZstdMagicVariant)] and
/// [ZstdSkippableContent#magicVariant()]), rejecting an out-of-range selector in
/// Java rather than deferring to native code.
public final class ZstdMagicVariant {

    private static final int MIN = 0;
    private static final int MAX = 15;

    private final int value;

    private ZstdMagicVariant(int value) {
        if (value < MIN || value > MAX) {
            throw new IllegalArgumentException(
                    "magic variant " + value + " must be in [" + MIN + ", " + MAX + "]");
        }
        this.value = value;
    }

    /// Wraps `value`, validating it.
    ///
    /// @param value the variant selector, `0..15`
    /// @return the new [ZstdMagicVariant]
    /// @throws IllegalArgumentException if `value` is outside `0..15`
    public static ZstdMagicVariant of(int value) {
        return new ZstdMagicVariant(value);
    }

    /// The variant selector, `0..15`.
    ///
    /// @return the variant selector, `0..15`
    public int value() {
        return value;
    }

    /// Value equality over all components.
    ///
    /// @param o the object to compare with
    /// @return `true` if `o` is a [ZstdMagicVariant] with equal components
    @Override
    public boolean equals(Object o) {
        return o instanceof ZstdMagicVariant other
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
    /// @return `ZstdMagicVariant[component=value, ...]`
    @Override
    public String toString() {
        return "ZstdMagicVariant[value=" + value + "]";
    }
}
