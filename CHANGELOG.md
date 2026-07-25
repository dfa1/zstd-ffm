# Changelog

All notable changes to this project are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/); versions are released as `v*`
git tags, which trigger publication to Maven Central.

## [Unreleased]

### Added
- `ZstdDecompressContext.decompress(byte[])` — decompresses a trusted frame using
  the decompressed size stored in its header, mirroring the static
  `Zstd.decompress(byte[])`. For untrusted input keep the bounded
  `decompress(byte[], ZstdByteSize)` overload, now documented as the safe entry
  point.
- `ZstdWindowLog` and `ZstdMagicVariant` value types, validated at construction —
  the window log against the linked libzstd's accepted range (with `0` /
  `ZstdWindowLog.AUTO` for "library chooses"), the magic variant to `0..15`.

### Changed
- **Breaking:** the compression-level bound queries `Zstd.maxCompressionLevel()`,
  `minCompressionLevel()`, and `defaultCompressionLevel()` are no longer public.
  The naked-`int` queries now live internally on `ZstdCompressionLevel`; get the
  levels as value objects via the `ZstdCompressionLevel.MAX`/`FASTEST`/`DEFAULT`
  constants (call `.value()` for the raw `int` when handing one to a native or
  third-party API that takes `int`).
- **Breaking:** renamed `ZstdByteSize.toIntExact()` to `toArraySize()`, which now
  throws `ZstdException` (was `ArithmeticException`) when the size exceeds the
  maximum array length — the narrowing happens at the API boundary, so callers
  no longer catch a raw `ArithmeticException`.
- **Breaking:** `ZstdDecompressContext.decompress` now takes a `ZstdByteSize`
  bound instead of a naked `int` (all three overloads: plain, `+ ZstdDictionary`,
  `+ ZstdDecompressDictionary`), completing the `ZstdByteSize` migration. Wrap the
  bound in `new ZstdByteSize(n)`.
- **Breaking:** the frame-size probes `ZstdFrame.compressedSize`, `headerSize`,
  and `decompressionMargin` now return `ZstdByteSize` instead of `long`, and
  `ZstdDictionary.size()`/`headerSize()` return `ZstdByteSize` instead of `int`,
  matching the other size accessors — call `.value()` for the raw number. The
  zero-copy segment `compress`/`decompress` overloads still return a raw `long`
  by design (the count feeds straight into `MemorySegment.asSlice`).
- **Breaking:** `ZstdCompressContext.windowLog` and
  `ZstdDecompressContext.windowLogMax` now take a `ZstdWindowLog`, and
  `ZstdFrame.writeSkippableFrame` / `ZstdSkippableContent.magicVariant()` now use
  `ZstdMagicVariant`. Wrap the raw `int` (`new ZstdWindowLog(n)` /
  `new ZstdMagicVariant(n)`), or use `ZstdWindowLog.AUTO`.

## [0.11] - 2026-07-24

### Changed
- **Breaking:** every public API that took or returned a naked `int`/`long` byte
  size or count now takes/returns a `ZstdByteSize` value type, which rejects
  negative values at construction (throwing `IllegalArgumentException`) — once
  you hold a `ZstdByteSize` it is guaranteed valid, no more wondering whether a
  raw `long` from the library still needs checking. Sizes that must fit a
  `byte[]` narrow via `ZstdByteSize.toIntExact()` (throwing
  `ArithmeticException` above `Integer.MAX_VALUE`), matching the JDK's own
  `Math.toIntExact`; native and streaming totals pass through as `long`. A
  frame-declared content size that is a zstd sentinel, or otherwise invalid
  (including the unsigned range above `Long.MAX_VALUE`, read as a negative
  `long`), fails fast with `ZstdException` via
  `ZstdByteSize.fromFrameContentSize(long)` rather than reaching the
  constructor's generic negative-value guard; `ZstdFrameHeader.contentSize()`
  is now `Optional<ZstdByteSize>` (was `OptionalLong`) via the sibling
  `ZstdByteSize.fromFrameHeaderContentSize(long)`, which treats any negative
  reading — not just the sentinel — as absent, since it can only mean an
  unrepresentable declared size. Affects
  `Zstd.decompress(byte[], …)`, `Zstd.compressBound`,
  `Zstd.estimateCompressContextSize`/`estimateDecompressContextSize`/
  `estimateCompressDictSize`/`estimateDecompressDictSize`,
  `Zstd.decompressedSize(MemorySegment)`,
  `ZstdDictionary.train`/`trainCover`/`trainFastCover`/`finalizeFrom`,
  `ZstdOutputStream.withPledgedSize`, `ZstdFrame.decompressedSize`/
  `decompressedBound`, `ZstdFrameHeader.contentSize`, and `sizeOf()` on
  `ZstdCompressContext`/`ZstdDecompressContext`/`ZstdCompressStream`/
  `ZstdDecompressStream`/`ZstdCompressDictionary`/`ZstdDecompressDictionary`.
  Use `new ZstdByteSize(n)`, or `ZstdByteSize.ofKiB(n)`/`ofMiB(n)` for a size
  expressed in KiB/MiB.
  ([#96](https://github.com/dfa1/zstd-java/issues/96))
- **Breaking:** every public API that took a raw `int` compression level now
  takes a `ZstdCompressionLevel` value type, which validates the level against
  the linked libzstd's accepted range at construction (throwing
  `IllegalArgumentException`) rather than deferring to native clamping/errors.
  Affects `Zstd.compress(byte[], …)`, `Zstd.estimateCompressContextSize`,
  `Zstd.estimateCompressDictSize`, `ZstdCompressContext.level`,
  `ZstdCompressStream`, `ZstdOutputStream`, `ZstdCompressDictionary`,
  `ZstdDictionary.compressDict`/`trainCover`/`trainFastCover`/`finalizeFrom`.
  Use `new ZstdCompressionLevel(19)` or the `DEFAULT`/`FASTEST`/`MAX` constants.
  The `Zstd.min/max/defaultCompressionLevel()` bound queries still return `int`.
  ([#93](https://github.com/dfa1/zstd-java/issues/93))

## [0.10] - 2026-07-18

### Added
- Native builds now compile with `ZSTD_MULTITHREAD`, making
  `ZstdCompressParameter.NB_WORKERS` (plus `JOB_SIZE` and `OVERLAP_LOG`)
  functional instead of a silent no-op. Workers engage above zstd's 512 KiB
  job-size minimum; multithreaded frames are format-valid but not
  byte-identical to single-threaded output. A context that compressed with
  workers holds its native worker threads until `close()` — `reset()` does
  not release them, so give such contexts a dedicated owner (never pool
  them). See [ADR 0015](adr/0015-enable-zstd-multithread.md), which
  supersedes ADR 0014.
  ([#80](https://github.com/dfa1/zstd-java/issues/80))

## [0.9] - 2026-07-18

### Added
- Native builds now decode legacy zstd frame formats v0.4-v0.7
  (`ZSTD_LEGACY_SUPPORT=4`, matching zstd-jni's default). v0.1-v0.3 stay
  unsupported — they predate zstd's 1.0 stabilization and are essentially
  never seen in practice. Verified against a real fixture of five concatenated
  legacy frames extracted from zstd's own test suite. ([#73](https://github.com/dfa1/zstd-java/pull/73))

### Changed
- `linux-x86_64`/`osx-x86_64`/`windows-x86_64` native builds now include
  zstd's hand-written BMI2 Huffman-decode assembly (previously disabled). It
  is a no-op on non-x86_64 targets and only activates via zstd's own runtime
  CPU detection; benchmarked as throughput-neutral on this project's
  synthetic workload but carries no measured downside either.
  ([#71](https://github.com/dfa1/zstd-java/pull/71))
- `aarch64` native builds now target an ARMv8-A + CRC baseline
  (`-mcpu=generic+crc`, zig's spelling of `-march=armv8-a+crc`), instead of
  the fully generic baseline. Measured +6.9% compress / +12-14% decompress
  throughput on Apple Silicon. ([#71](https://github.com/dfa1/zstd-java/pull/71))

### Security
- `linux-x86_64`/`linux-aarch64` native builds now link with full RELRO and
  immediate binding (`-Wl,-z,relro,-z,now`), closing off the classic
  GOT-overwrite exploit primitive. Verified with `llvm-readelf`.
  ([#71](https://github.com/dfa1/zstd-java/pull/71))
- `windows-x86_64`/`windows-aarch64` native builds now export only zstd's
  public API (`ZSTD_*`/`ZDICT_*`) from the DLL, via `-DZSTD_DLL_EXPORT=1`
  (the PE analogue of the `-fvisibility=hidden` surface already used on
  ELF/Mach-O), instead of dumping every internal symbol
  (`FSE_*`/`HUF_*`/`COVER_*`/...) into the export table via
  `--export-all-symbols`. Cut the windows-x86_64 export table from 576 to
  185 symbols. ([#79](https://github.com/dfa1/zstd-java/pull/79))

### Fixed
- Building the native library from source on Windows was silently broken:
  Maven's exec plugin tried to execute `build-zstd.sh` directly, which only
  works via a shebang on macOS/Linux. Windows builds now invoke it through
  `bash` explicitly. A second latent bug this surfaced — unrecognized/Windows
  host OS detection crashed the build script under `set -u` — is fixed
  alongside it. ([#75](https://github.com/dfa1/zstd-java/pull/75))
- Native library compilation now runs every translation unit through a real
  parallel work queue (`xargs -P`) instead of a fixed-size batch-then-wait
  loop, and aborts immediately if any `zig cc` invocation fails. Previously a
  failed compile under `&`/`wait` was invisible to `set -e` — it could
  silently produce no `.o` and only surface later as a cryptic link error, or
  worse, a link that "succeeded" against a stale `.o` left over from a
  previous run. ([#78](https://github.com/dfa1/zstd-java/pull/78))

Investigated and **rejected** as part of the same effort (see
[#70](https://github.com/dfa1/zstd-java/issues/70) for full benchmark data):
LTO (real compress regression on x86_64, unsupported on macOS entirely — zig's
Mach-O linker has no LTO support, tracked in
[#77](https://github.com/dfa1/zstd-java/issues/77)) and an `x86-64-v3`
baseline (mixed result, hurts compress more than it helps decompress). Both
would have traded away this project's existing compress-side edge over
zstd-jni for a smaller decompress-side gain.

## [0.8] - 2026-07-12

### Added
- `module-info.java`: `zstd` now ships as a named JPMS module
  (`module io.github.dfa1.zstd`), exporting the single public API package.
  Module-path consumers grant `--enable-native-access=io.github.dfa1.zstd`
  instead of `ALL-UNNAMED`; classpath consumers are unaffected. See
  [ADR 0011](adr/0011-jpms-module-descriptor.md).

## [0.7] - 2026-06-28

### Changed
- **Breaking:** renamed public types to spell out abbreviations, matching the
  `Zstd<Compress|Decompress><Stream|Parameter>` family and zstd's own prose
  ("compression context", "dictionary"): `ZstdCompressCtx` → `ZstdCompressContext`,
  `ZstdDecompressCtx` → `ZstdDecompressContext`, `ZstdCompressDict` →
  `ZstdCompressDictionary`, `ZstdDecompressDict` → `ZstdDecompressDictionary`.

## [0.6] - 2026-06-27

### Added
- `ZstdCompressCtx.refPrefix(MemorySegment)` / `ZstdDecompressCtx.refPrefix(...)`
  — reference native content as a single-use prefix (raw-content dictionary) for
  the next frame only: the building block for delta compression (compress a new
  version against a similar previous one). The prefix is referenced, not copied
  or digested, and writes no dictionary ID; the decompressor must reference the
  same prefix to decode. Binds `ZSTD_CCtx_refPrefix` / `ZSTD_DCtx_refPrefix`.
  Segment-only by design — heap callers that need a copy should use
  `loadDictionary` instead.
- `Zstd.dictId(byte[])` / `Zstd.dictId(MemorySegment)` — read the dictionary id
  stamped in raw dictionary bytes without wrapping them in a `ZstdDictionary`.
  Binds `ZSTD_getDictID_fromDict`.
- `ZstdDictionaryId` value type — a `record` wrapping the 32-bit dictionary id
  with an unsigned `value()`, `isPresent()`, and the `NONE` sentinel for "no id".
- `ZstdFrame.decompressedSize(byte[])` / `ZstdFrame.decompressedSize(MemorySegment)`
  — the exact combined decompressed size of all concatenated frames, summed from
  each frame header (throws if any frame does not record its size). Complements
  `decompressedBound` (upper bound). Binds `ZSTD_findDecompressedSize`.
- `ZstdFrame.headerSize(byte[])` / `ZstdFrame.headerSize(MemorySegment)` — the size
  of a frame's header computed from just its leading bytes (as few as 5), without a
  full parse. Binds `ZSTD_frameHeaderSize`.
- `ZstdFrame.decompressionMargin(byte[])` / `ZstdFrame.decompressionMargin(MemorySegment)`
  — the extra room needed to decompress a frame **in place** (output buffer overlaps
  the compressed input at its tail), sized `decompressedSize + margin`. Binds
  `ZSTD_decompressionMargin`.
- `ZstdDictionary.compressDict(int)` / `compressDict()` / `decompressDict()` —
  factories for digested dictionaries, e.g. `dict.compressDict(19)` instead of
  `new ZstdCompressDict(dict, 19)`. They signal that the result is `AutoCloseable`
  and are for sharing one digest across contexts via `refDictionary`; a single
  context should prefer the context-owned `loadDictionary`.

### Changed
- Every dictionary-id accessor now returns `ZstdDictionaryId` instead of `int`:
  `ZstdDictionary.id()`, `ZstdCompressDict.id()`, `ZstdDecompressDict.id()`,
  `ZstdFrame.dictId(...)`, and `ZstdFrameHeader.dictId()`. The `0` sentinel is now
  `ZstdDictionaryId.NONE`, and the id reads as unsigned via `value()`.
- `Zstd.decompress(byte[])` now throws `ZstdException` (instead of letting a raw
  `ArithmeticException` escape) when a frame declares a content size larger than a
  Java array can hold. The size comes from the untrusted frame header; use
  `decompress(byte[], int)` to bound output for untrusted input.

## [0.5]

### Added
- `ZstdCompressCtx.reset(ZstdResetDirective)` / `ZstdDecompressCtx.reset(...)` —
  recycle a context's native state between frames without freeing and recreating
  it. `SESSION_ONLY` keeps the level, parameters, and dictionary; `PARAMETERS` /
  `SESSION_AND_PARAMETERS` restore the defaults. Binds `ZSTD_CCtx_reset` /
  `ZSTD_DCtx_reset`.
  ([3dfd5b8](https://github.com/dfa1/zstd-java/commit/3dfd5b8))
- `ZstdCompressCtx.loadDictionary(...)` / `ZstdDecompressCtx.loadDictionary(...)`
  (a `ZstdDictionary` or a native `MemorySegment`) and `refDictionary(...)` (a
  pre-digested `ZstdCompressDict` / `ZstdDecompressDict`, attached by reference,
  no copy). A sticky dictionary on the context lets compression combine a
  dictionary with the advanced parameters (checksum, window log, long-distance
  matching) — impossible through the per-call `compress(src, dict)` overloads,
  which route the legacy dictionary path. A parameter `reset(...)` clears it.
  Binds `ZSTD_CCtx_loadDictionary` / `ZSTD_DCtx_loadDictionary` (now on contexts,
  not just streams), `ZSTD_CCtx_refCDict`, `ZSTD_DCtx_refDDict`.
  ([3dfd5b8](https://github.com/dfa1/zstd-java/commit/3dfd5b8))

### Changed
- `NativeLibrary.classifier()` now throws a clear `UnsatisfiedLinkError` naming
  the unsupported CPU arch instead of silently mapping it to x86_64 (which
  deferred failure to a cryptic `dlopen` error). Added an explicit `amd64`
  branch so Linux JVMs (which report `os.arch=amd64`) still resolve x86_64.
  ([ea1ac84](https://github.com/dfa1/zstd-java/commit/ea1ac84))

### Fixed
- Native JARs are much smaller. The ELF shared library is now stripped at link
  time (`-s`), dropping debug info (`libzstd.so` 4.0M -> ~650K), and the
  multi-MB `.pdb` debug database and `.lib` import library that lld emits next
  to the Windows `.dll` are no longer bundled (neither is needed at runtime).
  Net: linux-x86_64 native jar 1.2M -> 285K, windows-x86_64 1.2M -> 372K.
  ([ea1ac84](https://github.com/dfa1/zstd-java/commit/ea1ac84))

## [0.4]

### Added
- `Zstd.versionNumber()` — the linked zstd version as a single integer
  (`MAJOR * 10000 + MINOR * 100 + PATCH`, e.g. `10507` for `1.5.7`), for
  programmatic version checks alongside `version()`.

### Changed
- `ZstdSkippableContent` is now a true immutable value: it defensively copies its
  bytes on the way in and out, and compares by content
  (`equals` / `hashCode` / `toString` over the payload, not array identity).
- Public methods fail fast with a named `NullPointerException` on null `byte[]`,
  dictionary, or sample arguments, instead of an opaque failure deep in native
  code. Streams are documented as not thread-safe; digested dictionaries
  (`ZstdCompressDict` / `ZstdDecompressDict`) as immutable and safe to share.

### Fixed
- A streaming wrapper that failed partway through construction (e.g. an invalid
  parameter or dictionary) leaked the native context. The context is now freed
  on every constructor error path.

### Security
- The bundled library is extracted into a directory created owner-only
  (`rwx------`) atomically at creation, not just by default. The third-party
  `setup-zig` CI action is pinned to a full commit SHA.

## [0.3]

- [`zstd-platform` is now an empty convenience jar (not a `pom`)](https://github.com/dfa1/zstd-java/commit/ba5593a)
- [Bundled-only native loader; fix Sonar vulnerability + bug](https://github.com/dfa1/zstd-java/commit/8d0ea6a)
- [`ByteBuffer` interop + pledged-size zero-copy decode](https://github.com/dfa1/zstd-java/commit/8bfe272)

## [0.2]

- [`zstd-platform` aggregator; off-heap `MemorySegment` dictionary constructors](https://github.com/dfa1/zstd-java/releases/tag/v0.2)

## [0.1]

- [First release: JDK 25 FFM bindings for Zstandard 1.5.7, built with `zig cc`](https://github.com/dfa1/zstd-java/releases/tag/v0.1)
