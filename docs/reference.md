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

## RFC 9842 (Compression Dictionary Transport)

`io.github.dfa1.zstd:zstd-rfc9842` — a separate module, on top of `zstd`:

```xml
<dependency>
  <groupId>io.github.dfa1.zstd</groupId>
  <artifactId>zstd-rfc9842</artifactId>
  <version>0.13</version>
</dependency>
```

| Type | Role |
|---|---|
| `Rfc9842Frame` | wraps/unwraps the `dcz` wire format (§5): a 40-byte header (skippable-frame magic + SHA-256 dictionary hash) around a compressed zstd frame |
| `UseAsDictionaryHeader` | the `Use-As-Dictionary` response header (§2.1): where/how a dictionary applies, its id, its type |
| `AvailableDictionaryHeader` | the `Available-Dictionary` request header (§2.2): a dictionary's SHA-256 hash — also the value embedded in a `dcz` header |
| `DictionaryIdHeader` | the `Dictionary-ID` header (§2.3): the id a client echoes back |
| `Rfc9842Negotiation` | everything a client needs after fetching a dictionary once, derived from its bytes plus the `Use-As-Dictionary` value it arrived with |
| `Rfc9842Exception` | thrown for malformed header values or a `dcz` header that doesn't verify |

Each header type exposes its HTTP header name as a `HTTP_HEADER` constant
(e.g. `AvailableDictionaryHeader.HTTP_HEADER`), and `parse(String)` /
`toHeaderValue()` for round-tripping the raw header value.

The module is [sans-io](https://sans-io.readthedocs.io/): everything above is
pure framing/parsing/hashing over `byte[]`/`MemorySegment`, with no `java.net`
dependency — enforced by an
[ArchUnit rule](../rfc9842/src/test/java/io/github/dfa1/zstd/rfc9842/ArchitectureTest.java).
See the [how-to guide](how-to.md#negotiate-and-use-an-rfc-9842-dictionary) to
wire it into an actual client/server.

## Build from source

Building from source is for contributors — consumers should use the published
artifacts (see [Install](../README.md#install)).

### Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 25+ | first LTS with stable `java.lang.foreign`; [adoptium.net](https://adoptium.net/) has Temurin builds for every OS |
| Zig | 0.16.0 | on `PATH`; compiles the vendored zstd C sources — see install below |
| Git | any | needed for the `third_party/zstd` submodule |

Maven itself is *not* required — the repo ships the Maven Wrapper (`mvnw`
/ `mvnw.cmd`), which downloads the pinned Maven version on first run.

**Installing Zig on Linux/macOS** — download a release archive from
[ziglang.org/download](https://ziglang.org/download/) and put the extracted
`zig` binary on `PATH`, or use a version manager (`asdf install zig 0.16.0`,
`mise use zig@0.16.0`). Confirm with:

```bash
zig version   # must print 0.16.0 or newer
```

### Clone and build

```bash
git clone --recurse-submodules https://github.com/dfa1/zstd-ffm.git
cd zstd-ffm
./mvnw verify
```

If you already cloned without `--recurse-submodules` (the build fails with
`third_party/zstd` empty / missing headers), fetch the submodule after the fact:

```bash
git submodule update --init --recursive
```

`./mvnw verify` compiles every module, runs `scripts/build-zstd.sh` to build
`libzstd.{dylib,so,dll}` from the `third_party/zstd` submodule (pinned to tag
`v1.5.7`) with `zig cc` — cross-compiling any of the six target classifiers
from any host, no system toolchain or sysroot needed — then runs the full
test suite, checkstyle, and javadoc generation. The native build is
idempotent: it skips recompiling a classifier whose library file already
exists under the module's `target/` resources.

A plain `./mvnw test` skips checkstyle/javadoc enforcement if you just want a
faster inner loop while iterating.
