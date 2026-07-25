package io.github.dfa1.zstd;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/// An immutable zstd dictionary — the feature that makes compressing many small,
/// similar payloads (log lines, JSON records, protobufs) dramatically smaller
/// than compressing each one independently.
///
/// Obtain one by [training][#train(List, ZstdByteSize)] on representative
/// samples, or wrap dictionary bytes you already have with [#of(byte[])].
/// Pass it to [ZstdCompressContext] / [ZstdDecompressContext] to compress and
/// decompress against it. For a hot path, digest it once into a
/// [ZstdCompressDictionary] / [ZstdDecompressDictionary].
///
/// {@snippet :
/// ZstdDictionary dict = ZstdDictionary.train(sampleRecords, ZstdByteSize.ofKiB(64));
/// try (ZstdCompressContext ctx = new ZstdCompressContext()) {
///     byte[] packed = ctx.compress(record, dict);
/// }
/// }
public final class ZstdDictionary {

    private static final String FIELD_NB_THREADS = "nbThreads";
    private static final String FIELD_COMPRESSION_LEVEL = "compressionLevel";
    private static final String SAMPLES = "samples";

    // ZDICT_cover_params_t fields: unsigned k, d, steps, nbThreads; double
    // splitPoint; unsigned shrinkDict, shrinkDictMaxRegression; then a nested
    // ZDICT_params_t of int compressionLevel, unsigned notificationLevel, dictID.
    private static final MemoryLayout COVER_PARAMS = MemoryLayout.structLayout(
            JAVA_INT.withName("k"),
            JAVA_INT.withName("d"),
            JAVA_INT.withName("steps"),
            JAVA_INT.withName(FIELD_NB_THREADS),
            JAVA_DOUBLE.withName("splitPoint"),
            JAVA_INT.withName("shrinkDict"),
            JAVA_INT.withName("shrinkDictMaxRegression"),
            JAVA_INT.withName(FIELD_COMPRESSION_LEVEL),
            JAVA_INT.withName("notificationLevel"),
            JAVA_INT.withName("dictID"),
            MemoryLayout.paddingLayout(4)); // trailing pad to the C struct's 8-byte alignment

    // ZDICT_fastCover_params_t adds `unsigned f` after d and `unsigned accel`
    // after splitPoint; the 4-byte gap before the 8-aligned double is explicit.
    private static final MemoryLayout FASTCOVER_PARAMS = MemoryLayout.structLayout(
            JAVA_INT.withName("k"),
            JAVA_INT.withName("d"),
            JAVA_INT.withName("f"),
            JAVA_INT.withName("steps"),
            JAVA_INT.withName(FIELD_NB_THREADS),
            MemoryLayout.paddingLayout(4),
            JAVA_DOUBLE.withName("splitPoint"),
            JAVA_INT.withName("accel"),
            JAVA_INT.withName("shrinkDict"),
            JAVA_INT.withName("shrinkDictMaxRegression"),
            JAVA_INT.withName(FIELD_COMPRESSION_LEVEL),
            JAVA_INT.withName("notificationLevel"),
            JAVA_INT.withName("dictID"));

    private final byte[] bytes;

    private ZstdDictionary(byte[] bytes) {
        this.bytes = bytes;
    }

    /// Wraps existing dictionary content (e.g. trained elsewhere, or a shared
    /// dictionary shipped with your application).
    ///
    /// @param raw dictionary bytes; defensively copied
    /// @return a dictionary wrapping a copy of `raw`
    public static ZstdDictionary of(byte[] raw) {
        Objects.requireNonNull(raw, "raw");
        return new ZstdDictionary(raw.clone());
    }

    /// Trains a dictionary from representative samples using zstd's ZDICT trainer.
    ///
    /// Aim for at least a few hundred samples totalling ~100× the target
    /// dictionary size; too little data yields a weak dictionary.
    ///
    /// @param samples      representative payloads to learn from
    /// @param maxDictBytes upper bound on the produced dictionary size (e.g. 110 KiB)
    /// @return the trained dictionary
    /// @throws ZstdException if training fails (commonly: not enough sample data),
    ///                       or `maxDictBytes` exceeds the maximum array length
    public static ZstdDictionary train(List<byte[]> samples, ZstdByteSize maxDictBytes) {
        Objects.requireNonNull(samples, SAMPLES);
        requireNonEmpty(samples, "train");
        int maxDictBytesLength = maxDictBytes.toArraySize();
        try (Arena arena = Arena.ofConfined()) {
            FlatSamples in = flatten(arena, samples);
            MemorySegment dictBuf = arena.allocate(maxDictBytesLength);
            long produced;
            try {
                produced = (long) Bindings.ZDICT_TRAIN.invokeExact(
                        dictBuf, (long) maxDictBytesLength, in.data(), in.sizes(), in.count());
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            return toDictionary(dictBuf, produced, "dictionary training");
        }
    }

    /// Trains a dictionary with the COVER algorithm, auto-tuning its parameters
    /// for the best dictionary it can find. Higher quality than [#train],
    /// but slower; for a faster near-equal result use [#trainFastCover].
    ///
    /// @param samples      representative payloads to learn from
    /// @param maxDictBytes upper bound on the produced dictionary size
    /// @return the trained dictionary
    /// @throws ZstdException if training fails, or `maxDictBytes` exceeds the
    ///                       maximum array length
    public static ZstdDictionary trainCover(List<byte[]> samples, ZstdByteSize maxDictBytes) {
        return optimize(samples, maxDictBytes, 0, false);
    }

    /// Trains a COVER dictionary optimized for a specific compression level.
    ///
    /// @param samples          representative payloads to learn from
    /// @param maxDictBytes     upper bound on the produced dictionary size
    /// @param compressionLevel the level the dictionary will be used at (a level
    ///                         whose [ZstdCompressionLevel#value()] is 0 = default)
    /// @return the trained dictionary
    /// @throws ZstdException if training fails, or `maxDictBytes` exceeds the
    ///                       maximum array length
    public static ZstdDictionary trainCover(List<byte[]> samples, ZstdByteSize maxDictBytes,
                                            ZstdCompressionLevel compressionLevel) {
        return optimize(samples, maxDictBytes, compressionLevel.value(), false);
    }

    /// Trains a dictionary with the fast COVER algorithm, auto-tuning its
    /// parameters. The recommended optimizer: nearly the quality of
    /// [#trainCover] at a fraction of the time.
    ///
    /// @param samples      representative payloads to learn from
    /// @param maxDictBytes upper bound on the produced dictionary size
    /// @return the trained dictionary
    /// @throws ZstdException if training fails, or `maxDictBytes` exceeds the
    ///                       maximum array length
    public static ZstdDictionary trainFastCover(List<byte[]> samples, ZstdByteSize maxDictBytes) {
        return optimize(samples, maxDictBytes, 0, true);
    }

    /// Trains a fast COVER dictionary optimized for a specific compression level.
    ///
    /// @param samples          representative payloads to learn from
    /// @param maxDictBytes     upper bound on the produced dictionary size
    /// @param compressionLevel the level the dictionary will be used at (a level
    ///                         whose [ZstdCompressionLevel#value()] is 0 = default)
    /// @return the trained dictionary
    /// @throws ZstdException if training fails, or `maxDictBytes` exceeds the
    ///                       maximum array length
    public static ZstdDictionary trainFastCover(List<byte[]> samples, ZstdByteSize maxDictBytes,
                                                ZstdCompressionLevel compressionLevel) {
        return optimize(samples, maxDictBytes, compressionLevel.value(), true);
    }

    private static ZstdDictionary optimize(List<byte[]> samples, ZstdByteSize maxDictBytes,
                                           int compressionLevel, boolean fast) {
        Objects.requireNonNull(samples, SAMPLES);
        requireNonEmpty(samples, "train");
        int maxDictBytesLength = maxDictBytes.toArraySize();
        try (Arena arena = Arena.ofConfined()) {
            FlatSamples in = flatten(arena, samples);
            // zeroed params (auto-tune k/d/steps); set single-threaded + target level.
            MemoryLayout layout = fast ? FASTCOVER_PARAMS : COVER_PARAMS;
            MemorySegment params = arena.allocate(layout);
            params.set(JAVA_INT, layout.byteOffset(PathElement.groupElement(FIELD_NB_THREADS)), 1);
            params.set(JAVA_INT, layout.byteOffset(PathElement.groupElement(FIELD_COMPRESSION_LEVEL)), compressionLevel);
            MethodHandle handle = fast ? Bindings.ZDICT_OPTIMIZE_FASTCOVER : Bindings.ZDICT_OPTIMIZE_COVER;
            MemorySegment dictBuf = arena.allocate(maxDictBytesLength);
            long produced;
            try {
                produced = (long) handle.invokeExact(
                        dictBuf, (long) maxDictBytesLength, in.data(), in.sizes(), in.count(), params);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            return toDictionary(dictBuf, produced, "dictionary training");
        }
    }

    /// Turns raw dictionary `content` you supply (e.g. hand-picked common bytes,
    /// or a prefix from elsewhere) into a usable zstd dictionary by adding a
    /// header and entropy tables tuned on `samples`. Use this when you control the
    /// dictionary content and only want zstd to finalize it.
    ///
    /// @param content          the raw dictionary content to wrap
    /// @param samples          representative payloads to tune entropy tables on
    /// @param maxDictBytes     upper bound on the produced dictionary size
    /// @param compressionLevel the level the dictionary will be used at (a level
    ///                         whose [ZstdCompressionLevel#value()] is 0 = default)
    /// @return the finalized dictionary
    /// @throws ZstdException if finalization fails, or `maxDictBytes` exceeds the
    ///                       maximum array length
    public static ZstdDictionary finalizeFrom(byte[] content, List<byte[]> samples,
                                              ZstdByteSize maxDictBytes, ZstdCompressionLevel compressionLevel) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(samples, SAMPLES);
        requireNonEmpty(samples, "finalize");
        int maxDictBytesLength = maxDictBytes.toArraySize();
        try (Arena arena = Arena.ofConfined()) {
            FlatSamples in = flatten(arena, samples);
            MemorySegment contentSeg = Zstd.copyIn(arena, content);
            MemorySegment params = arena.allocate(Bindings.ZDICT_PARAMS_LAYOUT);
            params.set(JAVA_INT, 0, compressionLevel.value());  // compressionLevel; notificationLevel/dictID = 0
            MemorySegment dictBuf = arena.allocate(maxDictBytesLength);
            long produced;
            try {
                produced = (long) Bindings.ZDICT_FINALIZE_DICTIONARY.invokeExact(
                        dictBuf, (long) maxDictBytesLength, contentSeg, (long) content.length,
                        in.data(), in.sizes(), in.count(), params);
            } catch (Throwable t) {
                throw NativeCall.rethrow(t);
            }
            return toDictionary(dictBuf, produced, "dictionary finalization");
        }
    }

    /// One native buffer holding all samples back to back, plus a parallel
    /// `size_t[]` of their lengths — the shape the ZDICT trainers consume.
    private record FlatSamples(MemorySegment data, MemorySegment sizes, int count) {
    }

    private static FlatSamples flatten(Arena arena, List<byte[]> samples) {
        long total = 0;
        for (byte[] s : samples) {
            total += s.length;
        }
        MemorySegment data = arena.allocate(Math.max(total, 1));
        MemorySegment sizes = arena.allocate(JAVA_LONG, samples.size());
        long offset = 0;
        for (int i = 0; i < samples.size(); i++) {
            byte[] s = samples.get(i);
            MemorySegment.copy(s, 0, data, JAVA_BYTE, offset, s.length);
            sizes.setAtIndex(JAVA_LONG, i, s.length);
            offset += s.length;
        }
        return new FlatSamples(data, sizes, samples.size());
    }

    private static void requireNonEmpty(List<byte[]> samples, String verb) {
        if (samples.isEmpty()) {
            throw new ZstdException("cannot " + verb + " a dictionary from zero samples");
        }
    }

    private static ZstdDictionary toDictionary(MemorySegment dictBuf, long produced, String what) {
        if (zdictIsError(produced)) {
            throw new ZstdException(what + " failed: " + zdictErrorName(produced));
        }
        byte[] out = new byte[Math.toIntExact(produced)];
        MemorySegment.copy(dictBuf, JAVA_BYTE, 0, out, 0, out.length);
        return new ZstdDictionary(out);
    }

    /// The dictionary id zstd stamps into frames compressed with this dictionary,
    /// or [ZstdDictionaryId#NONE] for a raw/content-only dictionary with no header.
    ///
    /// @return the dictionary id, or [ZstdDictionaryId#NONE] if none
    public ZstdDictionaryId id() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = Zstd.copyIn(arena, bytes);
            return ZstdDictionaryId.of((int) Bindings.ZDICT_GET_DICT_ID.invokeExact(seg, (long) bytes.length));
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    /// Size of this dictionary's header — the entropy-table and id prefix that
    /// precedes the raw content.
    ///
    /// @return the header size in bytes
    /// @throws ZstdException if this is not a valid zstd dictionary
    public ZstdByteSize headerSize() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = Zstd.copyIn(arena, bytes);
            long size = (long) Bindings.ZDICT_GET_DICT_HEADER_SIZE.invokeExact(seg, (long) bytes.length);
            if (zdictIsError(size)) {
                throw new ZstdException("not a valid dictionary: " + zdictErrorName(size));
            }
            return new ZstdByteSize(size);
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    /// Serializes this dictionary to a fresh byte array.
    ///
    /// @return a copy of the raw dictionary bytes, suitable for persisting
    public byte[] toByteArray() {
        return bytes.clone();
    }

    /// The size of this dictionary.
    ///
    /// @return the dictionary size in bytes
    public ZstdByteSize size() {
        return new ZstdByteSize(bytes.length);
    }

    /// Digests this dictionary once for compression at `level`, ready to share by
    /// reference across contexts with [ZstdCompressContext#refDictionary(ZstdCompressDictionary)].
    ///
    /// The returned dictionary owns native memory — close it when done (it is
    /// [AutoCloseable]). For a single context, prefer
    /// [ZstdCompressContext#loadDictionary(ZstdDictionary)], which the context digests,
    /// owns, and frees for you.
    ///
    /// @param level the compression level to fix for the digested dictionary
    /// @return a digested compression dictionary the caller must close
    public ZstdCompressDictionary compressDict(ZstdCompressionLevel level) {
        return new ZstdCompressDictionary(this, level);
    }

    /// Digests this dictionary for compression at the library default level.
    /// Otherwise identical to [#compressDict(ZstdCompressionLevel)].
    ///
    /// @return a digested compression dictionary the caller must close
    public ZstdCompressDictionary compressDict() {
        return new ZstdCompressDictionary(this);
    }

    /// Digests this dictionary once for decompression, ready to share by reference
    /// across contexts with [ZstdDecompressContext#refDictionary(ZstdDecompressDictionary)].
    ///
    /// The returned dictionary owns native memory — close it when done (it is
    /// [AutoCloseable]). For a single context, prefer
    /// [ZstdDecompressContext#loadDictionary(ZstdDictionary)], which the context owns
    /// and frees for you.
    ///
    /// @return a digested decompression dictionary the caller must close
    public ZstdDecompressDictionary decompressDict() {
        return new ZstdDecompressDictionary(this);
    }

    /// Internal: direct view of the bytes for native calls. Not exposed.
    byte[] raw() {
        return bytes;
    }

    private static boolean zdictIsError(long code) {
        try {
            return ((int) Bindings.ZDICT_IS_ERROR.invokeExact(code)) != 0;
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    @SuppressWarnings("restricted") // reinterpret needed to read a C string of unknown length
    private static String zdictErrorName(long code) {
        try {
            MemorySegment p = (MemorySegment) Bindings.ZDICT_GET_ERROR_NAME.invokeExact(code);
            return p.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.US_ASCII);
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }
}
