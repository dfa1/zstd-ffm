# Contributing: build from source

How to build, test, and iterate on zstd-ffm itself. For running against a
`libzstd` you built yourself, see the
[how-to guide](how-to.md#run-against-a-self-built-libzstd).

Building from source is for contributors — consumers should use the published
artifacts (see [the tutorial](tutorial.md#1-add-the-dependency)).

## Prerequisites

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

## Clone and build

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
`v1.6.0`) with `zig cc` — cross-compiling any of the six target classifiers
from any host, no system toolchain or sysroot needed — then runs the full
test suite, checkstyle, and javadoc generation. The native build is
idempotent: it skips recompiling a classifier whose library file already
exists at `native/<classifier>/src/main/resources/native/<classifier>/libzstd.<ext>`
(delete that file to force a rebuild).

A plain `./mvnw test` still runs checkstyle (it is bound to the `validate`
phase) but skips javadoc generation and the jacoco coverage check, for a faster
inner loop while iterating.

## Conventions

The code conventions, test style, and the full `./mvnw verify` gate are
described in the repository's [`CLAUDE.md`](../CLAUDE.md); architecture
decisions are recorded as [ADRs](../adr/ADR.md).
