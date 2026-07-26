/// Java Foreign Function & Memory (FFM) bindings for Zstandard.
///
/// Exports the single public API package; the native `libzstd` is loaded at
/// runtime from the platform `zstd-native-<classifier>` artifact on the path.
/// Requires `--enable-native-access=io.github.dfa1.zstd` (or `ALL-UNNAMED` on
/// the classpath) since FFM downcalls are a restricted operation.
@SuppressWarnings("module") // dfa1 is my username in github
module io.github.dfa1.zstd {
    exports io.github.dfa1.zstd;
}
