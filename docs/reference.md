# Reference

## Supported platforms

The library — `io.github.dfa1.zstd:zstd` — ships as a pure-Java module plus one
native artifact per platform:

| OS      | aarch64 | x86_64 |
|---------|:-------:|:------:|
| macOS   |   ✅    |   ✅   |
| Linux   |   ✅    |   ✅   |
| Windows |   ✅    |   ✅   |

## How objects are created

Two rules cover every public type; no public type has a public constructor.

- **Resources come from `Zstd.newX(...)` and must be closed.** Contexts, digested
  dictionaries, and streams own native memory. Create them only through the
  `Zstd.new*` factories below, and close them — ideally with try-with-resources.
- **Values come from `X.of(...)`.** Levels, sizes, window logs, versions, ids,
  and the RFC 9842 headers are immutable, validated once in `of(...)` (or
  `parse(...)` for header text), and need no cleanup.

Result types — `ZstdFrameHeader`, `ZstdFrameProgression`, `ZstdStreamResult`,
`ZstdSkippableContent`, `Rfc9842Negotiation` — are only ever
returned by the library.

## API surface

### `Zstd` — the entry point

| Group | Methods |
|---|---|
| One-shot | `Zstd.compress(byte[])`, `Zstd.compress(byte[], ZstdCompressionLevel)`, `Zstd.decompress(byte[])`, `Zstd.decompress(byte[], ZstdByteSize)` |
| Probes and sizing | `Zstd.decompressedSize(MemorySegment)`, `Zstd.dictId(byte[])` / `Zstd.dictId(MemorySegment)` (id of raw dictionary bytes), `Zstd.compressBound(ZstdByteSize)` |
| Memory estimates | `Zstd.estimateCompressContextSize(ZstdCompressionLevel)`, `Zstd.estimateDecompressContextSize()`, `Zstd.estimateCompressDictSize(ZstdByteSize, ZstdCompressionLevel)`, `Zstd.estimateDecompressDictSize(ZstdByteSize)` |
| Version | `Zstd.version()` → `ZstdVersion` |
| Contexts | `Zstd.newCompressContext()`, `Zstd.newDecompressContext()` |
| Digested dictionaries | `Zstd.newCompressDictionary(ZstdDictionary)`, `Zstd.newCompressDictionary(ZstdDictionary, ZstdCompressionLevel)`, `Zstd.newCompressDictionary(MemorySegment)`, `Zstd.newCompressDictionary(MemorySegment, ZstdCompressionLevel)`, `Zstd.newDecompressDictionary(ZstdDictionary)`, `Zstd.newDecompressDictionary(MemorySegment)` |
| Segment streams | `Zstd.newCompressStream()`, `Zstd.newCompressStream(ZstdDictionary)`, `Zstd.newDecompressStream()`, `Zstd.newDecompressStream(ZstdDictionary)` |
| `java.io` streams | `Zstd.newOutputStream(OutputStream)`, `Zstd.newOutputStream(OutputStream, ZstdDictionary)`, `Zstd.newPledgedOutputStream(OutputStream, ZstdByteSize)` (stamps the content size), `Zstd.newInputStream(InputStream)`, `Zstd.newInputStream(InputStream, ZstdDictionary)`; set the level on the stream with `level(...)` |

The `MemorySegment` overloads of the dictionary factories take native segments
only (heap segments are rejected with `IllegalArgumentException`).

### Contexts

| Type | Methods |
|---|---|
| `ZstdCompressContext` | configure: every `ZstdCompressSettings` setter (`level`, `windowLog`, `workers`, `checksum`, `strategy`, …), `ZstdCompressContext.reset(ZstdResetDirective)`; dictionaries: `ZstdCompressContext.loadDictionary(ZstdDictionary)` / `loadDictionary(MemorySegment)`, `ZstdCompressContext.refDictionary(ZstdCompressDictionary)`, `ZstdCompressContext.refPrefix(MemorySegment)`; compress: `compress(byte[])`, `compress(byte[], ZstdDictionary)`, `compress(byte[], ZstdCompressDictionary)`, `compress(MemorySegment dst, MemorySegment src)` (+ `ZstdCompressDictionary`), `compress(Arena, MemorySegment)` (+ `ZstdCompressDictionary`); `ZstdCompressContext.sizeOf()` |
| `ZstdDecompressContext` | configure: every `ZstdDecompressSettings` setter (`ZstdDecompressContext.windowLogMax(int)`, `ZstdDecompressContext.ignoreChecksum(boolean)`, `ZstdDecompressContext.magicless(boolean)`), `ZstdDecompressContext.reset(ZstdResetDirective)`; dictionaries: `ZstdDecompressContext.loadDictionary(ZstdDictionary)` / `loadDictionary(MemorySegment)`, `ZstdDecompressContext.refDictionary(ZstdDecompressDictionary)`, `ZstdDecompressContext.refPrefix(MemorySegment)`; decompress: `decompress(byte[])`, `decompress(byte[], ZstdByteSize)`, `decompress(byte[], ZstdByteSize, ZstdDictionary)`, `decompress(byte[], ZstdByteSize, ZstdDecompressDictionary)`, `decompress(MemorySegment dst, MemorySegment src)` (+ `ZstdDecompressDictionary`), `decompress(Arena, MemorySegment)` (+ `ZstdDecompressDictionary`); `ZstdDecompressContext.sizeOf()` |

The configuring methods return the context, for chaining. The `byte[]`
overloads copy through a native scratch buffer; the `MemorySegment` overloads
take native segments only and add no boundary copy.

### Dictionaries

| Type | Methods |
|---|---|
| `ZstdDictionary` | immutable dictionary bytes. Build: `ZstdDictionary.of(byte[])`, `ZstdDictionary.train(List, ZstdByteSize)`, `ZstdDictionary.trainCover(List, ZstdByteSize)` (+ `ZstdCompressionLevel`), `ZstdDictionary.trainFastCover(List, ZstdByteSize)` (+ `ZstdCompressionLevel`), `ZstdDictionary.finalizeFrom(byte[], List, ZstdByteSize, ZstdCompressionLevel)`. Query: `ZstdDictionary.id()`, `ZstdDictionary.size()`, `ZstdDictionary.headerSize()`, `ZstdDictionary.toByteArray()` |
| `ZstdCompressDictionary` | digested for compression at a fixed level: `ZstdCompressDictionary.level()`, `ZstdCompressDictionary.id()`, `ZstdCompressDictionary.sizeOf()` |
| `ZstdDecompressDictionary` | digested for decompression: `ZstdDecompressDictionary.id()`, `ZstdDecompressDictionary.sizeOf()` |

### Streams

| Type | Methods |
|---|---|
| `ZstdOutputStream` | a `java.io.OutputStream` that compresses into the stream it wraps: every `ZstdCompressSettings` setter, `ZstdOutputStream.write(byte[], int, int)` / `write(int)`, `ZstdOutputStream.flush()`, `ZstdOutputStream.close()` (finishes the frame) |
| `ZstdInputStream` | a `java.io.InputStream` that decompresses the stream it wraps: `ZstdInputStream.windowLogMax(int)`, `ZstdInputStream.ignoreChecksum(boolean)`, `ZstdInputStream.magicless(boolean)`, `ZstdInputStream.read(byte[], int, int)` / `read()`, `ZstdInputStream.close()` |
| `ZstdCompressStream` | zero-copy segment streaming compressor: `ZstdCompressStream.compress(MemorySegment, MemorySegment, ZstdEndDirective)`, every `ZstdCompressSettings` setter, `ZstdCompressStream.progress()`, `ZstdCompressStream.sizeOf()` |
| `ZstdDecompressStream` | zero-copy segment streaming decompressor: `ZstdDecompressStream.windowLogMax(int)`, `ZstdDecompressStream.ignoreChecksum(boolean)`, `ZstdDecompressStream.magicless(boolean)`, `ZstdDecompressStream.decompress(MemorySegment, MemorySegment)`, `ZstdDecompressStream.sizeOf()` |
| `ZstdEndDirective` | `CONTINUE`, `FLUSH`, `END` — what a `ZstdCompressStream.compress` step does with the input |
| `ZstdStreamResult` | one streaming step: `ZstdStreamResult.bytesConsumed()`, `ZstdStreamResult.bytesProduced()`, `ZstdStreamResult.remaining()`, `ZstdStreamResult.isComplete()` |
| `ZstdFrameProgression` | `ZstdCompressStream.progress()` snapshot: `ZstdFrameProgression.ingested()`, `ZstdFrameProgression.consumed()`, `ZstdFrameProgression.produced()`, `ZstdFrameProgression.flushed()`, `ZstdFrameProgression.currentJobId()`, `ZstdFrameProgression.activeWorkers()` |

### Frames

| Type | Methods |
|---|---|
| `ZstdFrame` | each has a `byte[]` and a `MemorySegment` overload: `ZstdFrame.isZstdFrame`, `ZstdFrame.isSkippableFrame`, `ZstdFrame.header`, `ZstdFrame.headerSize`, `ZstdFrame.compressedSize`, `ZstdFrame.decompressedSize`, `ZstdFrame.decompressedBound`, `ZstdFrame.decompressionMargin`, `ZstdFrame.dictId`; plus `ZstdFrame.writeSkippableFrame(byte[], ZstdMagicVariant)` and `ZstdFrame.readSkippableFrame(byte[])` |
| `ZstdFrameHeader` | `ZstdFrameHeader.contentSize()` / `ZstdFrameHeader.windowSize()` (`Optional<ZstdByteSize>`), `ZstdFrameHeader.blockSizeMax()`, `ZstdFrameHeader.frameType()`, `ZstdFrameHeader.headerSize()`, `ZstdFrameHeader.dictId()`, `ZstdFrameHeader.hasChecksum()` |
| `ZstdFrameType` | `STANDARD`, `SKIPPABLE` |
| `ZstdSkippableContent` | `ZstdSkippableContent.content()` (a copy), `ZstdSkippableContent.magicVariant()` |

### Parameters

| Type | Methods |
|---|---|
| `ZstdCompressSettings` | the compression setters shared by `ZstdCompressContext`, `ZstdCompressStream` and `ZstdOutputStream`: one setter per parameter: `level`, `windowLog`, `hashLog`, `chainLog`, `searchLog`, `minMatch`, `targetLength`, `strategy`, `targetCBlockSize`, `longDistanceMatching`, `ldmHashLog`, `ldmMinMatch`, `ldmBucketSizeLog`, `ldmHashRateLog`, `contentSize`, `checksum`, `dictId`, `magicless`, `workers`, `jobSize`, `overlapLog` |
| `ZstdDecompressSettings` | the decompression setters shared by `ZstdDecompressContext`, `ZstdDecompressStream` and `ZstdInputStream`: `windowLogMax`, `ignoreChecksum`, `magicless` |
| `ZstdStrategy` | the match-finding strategies, `FAST` … `BTULTRA2` |
| `ZstdResetDirective` | `SESSION_ONLY`, `PARAMETERS`, `SESSION_AND_PARAMETERS` — what `reset` clears |

### Value types

| Type | Create | Read |
|---|---|---|
| `ZstdCompressionLevel` | `ZstdCompressionLevel.of(int)`; `DEFAULT`, `FASTEST`, `MAX` | `ZstdCompressionLevel.value()` |
| `ZstdByteSize` | `ZstdByteSize.of(long)`, `ZstdByteSize.ofKiB(long)`, `ZstdByteSize.ofMiB(long)` | `ZstdByteSize.value()`, `ZstdByteSize.toArraySize()` |
| `ZstdMagicVariant` | `ZstdMagicVariant.of(int)` (`0..15`) | `ZstdMagicVariant.value()` |
| `ZstdVersion` | `ZstdVersion.of(int, int, int)`; also `Zstd.version()` | `ZstdVersion.major()`, `ZstdVersion.minor()`, `ZstdVersion.patch()`, `ZstdVersion.number()`; `Comparable` |
| `ZstdDictionaryId` | `ZstdDictionaryId.of(int)`; `NONE` | `ZstdDictionaryId.raw()`, `ZstdDictionaryId.value()` (unsigned), `ZstdDictionaryId.isPresent()` |

`of(...)` throws `IllegalArgumentException` for an out-of-range value. Value
types compare by value (`equals`/`hashCode`) and print as `Name[field=value]`
(`ZstdVersion` prints `x.y.z`).

### Errors

| Type | Methods |
|---|---|
| `ZstdException` | unchecked; thrown for every zstd error: `ZstdException.code()` → `ZstdErrorCode` |
| `ZstdErrorCode` | zstd's error enum: `ZstdErrorCode.value()`, `ZstdErrorCode.description()` |

### Closing resources

Every native-backed type (contexts, segment streams, digested dictionaries) is
`AutoCloseable`; its only lifecycle method is `close()`. Their shared base
classes are internal to the library.

## Lifecycle and thread safety

- **Close is idempotent.** Calling `close()` again on any resource is a no-op.
- **Use after close fails fast.** Contexts, segment streams, and digested
  dictionaries throw `IllegalStateException`. The `java.io` streams throw
  `IOException` from `read`/`write`/`flush`, and their setters throw
  `IllegalStateException`.
- **The `java.io` streams own what they wrap.** `ZstdOutputStream.close()`
  finishes the frame and then closes the wrapped `OutputStream`;
  `ZstdInputStream.close()` closes the wrapped `InputStream`.
- **Digested dictionaries are reference-counted.** A context that attaches one
  with `refDictionary` takes its own reference, so closing the dictionary while
  a context still uses it is safe: the native dictionary is freed when the last
  holder lets go. The closed handle itself is unusable — passing it to
  `compress`, `decompress`, or another `refDictionary` throws
  `IllegalStateException`. Replacing, clearing (`refDictionary(null)`), a
  parameter `reset`, or closing the context releases its reference.
- **Thread safety.**

  | Thread-safe | Not thread-safe — confine to one thread or pool |
  |---|---|
  | the static methods of `Zstd` and `ZstdFrame`; `ZstdDictionary`; `ZstdCompressDictionary` / `ZstdDecompressDictionary` (read-only once built, share freely); all value and result types (immutable) | `ZstdCompressContext`, `ZstdDecompressContext`, `ZstdCompressStream`, `ZstdDecompressStream`, `ZstdOutputStream`, `ZstdInputStream` |

- A compress context or stream with `workers(n)` above zero
  keeps worker threads that only `close()` releases (not `reset`), so give it a
  dedicated owner rather than a long-lived pool.

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

`io.github.dfa1.zstd:zstd-rfc9842` — a separate module, on top of `zstd`
(dependency snippet in the [tutorial](tutorial.md#1-add-the-dependency)):

| Type | Methods |
|---|---|
| `Rfc9842Frame` | the `dcz` wire format (§5): a 40-byte header (skippable-frame magic + SHA-256 dictionary hash) around a zstd frame. `Rfc9842Frame.wrap(byte[], AvailableDictionaryHeader)` / `wrap(byte[], ZstdDictionary)` and `Rfc9842Frame.unwrap(byte[], AvailableDictionaryHeader)` / `unwrap(byte[], ZstdDictionary)`; segment overloads `wrap(MemorySegment dst, MemorySegment frame, …)` → bytes written, `wrap(Arena, MemorySegment, …)` → new segment, and `unwrap(MemorySegment, …)` → a slice of the input (each taking an `AvailableDictionaryHeader` or a `ZstdDictionary`). `unwrap` verifies the hash |
| `UseAsDictionaryHeader` | the `Use-As-Dictionary` response header (§2.1): `UseAsDictionaryHeader.of(String)` / `of(String, String)` / `of(String, String, String)`, `UseAsDictionaryHeader.parse(String)`, `UseAsDictionaryHeader.match()`, `UseAsDictionaryHeader.id()`, `UseAsDictionaryHeader.type()`, `UseAsDictionaryHeader.matchesPath(String)` (literal text plus `*` wildcards), `UseAsDictionaryHeader.toHeaderValue()` |
| `AvailableDictionaryHeader` | the `Available-Dictionary` request header (§2.2), a dictionary's SHA-256 hash — also the hash embedded in a `dcz` header: `AvailableDictionaryHeader.of(ZstdDictionary)`, `AvailableDictionaryHeader.parse(String)`, `AvailableDictionaryHeader.toHeaderValue()` |
| `DictionaryIdHeader` | the `Dictionary-ID` header (§2.3), the id a client echoes back: `DictionaryIdHeader.of(String)`, `DictionaryIdHeader.parse(String)`, `DictionaryIdHeader.value()`, `DictionaryIdHeader.toHeaderValue()` |
| `Rfc9842Negotiation` | everything a client needs after fetching a dictionary once: `Rfc9842Negotiation.from(byte[], String)`, then `Rfc9842Negotiation.dictionary()`, `Rfc9842Negotiation.useAsDictionary()`, `Rfc9842Negotiation.availableDictionary()`, `Rfc9842Negotiation.dictionaryId()` (`Optional`, empty when the server assigned no id) |
| `Rfc9842Exception` | unchecked; thrown for a malformed header value or a `dcz` header that doesn't verify |

Each header type exposes its HTTP header name as a `HTTP_HEADER` constant
(e.g. `AvailableDictionaryHeader.HTTP_HEADER`). All of these are immutable and
thread-safe.

The module is sans-io — no HTTP client or server
([why](explanation.md#why-rfc-9842-is-sans-io)). See the
[how-to guide](how-to.md#negotiate-and-use-an-rfc-9842-dictionary) to wire it
into an actual client/server.

## Building from source

Building from source is for contributors; see [contributing.md](contributing.md).
