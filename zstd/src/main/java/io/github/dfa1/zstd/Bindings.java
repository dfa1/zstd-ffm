package io.github.dfa1.zstd;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/// Central registry of bound zstd C functions.
///
/// Signatures and semantics follow the
/// [official manual](https://facebook.github.io/zstd/doc/api_manual_latest.html).
///
/// `size_t` and `unsigned long long` are modeled as
/// [java.lang.foreign.ValueLayout#JAVA_LONG] (LP64); the public API guards
/// against negative interpretations where zstd uses sentinel values.
///
/// For the full coverage map — every public zstd symbol, what is bound, and which
/// deprecated functions are intentionally left out — see `docs/supported.md`.
final class Bindings {

    // size_t ZSTD_compressBound(size_t srcSize)
    static final MethodHandle COMPRESS_BOUND =
            NativeLibrary.lookup("ZSTD_compressBound", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG));

    // size_t ZSTD_compress(void* dst, size_t dstCap, const void* src, size_t srcSize, int level)
    static final MethodHandle COMPRESS =
            NativeLibrary.lookup("ZSTD_compress",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT));

    // size_t ZSTD_decompress(void* dst, size_t dstCap, const void* src, size_t srcSize)
    static final MethodHandle DECOMPRESS =
            NativeLibrary.lookup("ZSTD_decompress",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG));

    // unsigned long long ZSTD_getFrameContentSize(const void* src, size_t srcSize)
    static final MethodHandle GET_FRAME_CONTENT_SIZE =
            NativeLibrary.lookup("ZSTD_getFrameContentSize",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));

    // unsigned ZSTD_isFrame(const void* buffer, size_t size)
    static final MethodHandle IS_FRAME =
            NativeLibrary.lookup("ZSTD_isFrame", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));

    // size_t ZSTD_findFrameCompressedSize(const void* src, size_t srcSize)
    static final MethodHandle FIND_FRAME_COMPRESSED_SIZE =
            NativeLibrary.lookup("ZSTD_findFrameCompressedSize",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));

    // unsigned long long ZSTD_decompressBound(const void* src, size_t srcSize)
    static final MethodHandle DECOMPRESS_BOUND =
            NativeLibrary.lookup("ZSTD_decompressBound",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));

    // unsigned long long ZSTD_findDecompressedSize(const void* src, size_t srcSize)
    static final MethodHandle FIND_DECOMPRESSED_SIZE =
            NativeLibrary.lookup("ZSTD_findDecompressedSize",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));

    // size_t ZSTD_decompressionMargin(const void* src, size_t srcSize)
    static final MethodHandle DECOMPRESSION_MARGIN =
            NativeLibrary.lookup("ZSTD_decompressionMargin",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));

    // unsigned ZSTD_getDictID_fromFrame(const void* src, size_t srcSize)
    static final MethodHandle GET_DICT_ID_FROM_FRAME =
            NativeLibrary.lookup("ZSTD_getDictID_fromFrame",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));

    // size_t ZSTD_getFrameHeader(ZSTD_FrameHeader* zfh, const void* src, size_t srcSize)
    static final MethodHandle GET_FRAME_HEADER =
            NativeLibrary.lookup("ZSTD_getFrameHeader",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

    // size_t ZSTD_frameHeaderSize(const void* src, size_t srcSize)
    static final MethodHandle FRAME_HEADER_SIZE =
            NativeLibrary.lookup("ZSTD_frameHeaderSize",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));

    // unsigned ZSTD_isSkippableFrame(const void* buffer, size_t size)
    static final MethodHandle IS_SKIPPABLE_FRAME =
            NativeLibrary.lookup("ZSTD_isSkippableFrame",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));

    // size_t ZSTD_writeSkippableFrame(void* dst, size_t dstCap, const void* src, size_t srcSize, unsigned magicVariant)
    static final MethodHandle WRITE_SKIPPABLE_FRAME =
            NativeLibrary.lookup("ZSTD_writeSkippableFrame",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT));

    // size_t ZSTD_readSkippableFrame(void* dst, size_t dstCap, unsigned* magicVariant, const void* src, size_t srcSize)
    static final MethodHandle READ_SKIPPABLE_FRAME =
            NativeLibrary.lookup("ZSTD_readSkippableFrame",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

    // unsigned ZSTD_isError(size_t code)
    static final MethodHandle IS_ERROR =
            NativeLibrary.lookup("ZSTD_isError", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));

    // const char* ZSTD_getErrorName(size_t code)
    static final MethodHandle GET_ERROR_NAME =
            NativeLibrary.lookup("ZSTD_getErrorName", FunctionDescriptor.of(ADDRESS, JAVA_LONG));

    // ZSTD_ErrorCode ZSTD_getErrorCode(size_t functionResult)
    static final MethodHandle GET_ERROR_CODE =
            NativeLibrary.lookup("ZSTD_getErrorCode", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));

    // const char* ZSTD_getErrorString(ZSTD_ErrorCode code)
    static final MethodHandle GET_ERROR_STRING =
            NativeLibrary.lookup("ZSTD_getErrorString", FunctionDescriptor.of(ADDRESS, JAVA_INT));

    // int ZSTD_maxCLevel(void) / ZSTD_minCLevel(void) / ZSTD_defaultCLevel(void)
    static final MethodHandle MAX_C_LEVEL =
            NativeLibrary.lookup("ZSTD_maxCLevel", FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle MIN_C_LEVEL =
            NativeLibrary.lookup("ZSTD_minCLevel", FunctionDescriptor.of(JAVA_INT));
    static final MethodHandle DEFAULT_C_LEVEL =
            NativeLibrary.lookup("ZSTD_defaultCLevel", FunctionDescriptor.of(JAVA_INT));

    // unsigned ZSTD_versionNumber(void)
    static final MethodHandle VERSION_NUMBER =
            NativeLibrary.lookup("ZSTD_versionNumber", FunctionDescriptor.of(JAVA_INT));

    // --- reusable contexts ---

    // ZSTD_CCtx* ZSTD_createCCtx(void)
    static final MethodHandle CREATE_CCTX =
            NativeLibrary.lookup("ZSTD_createCCtx", FunctionDescriptor.of(ADDRESS));
    // size_t ZSTD_freeCCtx(ZSTD_CCtx*)
    static final MethodHandle FREE_CCTX =
            NativeLibrary.lookup("ZSTD_freeCCtx", FunctionDescriptor.of(JAVA_LONG, ADDRESS));

    // ZSTD_DCtx* ZSTD_createDCtx(void)
    static final MethodHandle CREATE_DCTX =
            NativeLibrary.lookup("ZSTD_createDCtx", FunctionDescriptor.of(ADDRESS));
    // size_t ZSTD_freeDCtx(ZSTD_DCtx*)
    static final MethodHandle FREE_DCTX =
            NativeLibrary.lookup("ZSTD_freeDCtx", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    // size_t ZSTD_decompressDCtx(ZSTD_DCtx*, void* dst, size_t dstCap, const void* src, size_t srcSize)
    static final MethodHandle DECOMPRESS_DCTX =
            NativeLibrary.lookup("ZSTD_decompressDCtx",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG));

    // --- streaming (CStream == CCtx, DStream == DCtx) ---

    // size_t ZSTD_CCtx_setParameter(ZSTD_CCtx*, ZSTD_cParameter, int value)
    static final MethodHandle CCTX_SET_PARAMETER =
            NativeLibrary.lookup("ZSTD_CCtx_setParameter",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT));

    // size_t ZSTD_CCtx_reset(ZSTD_CCtx*, ZSTD_ResetDirective)
    static final MethodHandle CCTX_RESET =
            NativeLibrary.lookup("ZSTD_CCtx_reset",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT));

    // size_t ZSTD_compress2(ZSTD_CCtx*, void* dst, size_t dstCap, const void* src, size_t srcSize)
    // Uses the advanced parameters set on the context (unlike ZSTD_compressCCtx).
    static final MethodHandle COMPRESS2 =
            NativeLibrary.lookup("ZSTD_compress2",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG));

    // size_t ZSTD_DCtx_setParameter(ZSTD_DCtx*, ZSTD_dParameter, int value)
    static final MethodHandle DCTX_SET_PARAMETER =
            NativeLibrary.lookup("ZSTD_DCtx_setParameter",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_INT));

    // size_t ZSTD_DCtx_reset(ZSTD_DCtx*, ZSTD_ResetDirective)
    static final MethodHandle DCTX_RESET =
            NativeLibrary.lookup("ZSTD_DCtx_reset",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT));

    // size_t ZSTD_CCtx_setPledgedSrcSize(ZSTD_CCtx*, unsigned long long pledgedSrcSize)
    static final MethodHandle CCTX_SET_PLEDGED_SRC_SIZE =
            NativeLibrary.lookup("ZSTD_CCtx_setPledgedSrcSize",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));

    // unsigned ZSTD_getDictID_fromCDict(const ZSTD_CDict*)
    static final MethodHandle GET_DICT_ID_FROM_CDICT =
            NativeLibrary.lookup("ZSTD_getDictID_fromCDict", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    // unsigned ZSTD_getDictID_fromDDict(const ZSTD_DDict*)
    static final MethodHandle GET_DICT_ID_FROM_DDICT =
            NativeLibrary.lookup("ZSTD_getDictID_fromDDict", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    // unsigned ZSTD_getDictID_fromDict(const void* dict, size_t dictSize)
    static final MethodHandle GET_DICT_ID_FROM_DICT =
            NativeLibrary.lookup("ZSTD_getDictID_fromDict",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));

    // ZSTD_bounds { size_t error; int lowerBound; int upperBound; } — returned by value
    static final MemoryLayout BOUNDS_LAYOUT =
            MemoryLayout.structLayout(
                    JAVA_LONG.withName("error"),
                    JAVA_INT.withName("lowerBound"),
                    JAVA_INT.withName("upperBound"));
    // ZSTD_bounds ZSTD_cParam_getBounds(ZSTD_cParameter) / ZSTD_dParam_getBounds(ZSTD_dParameter)
    static final MethodHandle CPARAM_GET_BOUNDS =
            NativeLibrary.lookup("ZSTD_cParam_getBounds", FunctionDescriptor.of(BOUNDS_LAYOUT, JAVA_INT));
    static final MethodHandle DPARAM_GET_BOUNDS =
            NativeLibrary.lookup("ZSTD_dParam_getBounds", FunctionDescriptor.of(BOUNDS_LAYOUT, JAVA_INT));

    // ZSTD_frameProgression layout: u64 ingested, consumed, produced, flushed, then u32 currentJobID, nbActiveWorkers.
    private static final MemoryLayout FRAME_PROGRESSION_LAYOUT =
            MemoryLayout.structLayout(JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT);
    // ZSTD_frameProgression ZSTD_getFrameProgression(const ZSTD_CCtx*) — returned by value
    static final MethodHandle GET_FRAME_PROGRESSION =
            NativeLibrary.lookup("ZSTD_getFrameProgression",
                    FunctionDescriptor.of(FRAME_PROGRESSION_LAYOUT, ADDRESS));

    // size_t ZSTD_compressStream2(ZSTD_CCtx*, ZSTD_outBuffer*, ZSTD_inBuffer*, ZSTD_EndDirective)
    static final MethodHandle COMPRESS_STREAM2 =
            NativeLibrary.lookup("ZSTD_compressStream2",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));

    // size_t ZSTD_decompressStream(ZSTD_DCtx*, ZSTD_outBuffer*, ZSTD_inBuffer*)
    static final MethodHandle DECOMPRESS_STREAM =
            NativeLibrary.lookup("ZSTD_decompressStream",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, ADDRESS));

    // size_t ZSTD_CCtx_loadDictionary(ZSTD_CCtx*, const void* dict, size_t dictSize)
    static final MethodHandle CCTX_LOAD_DICTIONARY =
            NativeLibrary.lookup("ZSTD_CCtx_loadDictionary",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));
    // size_t ZSTD_DCtx_loadDictionary(ZSTD_DCtx*, const void* dict, size_t dictSize)
    static final MethodHandle DCTX_LOAD_DICTIONARY =
            NativeLibrary.lookup("ZSTD_DCtx_loadDictionary",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

    // size_t ZSTD_CCtx_refPrefix(ZSTD_CCtx*, const void* prefix, size_t prefixSize)
    static final MethodHandle CCTX_REF_PREFIX =
            NativeLibrary.lookup("ZSTD_CCtx_refPrefix",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));
    // size_t ZSTD_DCtx_refPrefix(ZSTD_DCtx*, const void* prefix, size_t prefixSize)
    static final MethodHandle DCTX_REF_PREFIX =
            NativeLibrary.lookup("ZSTD_DCtx_refPrefix",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG));

    // size_t ZSTD_CStreamInSize(void) / ZSTD_CStreamOutSize(void)
    static final MethodHandle CSTREAM_IN_SIZE =
            NativeLibrary.lookup("ZSTD_CStreamInSize", FunctionDescriptor.of(JAVA_LONG));
    static final MethodHandle CSTREAM_OUT_SIZE =
            NativeLibrary.lookup("ZSTD_CStreamOutSize", FunctionDescriptor.of(JAVA_LONG));
    // size_t ZSTD_DStreamInSize(void) / ZSTD_DStreamOutSize(void)
    static final MethodHandle DSTREAM_IN_SIZE =
            NativeLibrary.lookup("ZSTD_DStreamInSize", FunctionDescriptor.of(JAVA_LONG));
    static final MethodHandle DSTREAM_OUT_SIZE =
            NativeLibrary.lookup("ZSTD_DStreamOutSize", FunctionDescriptor.of(JAVA_LONG));

    // --- dictionaries: raw (re-digested per call) ---

    // size_t ZSTD_compress_usingDict(ZSTD_CCtx*, dst, dstCap, src, srcSize, dict, dictSize, int level)
    static final MethodHandle COMPRESS_USING_DICT =
            NativeLibrary.lookup("ZSTD_compress_usingDict",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG,
                            ADDRESS, JAVA_LONG, JAVA_INT));

    // size_t ZSTD_decompress_usingDict(ZSTD_DCtx*, dst, dstCap, src, srcSize, dict, dictSize)
    static final MethodHandle DECOMPRESS_USING_DICT =
            NativeLibrary.lookup("ZSTD_decompress_usingDict",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG,
                            ADDRESS, JAVA_LONG));

    // --- dictionaries: digested (built once, reused) ---

    // ZSTD_CDict* ZSTD_createCDict(const void* dict, size_t dictSize, int level)
    static final MethodHandle CREATE_CDICT =
            NativeLibrary.lookup("ZSTD_createCDict",
                    FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT));
    // size_t ZSTD_freeCDict(ZSTD_CDict*)
    static final MethodHandle FREE_CDICT =
            NativeLibrary.lookup("ZSTD_freeCDict", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    // size_t ZSTD_compress_usingCDict(ZSTD_CCtx*, dst, dstCap, src, srcSize, const ZSTD_CDict*)
    static final MethodHandle COMPRESS_USING_CDICT =
            NativeLibrary.lookup("ZSTD_compress_usingCDict",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS));
    // size_t ZSTD_CCtx_refCDict(ZSTD_CCtx*, const ZSTD_CDict*)
    static final MethodHandle CCTX_REF_CDICT =
            NativeLibrary.lookup("ZSTD_CCtx_refCDict",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS));

    // ZSTD_DDict* ZSTD_createDDict(const void* dict, size_t dictSize)
    static final MethodHandle CREATE_DDICT =
            NativeLibrary.lookup("ZSTD_createDDict",
                    FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG));
    // size_t ZSTD_freeDDict(ZSTD_DDict*)
    static final MethodHandle FREE_DDICT =
            NativeLibrary.lookup("ZSTD_freeDDict", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    // size_t ZSTD_decompress_usingDDict(ZSTD_DCtx*, dst, dstCap, src, srcSize, const ZSTD_DDict*)
    static final MethodHandle DECOMPRESS_USING_DDICT =
            NativeLibrary.lookup("ZSTD_decompress_usingDDict",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS));
    // size_t ZSTD_DCtx_refDDict(ZSTD_DCtx*, const ZSTD_DDict*)
    static final MethodHandle DCTX_REF_DDICT =
            NativeLibrary.lookup("ZSTD_DCtx_refDDict",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS));

    // --- dictionary training (ZDICT, from dictBuilder) ---

    // size_t ZDICT_trainFromBuffer(void* dictBuffer, size_t dictBufferCapacity,
    //                              const void* samplesBuffer, const size_t* samplesSizes, unsigned nbSamples)
    static final MethodHandle ZDICT_TRAIN =
            NativeLibrary.lookup("ZDICT_trainFromBuffer",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT));
    // unsigned ZDICT_isError(size_t)
    static final MethodHandle ZDICT_IS_ERROR =
            NativeLibrary.lookup("ZDICT_isError", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
    // const char* ZDICT_getErrorName(size_t)
    static final MethodHandle ZDICT_GET_ERROR_NAME =
            NativeLibrary.lookup("ZDICT_getErrorName", FunctionDescriptor.of(ADDRESS, JAVA_LONG));
    // unsigned ZDICT_getDictID(const void* dictBuffer, size_t dictSize)
    static final MethodHandle ZDICT_GET_DICT_ID =
            NativeLibrary.lookup("ZDICT_getDictID", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));

    // size_t ZDICT_optimizeTrainFromBuffer_cover(dictBuffer, dictCap, samples, sizes, nbSamples,
    //                                            ZDICT_cover_params_t* parameters)  [params auto-tuned in place]
    static final MethodHandle ZDICT_OPTIMIZE_COVER =
            NativeLibrary.lookup("ZDICT_optimizeTrainFromBuffer_cover",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
    // size_t ZDICT_optimizeTrainFromBuffer_fastCover(..., ZDICT_fastCover_params_t* parameters)
    static final MethodHandle ZDICT_OPTIMIZE_FASTCOVER =
            NativeLibrary.lookup("ZDICT_optimizeTrainFromBuffer_fastCover",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));

    // ZDICT_params_t { int compressionLevel; unsigned notificationLevel; unsigned dictID; } — by value
    static final MemoryLayout ZDICT_PARAMS_LAYOUT =
            MemoryLayout.structLayout(JAVA_INT, JAVA_INT, JAVA_INT);
    // size_t ZDICT_finalizeDictionary(dstDict, maxDictSize, dictContent, contentSize,
    //                                 samples, sizes, nbSamples, ZDICT_params_t params)
    static final MethodHandle ZDICT_FINALIZE_DICTIONARY =
            NativeLibrary.lookup("ZDICT_finalizeDictionary",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG,
                            ADDRESS, ADDRESS, JAVA_INT, ZDICT_PARAMS_LAYOUT));
    // size_t ZDICT_getDictHeaderSize(const void* dictBuffer, size_t dictSize)
    static final MethodHandle ZDICT_GET_DICT_HEADER_SIZE =
            NativeLibrary.lookup("ZDICT_getDictHeaderSize",
                    FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG));

    // --- memory accounting ---

    // size_t ZSTD_sizeof_CCtx / DCtx / CDict / DDict (live memory of an object)
    static final MethodHandle SIZEOF_CCTX =
            NativeLibrary.lookup("ZSTD_sizeof_CCtx", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    static final MethodHandle SIZEOF_DCTX =
            NativeLibrary.lookup("ZSTD_sizeof_DCtx", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    static final MethodHandle SIZEOF_CDICT =
            NativeLibrary.lookup("ZSTD_sizeof_CDict", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    static final MethodHandle SIZEOF_DDICT =
            NativeLibrary.lookup("ZSTD_sizeof_DDict", FunctionDescriptor.of(JAVA_LONG, ADDRESS));

    // size_t ZSTD_estimateCCtxSize(int level) / ZSTD_estimateDCtxSize(void)
    static final MethodHandle ESTIMATE_CCTX_SIZE =
            NativeLibrary.lookup("ZSTD_estimateCCtxSize", FunctionDescriptor.of(JAVA_LONG, JAVA_INT));
    static final MethodHandle ESTIMATE_DCTX_SIZE =
            NativeLibrary.lookup("ZSTD_estimateDCtxSize", FunctionDescriptor.of(JAVA_LONG));
    // size_t ZSTD_estimateCDictSize(size_t dictSize, int level)
    static final MethodHandle ESTIMATE_CDICT_SIZE =
            NativeLibrary.lookup("ZSTD_estimateCDictSize", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT));
    // size_t ZSTD_estimateDDictSize(size_t dictSize, ZSTD_dictLoadMethod_e dlm)
    static final MethodHandle ESTIMATE_DDICT_SIZE =
            NativeLibrary.lookup("ZSTD_estimateDDictSize", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT));

    private Bindings() {
        // no instances
    }
}
