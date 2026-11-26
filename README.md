# zstd-ffm

[![CI](https://github.com/dfa1/zstd-ffm/actions/workflows/ci.yml/badge.svg)](https://github.com/dfa1/zstd-ffm/actions/workflows/ci.yml)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=dfa1_zstd-java&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=dfa1_zstd-java)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=dfa1_zstd-java&metric=coverage)](https://sonarcloud.io/summary/new_code?id=dfa1_zstd-java)
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
import io.github.dfa1.zstd.ZstdCompressionLevel;

byte[] data = ...;
byte[] frame = Zstd.compress(data);        // or Zstd.compress(data, ZstdCompressionLevel.of(19))
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
try (ZstdCompressContext cctx = Zstd.newCompressContext();
     ZstdDecompressContext dctx = Zstd.newDecompressContext()) {
    byte[] frame = cctx.compress(message, dict);
    byte[] back  = dctx.decompress(frame, ZstdByteSize.of(message.length), dict);
}
```

**Zero-copy** — off-heap in, off-heap out, no `byte[]`, no per-call allocation:

```java
import io.github.dfa1.zstd.*;
import java.lang.foreign.*;

try (Arena arena = Arena.ofConfined();
     ZstdCompressContext cctx = Zstd.newCompressContext();
     ZstdDecompressContext dctx = Zstd.newDecompressContext()) {

    MemorySegment src     = ...;                       // e.g. an mmap'd file slice
    MemorySegment frame   = cctx.compress(arena, src); // off-heap → off-heap
    MemorySegment restored = dctx.decompress(arena, frame);
}
```

Run with `--enable-native-access=ALL-UNNAMED`. Full walkthrough in the
[tutorial](docs/tutorial.md); hot-path and dictionary recipes in the
[how-to guides](docs/how-to.md).

## Performance

Against zstd-jni's own zero-copy direct-`ByteBuffer` path, the `MemorySegment`
path is **up to ~23% faster on small payloads** (FFM's lower per-call overhead,
golden-corpus fixtures, JMH) and ties once codec work dominates; both allocate
~0 B/op. Methodology, every table, and the honest ties:
[docs/benchmarks.md](docs/benchmarks.md).

## Install

The simplest setup is one dependency on `zstd-platform`, which brings the
bindings plus every platform's native library:

```xml
<dependency>
  <groupId>io.github.dfa1.zstd</groupId>
  <artifactId>zstd-platform</artifactId>
  <version>0.17</version>
</dependency>
```

Requires JDK 25+ and `--enable-native-access=ALL-UNNAMED` at runtime. Gradle, the
leaner single-platform setup (BOM + one `zstd-native-<classifier>`), the
classifier list, and the optional `zstd-rfc9842` module are in the
[tutorial](docs/tutorial.md#1-add-the-dependency). Building from source is for
contributors — see [contributing](docs/contributing.md).

## RFC 9842 (Compression Dictionary Transport)

The `zstd-rfc9842` module implements [RFC 9842](https://www.rfc-editor.org/rfc/rfc9842)
on top of `zstd`: the `dcz` wire format plus the `Use-As-Dictionary`/
`Available-Dictionary`/`Dictionary-ID` header values, so a server and client
can agree on a shared dictionary and compress every response against it. It
has no HTTP client or server of its own
([why](docs/explanation.md#why-rfc-9842-is-sans-io)). See the
[how-to guide](docs/how-to.md#negotiate-and-use-an-rfc-9842-dictionary) to use
it, or the [reference](docs/reference.md#rfc-9842-compression-dictionary-transport)
for the type overview.

## Documentation

The docs follow the [Diátaxis](https://diataxis.fr) framework:

| | Purpose | Start here |
|---|---|---|
| **[Tutorial](docs/tutorial.md)** | Learning by doing | Install → first round-trip → zero-copy → streams |
| **[How-to guides](docs/how-to.md)** | Solving a specific task | Hot paths, dictionaries, zero-copy, RFC 9842, self-built lib |
| **[Reference](docs/reference.md)** | Looking up facts | Platforms, full API surface, lifecycle and thread safety |
| **[Explanation](docs/explanation.md)** | Understanding the why | Why FFM + Zig, when zero-copy pays, design choices |
| [Benchmarks](docs/benchmarks.md) | Reference data | JMH results vs zstd-jni and aircompressor, large files |
| [Supported symbols](docs/supported.md) | Reference data | Which zstd C symbols are bound, vs zstd-jni |
| [Contributing](docs/contributing.md) | Building from source | Prerequisites, clone and build |

Architecture decisions are recorded as [ADRs](adr/ADR.md) (MADR 3.0) — the
foundational choices and their trade-offs, one file per decision.

## License

[BSD 3-Clause](LICENSE) — the same primary license as zstd, which is bundled
under its BSD terms (zstd is dual BSD / GPLv2, © Meta Platforms, Inc.).

---

> **AI-assisted development:** This project uses Claude Code for implementation —
> C header mapping, test generation, docs. Architecture, API design, and all
> decisions are human-driven.
