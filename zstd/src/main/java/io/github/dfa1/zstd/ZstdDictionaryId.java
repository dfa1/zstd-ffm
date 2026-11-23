package io.github.dfa1.zstd;

/// A zstd dictionary id: the 32-bit value a dictionary stamps into the frames it
/// compresses, letting a decompressor pick the matching dictionary.
///
/// zstd treats the id as a C `unsigned`, so the wire value can exceed
/// [Integer#MAX_VALUE]. The raw 32-bit pattern is kept in [#raw()]; read it as an
/// unsigned magnitude with [#value()]. The value `0` is the sentinel for "no
/// dictionary id" — a raw/content-only dictionary, or a frame that records none —
/// exposed as [#NONE] and tested with [#isPresent()].
public final class ZstdDictionaryId {

    /// The absent id: no dictionary, or a dictionary with no recorded id.
    public static final ZstdDictionaryId NONE = new ZstdDictionaryId(0);

    private final int raw;

    private ZstdDictionaryId(int raw) {
        this.raw = raw;
    }

    /// Wraps a raw 32-bit id, returning [#NONE] for `0`.
    ///
    /// @param raw the 32-bit id as zstd stores it
    /// @return the wrapped id, or [#NONE] if `raw` is `0`
    public static ZstdDictionaryId of(int raw) {
        return raw == 0 ? NONE : new ZstdDictionaryId(raw);
    }

    /// Whether an id is present (non-zero).
    ///
    /// @return `true` unless this is [#NONE]
    public boolean isPresent() {
        return raw != 0;
    }

    /// The id as an unsigned magnitude in `[0, 2^32)`, widening the raw 32-bit
    /// pattern without sign extension.
    ///
    /// @return the unsigned id value
    public long value() {
        return Integer.toUnsignedLong(raw);
    }

    /// The 32-bit id as zstd stores it, possibly negative when read as a signed `int`; `0`
    /// means no id.
    ///
    /// @return the `raw` component
    public int raw() {
        return raw;
    }

    /// Value equality over all components.
    ///
    /// @param o the object to compare with
    /// @return `true` if `o` is a [ZstdDictionaryId] with equal components
    @Override
    public boolean equals(Object o) {
        return o instanceof ZstdDictionaryId other
                && raw == other.raw;
    }

    /// Hash code consistent with [#equals(Object)].
    ///
    /// @return the component-based hash code
    @Override
    public int hashCode() {
        return Integer.hashCode(raw);
    }

    /// Record-style description listing every component.
    ///
    /// @return this value as `ZstdDictionaryId[component=value, ...]`, naming every component
    @Override
    public String toString() {
        return "ZstdDictionaryId[raw=" + raw + "]";
    }
}
