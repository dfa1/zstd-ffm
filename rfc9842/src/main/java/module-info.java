/// RFC 9842 (Compression Dictionary Transport) support: the `dcz` frame codec
/// and a framework-agnostic model of the three HTTP headers.
///
/// [io.github.dfa1.zstd.rfc9842.Rfc9842Frame] wraps/verifies the
/// skippable-frame header that identifies which dictionary a zstd frame was
/// compressed against (§5). [io.github.dfa1.zstd.rfc9842.UseAsDictionary],
/// [io.github.dfa1.zstd.rfc9842.AvailableDictionary], and
/// [io.github.dfa1.zstd.rfc9842.DictionaryId] parse/build the values of the
/// `Use-As-Dictionary`, `Available-Dictionary`, and `Dictionary-ID` headers
/// (§2), with zero dependency on any HTTP framework. Pairs with the bindings
/// in `io.github.dfa1.zstd`; no HTTP dependency of its own.
///
/// **Not targeting browsers.** RFC 9842 is written for browser HTTP caches,
/// but nothing here assumes one: the header model omits browser-only concepts
/// like fetch destinations (`Sec-Fetch-Dest`/`match-dest`). The intended
/// caller is a non-browser server or client — service-to-service, an SDK, a
/// B2B integration.
@SuppressWarnings("module") // dfa1 is my username in github
module io.github.dfa1.zstd.rfc9842 {
    requires transitive io.github.dfa1.zstd;
    exports io.github.dfa1.zstd.rfc9842;
}
