/// RFC 9842 (Compression Dictionary Transport) `dcz` frame codec.
///
/// Wraps/verifies the skippable-frame header that identifies which dictionary
/// a zstd frame was compressed against (Compression Dictionary Transport §5).
/// No HTTP dependency — pairs with the bindings in `io.github.dfa1.zstd`.
@SuppressWarnings("module") // dfa1 is my username in github
module io.github.dfa1.zstd.rfc9842 {
    requires transitive io.github.dfa1.zstd;
    exports io.github.dfa1.zstd.rfc9842;
}
