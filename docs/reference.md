# Reference

## Supported platforms

The library — `io.github.dfa1.zstd:zstd` — ships as a pure-Java module plus one
native artifact per platform:

| OS      | aarch64 | x86_64 |
|---------|:-------:|:------:|
| macOS   |   ✅    |   ✅   |
| Linux   |   ✅    |   ✅   |
| Windows |   ✅    |   ✅   |

## API surface

| Type | Role |
|---|---|
| `Zstd` | one-shot `compress` / `decompress`, level + version queries, `compressBound`, `decompressedSize` |
| `ZstdCompressContext` / `ZstdDecompressContext` | reusable contexts; `byte[]` and `MemorySegment` overloads, dictionary variants |
| `ZstdDictionary` | train (`ZDICT`), load, persist, query dict id |
| `ZstdCompressDictionary` / `ZstdDecompressDictionary` | pre-digested dictionaries for hot paths |
| `ZstdFrame` | frame inspection: header, sizes, dict id, skippable frames |
| `ZstdException` / `ZstdErrorCode` | typed errors mapped from zstd's sentinels |

## Symbol coverage

Which zstd C symbols are bound (and which deprecated ones are intentionally not),
with a per-area breakdown and a comparison against zstd-jni:
[supported.md](supported.md).

## Runtime requirement

Native access requires a flag on the JVM command line: `--enable-native-access=ALL-UNNAMED`
on the classpath, or `--enable-native-access=io.github.dfa1.zstd` on the module path.

## Module path

`zstd` ships a `module-info.java` declaring `module io.github.dfa1.zstd`, which
exports only the public API package (`io.github.dfa1.zstd`). The native library
still comes from the separate `zstd-native-<classifier>` artifact, loaded at
runtime — it is not itself a JPMS module. Putting the jar on the classpath
instead of the module path works unchanged (it becomes part of the unnamed
module and the descriptor is ignored). See
[ADR 0011](../adr/0011-jpms-module-descriptor.md) for the design rationale.

## Build from source

Requires JDK 25+, Maven, and [Zig](https://ziglang.org/) on `PATH`.

```bash
git clone --recurse-submodules https://github.com/dfa1/zstd-ffm.git
cd zstd-ffm
mvn test
```

`scripts/build-zstd.sh` compiles `libzstd.{dylib,so,dll}` from the
`third_party/zstd` submodule (pinned to tag `v1.5.7`) with `zig cc`, cross-compiling
any of the six targets from any host.
