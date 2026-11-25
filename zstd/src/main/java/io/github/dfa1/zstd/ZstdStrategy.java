package io.github.dfa1.zstd;

/// The compressor's match-finding strategy (`ZSTD_c_strategy`), from fastest to
/// strongest. Each compression level picks one; setting it through
/// [ZstdCompressSettings#strategy(ZstdStrategy)] overrides the level's choice.
public enum ZstdStrategy {

    /// Single hash table, first match wins.
    FAST(1),
    /// Two hash tables, first match wins.
    DFAST(2),
    /// Hash chain, greedy matching.
    GREEDY(3),
    /// Hash chain, lazy matching (looks one position ahead).
    LAZY(4),
    /// Hash chain, lazy matching (looks two positions ahead).
    LAZY2(5),
    /// Binary tree, lazy matching.
    BTLAZY2(6),
    /// Binary tree, optimal parsing.
    BTOPT(7),
    /// Binary tree, optimal parsing with a stronger cost model.
    BTULTRA(8),
    /// Binary tree, optimal parsing with two passes; the strongest.
    BTULTRA2(9);

    private final int value;

    ZstdStrategy(int value) {
        this.value = value;
    }

    /// The native `ZSTD_strategy` value.
    ///
    /// @return the enum value as defined by zstd
    int value() {
        return value;
    }
}
