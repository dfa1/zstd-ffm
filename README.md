# zstd-ffm

[![CI](https://github.com/dfa1/zstd-ffm/actions/workflows/ci.yml/badge.svg)](https://github.com/dfa1/zstd-ffm/actions/workflows/ci.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=dfa1_zstd-ffm&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=dfa1_zstd-ffm)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=dfa1_zstd-ffm&metric=coverage)](https://sonarcloud.io/summary/new_code?id=dfa1_zstd-ffm)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.dfa1.zstd/zstd.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.dfa1.zstd/zstd)
![zstd](https://img.shields.io/badge/zstd-1.5.7-green.svg)
![Java](https://img.shields.io/badge/Java-25%2B-orange.svg)
[![License](https://img.shields.io/badge/License-BSD%203--Clause-blue.svg)](LICENSE)

**zstd-ffm** is an **FFM-based alternative to the excellent
[zstd-jni](https://github.com/luben/zstd-jni)** for early adopters on **JDK 25+**.
It wraps [Zstandard](https://github.com/facebook/zstd) through the **Foreign
Function & Memory (FFM) API** — no JNI, no `sun.misc.Unsafe`, no hand-written C
(JDK 25 is the first LTS with stable `java.lang.foreign`).

It leans into two things FFM makes natural:

- **Dictionary compression**, trained straight from your own data — the big win on
  small, repetitive records (logs, market-data ticks, JSON/Avro rows, FIX messages).
- A **zero-copy `MemorySegment` API** — compress/decompress off-heap buffers (an
  mmap'd slice in, an arena buffer out) with no heap copy and no per-call allocation.

## Quickstart

One-shot round-trip with `byte[]` — the convenient path:

```java
import io.github.dfa1.zstd.Zstd;

byte[] data = ...;
byte[] frame = Zstd.compress(data);        // or Zstd.compress(data, new ZstdCompressionLevel(19))
byte[] back  = Zstd.decompress(frame);     // size read from the frame header
```

**Dictionary** — train on a sample of your records, then compress each one against
the dictionary (huge ratio gains on small, similar messages):

```java
import io.github.dfa1.zstd.*;
import java.util.List;

List<byte[]> samples = ...;                       // representative records
ZstdDictionary dict = ZstdDictionary.train(samples, ZstdByteSize.ofKiB(8));

byte[] message = ...;
try (ZstdCompressContext cctx = new ZstdCompressContext();
     ZstdDecompressContext dctx = new ZstdDecompressContext()) {
    byte[] frame = cctx.compress(message, dict);
    byte[] back  = dctx.decompress(frame, message.length, dict);
}
```

**Zero-copy** — off-heap in, off-heap out, no `byte[]`, no per-call allocation:

```java
import io.github.dfa1.zstd.*;
import java.lang.foreign.*;

try (Arena arena = Arena.ofConfined();
     ZstdCompressContext cctx = new ZstdCompressContext();
     ZstdDecompressContext dctx = new ZstdDecompressContext()) {

    MemorySegment src     = ...;                       // e.g. an mmap'd file slice
    MemorySegment frame   = cctx.compress(arena, src); // off-heap → off-heap
    MemorySegment restored = dctx.decompress(arena, frame);
}
```

Run with `--enable-native-access=ALL-UNNAMED`. Full walkthrough in the
[tutorial](docs/tutorial.md); hot-path and dictionary recipes in the
[how-to guides](docs/how-to.md).

## Performance

Microbenchmarks against the common JVM zstd options (JMH; Apple M5, JDK 25, all
linking the same zstd 1.5.7). Full methodology and tables in
[docs/benchmarks.md](docs/benchmarks.md) — including the honest ties.

**Best vs best** — our zero-copy `MemorySegment` path vs **zstd-jni's own**
zero-copy direct-`ByteBuffer` path (golden-corpus fixtures, publication-grade run):

| operation (payload) | zstd-ffm `MemorySegment` | zstd-jni `ByteBuffer` | edge |
|---|---:|---:|---:|
| compress `http` (1.2 KiB) | **353.6** | 322.1 | +9.8% |
| decompress `http` | **922.7** | 750.8 | +22.9% |
| decompress `large-literal` (200 KiB) | 56.1 | 55.6 | tie |

*(throughput, ops/ms, higher is better; allocation is **~0 B/op on both** — both genuinely zero-copy)*

The edge is FFM's lower per-call overhead — **largest on small payloads**,
converging to a tie when codec/bandwidth dominates. Against the *convenient*
`byte[]` / JNI APIs (which allocate the output every call), the segment path is
additionally **allocation-free**: flat ~0 B/op at any size vs MB/op that scales
with the payload — no GC pressure on the hot path.

## Install

The `zstd` jar is pure Java and ships no `libzstd` — you always pair it with a
native artifact. Two ways:

**1. Everything, all supported platforms** — one dependency on `zstd-platform`, an
empty jar that transitively pulls the bindings plus all six natives (~3.8 MB). Zero
choices; the build runs on any supported OS/arch.

```xml
<dependency>
  <groupId>io.github.dfa1.zstd</groupId>
  <artifactId>zstd-platform</artifactId>
  <version>0.12</version>
</dependency>
```

**2. Leaner, one platform** — import `zstd-bom` to pin versions, then take `zstd`
plus only the `zstd-native-<classifier>` you target.

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.dfa1.zstd</groupId>
      <artifactId>zstd-bom</artifactId>
      <version>0.12</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>io.github.dfa1.zstd</groupId>
    <artifactId>zstd</artifactId>
  </dependency>
  <dependency>
    <groupId>io.github.dfa1.zstd</groupId>
    <artifactId>zstd-native-osx-aarch64</artifactId>
    <scope>runtime</scope>
  </dependency>
</dependencies>
```

Classifiers: `osx-aarch64`, `osx-x86_64`, `linux-x86_64`, `linux-aarch64`,
`windows-x86_64`, `windows-aarch64` — each verified on real hardware by the
[release smoke matrix](.github/workflows/release-smoke.yml). Gradle and more
detail in the [tutorial](docs/tutorial.md). Requires JDK 25+ and
`--enable-native-access=ALL-UNNAMED` at runtime. Building from source is for
contributors — see the [reference](docs/reference.md).

## Documentation

The docs follow the [Diátaxis](https://diataxis.fr) framework:

| | Purpose | Start here |
|---|---|---|
| **[Tutorial](docs/tutorial.md)** | Learning by doing | Clean checkout → first round-trip |
| **[How-to guides](docs/how-to.md)** | Solving a specific task | Hot paths, dictionaries, zero-copy, self-built lib |
| **[Reference](docs/reference.md)** | Looking up facts | Platforms, API surface, symbol coverage, build |
| **[Explanation](docs/explanation.md)** | Understanding the why | Why FFM + Zig, when zero-copy pays, benchmarks |

Architecture decisions are recorded as [ADRs](adr/ADR.md) (MADR 3.0) — the
foundational choices and their trade-offs, one file per decision.

## License

[BSD 3-Clause](LICENSE) — the same primary license as zstd, which is bundled
under its BSD terms (zstd is dual BSD / GPLv2, © Meta Platforms, Inc.).

---

> **AI-assisted development:** This project uses Claude Code for implementation —
> C header mapping, test generation, docs. Architecture, API design, and all
> decisions are human-driven.
