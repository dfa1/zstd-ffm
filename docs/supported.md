# Supported symbols

Coverage of the zstd C API by the `io.github.dfa1.zstd` bindings. Signatures
and semantics follow the [official manual](https://facebook.github.io/zstd/doc/api_manual_latest.html).

- zstd version: **1.5.7** (vendored `third_party/zstd`, pinned to tag `v1.5.7`)
- Public symbols exported by `libzstd`: **185**
- Bound so far: **68** (~37%)

"Bound" means the symbol has a `MethodHandle` in `Bindings` and is reachable
through the public Java API. The rest are reachable from native code but not yet
surfaced. Many unbound symbols are deprecated, experimental, or static-buffer /
low-level variants that an idiomatic Java API does not need.

**27** of the unbound symbols are deprecated upstream (carry `ZSTD_DEPRECATED` in
`zstd.h`) and will never be bound — each is superseded by API already listed here.
In the per-area tables below they are flagged **ᵈ** in the Bound column; the same
list is recorded in `Bindings.java`. Notably this binds `ZSTD_getFrameContentSize`
rather than the deprecated `ZSTD_getDecompressedSize`.

> Regenerate the symbol list with
> `nm -gjU libzstd.dylib | sed 's/^_//' | grep -E '^(ZSTD|ZDICT)_'`.

## Coverage by area

| Area | Bound / Total | Notes |
|---|:---:|---|
| Core one-shot | 6 / 7 | compress/decompress/bound/levels — complete for practical use |
| Version | 2 / 2 | complete |
| Errors | 4 / 4 | complete: name, typed `ZstdErrorCode`, and `description()` |
| Reusable contexts | 6 / 8 | CCtx/DCtx create/free/compress/decompress |
| Dictionary — simple | 11 / 23 | raw + digested (CDict/DDict) + dict-id queries; `_advanced`/`_byReference`/`Begin` variants not bound |
| Dictionary training (ZDICT) | 8 / 12 | trainFromBuffer, cover/fastCover optimizers, finalizeDictionary, getDictHeaderSize |
| Streaming — compress | 3 / 22 | `ZstdOutputStream` (`byte[]`/`java.io`) and `ZstdCompressStream` (zero-copy `MemorySegment`) — both drive `compressStream2` + buffer sizes |
| Streaming — decompress | 3 / 15 | `ZstdInputStream` (`byte[]`/`java.io`) and `ZstdDecompressStream` (zero-copy `MemorySegment`) — both drive `decompressStream` + buffer sizes |
| Advanced parameters | 14 / 38 | all `ZSTD_cParameter` + `ZSTD_dParameter` via `ZstdCompressParameter`/`ZstdDecompressParameter`; `compress2`, `C/DCtx_setParameter`, `C/DCtx_reset`, `C/DCtx_loadDictionary`, `CCtx_refCDict`/`DCtx_refDDict`, `C/DCtx_refPrefix`, `c/dParam_getBounds`; multithreaded compression via `NB_WORKERS` (ADR 0015) is reachable from `ZstdCompressContext.parameter()` and from both streaming classes' `parameter()` (`ZstdCompressStream`, `ZstdOutputStream`). The `ZSTD_CCtx_params` bundle (the 10 unbound rows in this section's table below) is experimental (`ZSTDLIB_STATIC_API`, no stability guarantee — exported, but intentionally not surfaced) — see [ADR 0013](../adr/0013-binding-coverage-scope.md) |
| Frame inspection | 12 / 13 | `ZstdFrame` + getFrameProgression; `_advanced` not bound |
| Memory sizing | 9 / 14 | sizeof_C/DCtx, sizeof_C/DDict, estimate C/DCtx + C/DDict size, in-place decompression margin |
| Low-level block | 0 / 12 | expert block/continue API not bound |
| Sequences | 0 / 5 | sequence producer API not bound |
| Misc / experimental | 0 / 10 | static-buffer init, param helpers, `copy*` not bound |

## Bound symbols → Java

| Native | Java surface |
|---|---|
| `ZSTD_compress`, `ZSTD_decompress`, `ZSTD_compressBound` | `Zstd.compress` / `decompress` / `compressBound` |
| `ZSTD_maxCLevel`, `ZSTD_minCLevel`, `ZSTD_defaultCLevel` | `ZstdCompressionLevel.MAX` / `FASTEST` / `DEFAULT` |
| `ZSTD_versionNumber`, `ZSTD_versionString` | `Zstd.version` |
| `ZSTD_isError`, `ZSTD_getErrorName` | internal error mapping in `Zstd` |
| `ZSTD_getFrameContentSize` | `Zstd.decompress(byte[])`, `Zstd.decompressedSize` |
| `ZSTD_createCCtx`, `ZSTD_freeCCtx`, `ZSTD_compressCCtx` | `ZstdCompressContext` |
| `ZSTD_createDCtx`, `ZSTD_freeDCtx`, `ZSTD_decompressDCtx` | `ZstdDecompressContext` |
| `ZSTD_compress_usingDict` | `ZstdCompressContext.compress(byte[], ZstdDictionary)` |
| `ZSTD_decompress_usingDict` | `ZstdDecompressContext.decompress(byte[], int, ZstdDictionary)` |
| `ZSTD_createCDict`, `ZSTD_freeCDict`, `ZSTD_compress_usingCDict` | `ZstdCompressDictionary` |
| `ZSTD_createDDict`, `ZSTD_freeDDict`, `ZSTD_decompress_usingDDict` | `ZstdDecompressDictionary` |
| `ZDICT_trainFromBuffer` | `ZstdDictionary.train` |
| `ZDICT_getDictID` | `ZstdDictionary.id` |
| `ZDICT_isError`, `ZDICT_getErrorName` | internal error mapping in `ZstdDictionary` |
| `ZSTD_compressStream2`, `ZSTD_CStreamInSize`, `ZSTD_CStreamOutSize`, `ZSTD_CCtx_setParameter` | `ZstdOutputStream`, `ZstdCompressStream` |
| `ZSTD_decompressStream`, `ZSTD_DStreamInSize`, `ZSTD_DStreamOutSize` | `ZstdInputStream`, `ZstdDecompressStream` |
| `ZSTD_compress2`, `ZSTD_CCtx_setParameter` | `ZstdCompressContext.parameter` / `checksum` / `longDistanceMatching` / `windowLog` (all of `ZstdCompressParameter`) |
| `ZSTD_DCtx_setParameter` | `ZstdDecompressContext.parameter` / `windowLogMax` (`ZstdDecompressParameter`) |
| `ZSTD_CCtx_setPledgedSrcSize` | `ZstdOutputStream.withPledgedSize` |
| `ZSTD_CCtx_reset`, `ZSTD_DCtx_reset` | `ZstdCompressContext.reset` / `ZstdDecompressContext.reset` (`ZstdResetDirective`) |
| `ZSTD_getDictID_fromCDict`, `ZSTD_getDictID_fromDDict` | `ZstdCompressDictionary.id()` / `ZstdDecompressDictionary.id()` |
| `ZSTD_getErrorString` | `ZstdErrorCode.description()` |
| `ZSTD_cParam_getBounds`, `ZSTD_dParam_getBounds` | `ZstdCompressParameter.bounds()` / `ZstdDecompressParameter.bounds()` (`ZstdBounds`) |
| `ZSTD_CCtx_loadDictionary`, `ZSTD_DCtx_loadDictionary` | `ZstdCompressContext.loadDictionary` / `ZstdDecompressContext.loadDictionary`; `ZstdOutputStream` / `ZstdInputStream` dictionary constructors |
| `ZSTD_CCtx_refCDict`, `ZSTD_DCtx_refDDict` | `ZstdCompressContext.refDictionary` / `ZstdDecompressContext.refDictionary` |
| `ZSTD_isFrame`, `ZSTD_findFrameCompressedSize`, `ZSTD_decompressBound`, `ZSTD_getDictID_fromFrame`, `ZSTD_getFrameHeader`, `ZSTD_isSkippableFrame`, `ZSTD_writeSkippableFrame`, `ZSTD_readSkippableFrame` | `ZstdFrame` (+ `ZstdFrameHeader`, `ZstdFrameType`, `ZstdSkippableContent`) |
| `ZSTD_getErrorCode` | `ZstdException.code()` (+ `ZstdErrorCode`) |
| `ZSTD_getFrameProgression` | `ZstdCompressStream.progress()` (`ZstdFrameProgression`) |
| `ZDICT_optimizeTrainFromBuffer_cover`, `ZDICT_optimizeTrainFromBuffer_fastCover` | `ZstdDictionary.trainCover` / `trainFastCover` |
| `ZDICT_finalizeDictionary`, `ZDICT_getDictHeaderSize` | `ZstdDictionary.finalizeFrom` / `headerSize()` |
| `ZSTD_sizeof_CCtx`, `ZSTD_sizeof_DCtx`, `ZSTD_sizeof_CDict`, `ZSTD_sizeof_DDict` | `sizeOf()` on contexts / dicts / streams |
| `ZSTD_estimateCCtxSize`, `ZSTD_estimateDCtxSize`, `ZSTD_estimateCDictSize`, `ZSTD_estimateDDictSize` | `Zstd.estimate*Size` |

## Roadmap (priority order)

1. ~~**Streaming**~~ — done: `ZstdOutputStream` / `ZstdInputStream` (`byte[]`/`java.io`, `compressStream2`, `decompressStream`, bounded buffers, dictionary constructors, `pledgedSrcSize` via `withPledgedSize`) and `ZstdCompressStream` / `ZstdDecompressStream` (zero-copy `MemorySegment` driver on the same native calls).
2. ~~**Advanced parameters**~~ — done: every `ZSTD_cParameter`/`ZSTD_dParameter` via `ZstdCompressParameter`/`ZstdDecompressParameter` (+ `bounds()`), on both contexts; `pledgedSrcSize`. `nbWorkers` is functional (ADR 0015) and reachable from `ZstdCompressContext.parameter()` and from `ZstdCompressStream.parameter()` / `ZstdOutputStream.parameter()`.
3. ~~**Frame inspection**~~ — done: `ZstdFrame` (`isFrame`, `header`, `compressedSize`, `decompressedBound`, `decompressedSize`, `dictId`, skippable, `getFrameProgression`); dict-id from raw/CDict/DDict.
4. ~~**Better dictionaries**~~ — done: COVER / fast-COVER optimizers, `finalizeDictionary`, `getDictHeaderSize`.
5. ~~**Typed errors**~~ — done: `ZstdException.code()` returns `ZstdErrorCode` (via `getErrorCode`).

## Full symbol table

The **Bound** column is this library; **zstd-jni** marks symbols referenced by
zstd-jni's JNI sources (v1.5.7-11, `src/main/native/*.c`). The latter is
symbol-exact, not functional equivalence: zstd-jni may expose an operation through
a different symbol than this library — e.g. it routes one-shot compression through
`ZSTD_compress2`, so `ZSTD_compress` reads `—` for it even though `Zstd.compress`
works. zstd-jni references 53 of these symbols; this library binds 59. They
overlap on the modern context/streaming API and diverge mainly on zstd-jni's
sequence-producer hooks vs this library's frame-inspection and typed-error surface.

<!-- generated; see the nm command above to regenerate.
     zstd-jni column: grep '(ZSTD|ZDICT)_NAME(' over its src/main/native/*.c,
     intersected with this table's symbols. -->

### Core one-shot (6/7)

| Symbol | Bound | zstd-jni |
|---|:---:|:---:|
| `ZSTD_compress` | ✅ | — |
| `ZSTD_compressBound` | ✅ | ✅ |
| `ZSTD_decompress` | ✅ | ✅ |
| `ZSTD_defaultCLevel` | ✅ | — |
| `ZSTD_maxCLevel` | ✅ | ✅ |
| `ZSTD_minCLevel` | ✅ | ✅ |
| `ZSTD_compress_advanced` | — ᵈ | — |

### Version (2/2)

| Symbol | Bound | zstd-jni |
|---|:---:|:---:|
| `ZSTD_versionNumber` | ✅ | — |
| `ZSTD_versionString` | ✅ | — |

### Errors (4/4)

| Symbol | Bound | zstd-jni |
|---|:---:|:---:|
| `ZSTD_getErrorName` | ✅ | ✅ |
| `ZSTD_isError` | ✅ | ✅ |
| `ZSTD_getErrorCode` | ✅ | ✅ |
| `ZSTD_getErrorString` | ✅ | — |

### Reusable contexts (6/8)

| Symbol | Bound | zstd-jni |
|---|:---:|:---:|
| `ZSTD_compressCCtx` | ✅ | — |
| `ZSTD_createCCtx` | ✅ | ✅ |
| `ZSTD_createDCtx` | ✅ | ✅ |
| `ZSTD_decompressDCtx` | ✅ | ✅ |
| `ZSTD_freeCCtx` | ✅ | ✅ |
| `ZSTD_freeDCtx` | ✅ | ✅ |
| `ZSTD_createCCtx_advanced` | — | — |
| `ZSTD_createDCtx_advanced` | — | — |

### Dictionary — simple (11/23)

| Symbol | Bound | zstd-jni |
|---|:---:|:---:|
| `ZSTD_compress_usingCDict` | ✅ | ✅ |
| `ZSTD_compress_usingDict` | ✅ | — |
| `ZSTD_createCDict` | ✅ | ✅ |
| `ZSTD_createDDict` | ✅ | ✅ |
| `ZSTD_decompress_usingDDict` | ✅ | ✅ |
| `ZSTD_decompress_usingDict` | ✅ | — |
| `ZSTD_freeCDict` | ✅ | ✅ |
| `ZSTD_freeDDict` | ✅ | ✅ |
| `ZSTD_compress_usingCDict_advanced` | — ᵈ | — |
| `ZSTD_compressBegin_usingCDict` | — ᵈ | — |
| `ZSTD_compressBegin_usingCDict_advanced` | — ᵈ | — |
| `ZSTD_compressBegin_usingDict` | — ᵈ | — |
| `ZSTD_createCDict_advanced` | — | — |
| `ZSTD_createCDict_advanced2` | — | — |
| `ZSTD_createCDict_byReference` | — | ✅ |
| `ZSTD_createDDict_advanced` | — | — |
| `ZSTD_createDDict_byReference` | — | ✅ |
| `ZSTD_decompressBegin_usingDDict` | — | — |
| `ZSTD_decompressBegin_usingDict` | — | — |
| `ZSTD_getDictID_fromCDict` | ✅ | — |
| `ZSTD_getDictID_fromDDict` | ✅ | — |
| `ZSTD_getDictID_fromDict` | ✅ | ✅ |
| `ZSTD_getDictID_fromFrame` | ✅ | ✅ |

### Dictionary training, ZDICT (8/12)

| Symbol | Bound | zstd-jni |
|---|:---:|:---:|
| `ZDICT_getDictID` | ✅ | — |
| `ZDICT_getErrorName` | ✅ | — |
| `ZDICT_isError` | ✅ | — |
| `ZDICT_trainFromBuffer` | ✅ | ✅ |
| `ZDICT_addEntropyTablesFromBuffer` | — | — |
| `ZDICT_finalizeDictionary` | ✅ | — |
| `ZDICT_getDictHeaderSize` | ✅ | — |
| `ZDICT_optimizeTrainFromBuffer_cover` | ✅ | — |
| `ZDICT_optimizeTrainFromBuffer_fastCover` | ✅ | — |
| `ZDICT_trainFromBuffer_cover` | — | — |
| `ZDICT_trainFromBuffer_fastCover` | — | — |
| `ZDICT_trainFromBuffer_legacy` | — | ✅ |

### Streaming — compress (3/22)

| Symbol | Bound | zstd-jni |
|---|:---:|:---:|
| `ZSTD_CStreamInSize` | ✅ | — |
| `ZSTD_CStreamOutSize` | ✅ | ✅ |
| `ZSTD_compressStream2` | ✅ | ✅ |
| `ZSTD_compressStream` | — | ✅ |
| `ZSTD_compressStream2_simpleArgs` | — | — |
| `ZSTD_createCStream` | — | ✅ |
| `ZSTD_createCStream_advanced` | — | — |
| `ZSTD_endStream` | — | ✅ |
| `ZSTD_estimateCStreamSize` | — | — |
| `ZSTD_estimateCStreamSize_usingCCtxParams` | — | — |
| `ZSTD_estimateCStreamSize_usingCParams` | — | — |
| `ZSTD_flushStream` | — | ✅ |
| `ZSTD_freeCStream` | — | ✅ |
| `ZSTD_initCStream` | — | ✅ |
| `ZSTD_initCStream_advanced` | — ᵈ | — |
| `ZSTD_initCStream_srcSize` | — ᵈ | — |
| `ZSTD_initCStream_usingCDict` | — ᵈ | — |
| `ZSTD_initCStream_usingCDict_advanced` | — ᵈ | — |
| `ZSTD_initCStream_usingDict` | — ᵈ | — |
| `ZSTD_initStaticCStream` | — | — |
| `ZSTD_resetCStream` | — ᵈ | — |
| `ZSTD_sizeof_CStream` | — | — |

### Streaming — decompress (3/15)

| Symbol | Bound | zstd-jni |
|---|:---:|:---:|
| `ZSTD_DStreamInSize` | ✅ | ✅ |
| `ZSTD_DStreamOutSize` | ✅ | ✅ |
| `ZSTD_decompressStream` | ✅ | ✅ |
| `ZSTD_decompressStream_simpleArgs` | — | — |
| `ZSTD_createDStream` | — | ✅ |
| `ZSTD_createDStream_advanced` | — | — |
| `ZSTD_estimateDStreamSize` | — | — |
| `ZSTD_estimateDStreamSize_fromFrame` | — | — |
| `ZSTD_freeDStream` | — | — |
| `ZSTD_initDStream` | — | ✅ |
| `ZSTD_initDStream_usingDDict` | — ᵈ | — |
| `ZSTD_initDStream_usingDict` | — ᵈ | — |
| `ZSTD_initStaticDStream` | — | — |
| `ZSTD_resetDStream` | — ᵈ | — |
| `ZSTD_sizeof_DStream` | — | — |

### Advanced parameters (14/38)

| Symbol | Bound | zstd-jni |
|---|:---:|:---:|
| `ZSTD_CCtxParams_getParameter` | — | — |
| `ZSTD_CCtxParams_init` | — | — |
| `ZSTD_CCtxParams_init_advanced` | — | — |
| `ZSTD_CCtxParams_registerSequenceProducer` | — | — |
| `ZSTD_CCtxParams_reset` | — | — |
| `ZSTD_CCtxParams_setParameter` | — | — |
| `ZSTD_CCtx_getParameter` | — | — |
| `ZSTD_CCtx_loadDictionary` | ✅ | ✅ |
| `ZSTD_CCtx_loadDictionary_advanced` | — | — |
| `ZSTD_CCtx_loadDictionary_byReference` | — | — |
| `ZSTD_CCtx_refCDict` | ✅ | ✅ |
| `ZSTD_CCtx_refPrefix` | ✅ | — |
| `ZSTD_CCtx_refPrefix_advanced` | — | — |
| `ZSTD_CCtx_refThreadPool` | — | — |
| `ZSTD_CCtx_reset` | ✅ | ✅ |
| `ZSTD_CCtx_setCParams` | — | — |
| `ZSTD_CCtx_setFParams` | — | — |
| `ZSTD_CCtx_setParameter` | ✅ | ✅ |
| `ZSTD_CCtx_setParametersUsingCCtxParams` | — | — |
| `ZSTD_CCtx_setParams` | — | — |
| `ZSTD_CCtx_setPledgedSrcSize` | ✅ | ✅ |
| `ZSTD_DCtx_getParameter` | — | — |
| `ZSTD_DCtx_loadDictionary` | ✅ | ✅ |
| `ZSTD_DCtx_loadDictionary_advanced` | — | — |
| `ZSTD_DCtx_loadDictionary_byReference` | — | — |
| `ZSTD_DCtx_refDDict` | ✅ | ✅ |
| `ZSTD_DCtx_refPrefix` | ✅ | — |
| `ZSTD_DCtx_refPrefix_advanced` | — | — |
| `ZSTD_DCtx_reset` | ✅ | ✅ |
| `ZSTD_DCtx_setFormat` | — ᵈ | — |
| `ZSTD_DCtx_setMaxWindowSize` | — | — |
| `ZSTD_DCtx_setParameter` | ✅ | ✅ |
| `ZSTD_cParam_getBounds` | ✅ | — |
| `ZSTD_compress2` | ✅ | ✅ |
| `ZSTD_createCCtxParams` | — | — |
| `ZSTD_dParam_getBounds` | ✅ | — |
| `ZSTD_estimateCCtxSize_usingCCtxParams` | — | — |
| `ZSTD_freeCCtxParams` | — | — |

### Frame inspection (12/13)

| Symbol | Bound | zstd-jni |
|---|:---:|:---:|
| `ZSTD_getFrameContentSize` | ✅ | ✅ |
| `ZSTD_decompressBound` | ✅ | — |
| `ZSTD_findDecompressedSize` | ✅ | — |
| `ZSTD_findFrameCompressedSize` | ✅ | ✅ |
| `ZSTD_frameHeaderSize` | ✅ | — |
| `ZSTD_getDecompressedSize` | — ᵈ | — |
| `ZSTD_getFrameHeader` | ✅ | — |
| `ZSTD_getFrameHeader_advanced` | — | ✅ |
| `ZSTD_getFrameProgression` | ✅ | ✅ |
| `ZSTD_isFrame` | ✅ | — |
| `ZSTD_isSkippableFrame` | ✅ | — |
| `ZSTD_readSkippableFrame` | ✅ | — |
| `ZSTD_writeSkippableFrame` | ✅ | — |

### Memory sizing (9/14)

| Symbol | Bound | zstd-jni |
|---|:---:|:---:|
| `ZSTD_decodingBufferSize_min` | — | — |
| `ZSTD_decompressionMargin` | ✅ | — |
| `ZSTD_estimateCCtxSize` | ✅ | — |
| `ZSTD_estimateCCtxSize_usingCParams` | — | — |
| `ZSTD_estimateCDictSize` | ✅ | — |
| `ZSTD_estimateCDictSize_advanced` | — | — |
| `ZSTD_estimateDCtxSize` | ✅ | — |
| `ZSTD_estimateDDictSize` | ✅ | — |
| `ZSTD_getBlockSize` | — ᵈ | — |
| `ZSTD_sequenceBound` | — | — |
| `ZSTD_sizeof_CCtx` | ✅ | — |
| `ZSTD_sizeof_CDict` | ✅ | — |
| `ZSTD_sizeof_DCtx` | ✅ | — |
| `ZSTD_sizeof_DDict` | ✅ | — |

### Low-level block (0/12)

| Symbol | Bound | zstd-jni |
|---|:---:|:---:|
| `ZSTD_compressBegin` | — ᵈ | — |
| `ZSTD_compressBegin_advanced` | — ᵈ | — |
| `ZSTD_compressBlock` | — ᵈ | — |
| `ZSTD_compressContinue` | — ᵈ | — |
| `ZSTD_compressEnd` | — ᵈ | — |
| `ZSTD_decompressBegin` | — | — |
| `ZSTD_decompressBlock` | — ᵈ | — |
| `ZSTD_decompressContinue` | — | — |
| `ZSTD_insertBlock` | — ᵈ | — |
| `ZSTD_nextInputType` | — | — |
| `ZSTD_nextSrcSizeToDecompress` | — | — |
| `ZSTD_toFlushNow` | — | — |

### Sequences (0/5)

| Symbol | Bound | zstd-jni |
|---|:---:|:---:|
| `ZSTD_compressSequences` | — | — |
| `ZSTD_compressSequencesAndLiterals` | — | — |
| `ZSTD_generateSequences` | — ᵈ | ✅ |
| `ZSTD_mergeBlockDelimiters` | — | — |
| `ZSTD_registerSequenceProducer` | — | ✅ |

### Misc / experimental (0/10)

| Symbol | Bound | zstd-jni |
|---|:---:|:---:|
| `ZSTD_adjustCParams` | — | — |
| `ZSTD_checkCParams` | — | — |
| `ZSTD_copyCCtx` | — ᵈ | — |
| `ZSTD_copyDCtx` | — ᵈ | — |
| `ZSTD_getCParams` | — | — |
| `ZSTD_getParams` | — | — |
| `ZSTD_initStaticCCtx` | — | — |
| `ZSTD_initStaticCDict` | — | — |
| `ZSTD_initStaticDCtx` | — | — |
| `ZSTD_initStaticDDict` | — | — |
