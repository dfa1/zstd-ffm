package io.github.dfa1.zstd;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Objects;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/// Entry point to the library: one-shot compression plus the factories for
/// every native-backed resource.
///
/// ## One-shot
///
/// [#compress(byte[])] / [#decompress(byte[])] and friends are stateless and
/// thread-safe, allocating their own native scratch buffers per call. Frame
/// probes ([#decompressedSize(MemorySegment)], [#dictId(byte[])]), sizing
/// ([#compressBound(ZstdByteSize)], the `estimate*` methods), and
/// [#version()] live here too.
///
/// {@snippet :
/// byte[] packed   = Zstd.compress("hello world".getBytes());
/// byte[] restored = Zstd.decompress(packed);
/// }
///
/// ## Contexts
///
/// For repeated calls on a hot path, reuse a context from
/// [#newCompressContext()] / [#newDecompressContext()] instead of re-allocating
/// per call. Contexts are not thread-safe.
///
/// {@snippet :
/// try (ZstdCompressContext ctx = Zstd.newCompressContext()) {
///     ctx.level(ZstdCompressionLevel.of(19)); // configure inside the try: a failing setter can't leak ctx
///     byte[] frame = ctx.compress(payload);
/// }
/// }
///
/// ## Dictionaries
///
/// Digest a [ZstdDictionary] once with [#newCompressDictionary(ZstdDictionary)] /
/// [#newDecompressDictionary(ZstdDictionary)] (or the [MemorySegment] overloads
/// for off-heap bytes) and share the result across threads and contexts.
///
/// ## Streams
///
/// [#newOutputStream(OutputStream)] / [#newInputStream(InputStream)] wrap
/// `java.io` streams; [#newCompressStream()] / [#newDecompressStream()] drive
/// zero-copy [MemorySegment] streaming.
///
/// Every `new*` factory returns an object that owns native memory: close it,
/// ideally with try-with-resources.
public final class Zstd {

    /// Compresses `src` at the library default level.
    ///
    /// @param src bytes to compress
    /// @return a self-describing zstd frame
    public static byte[] compress(byte[] src) {
        return compress(src, ZstdCompressionLevel.DEFAULT);
    }

    /// Compresses `src` at the given level.
    ///
    /// @param src   bytes to compress
    /// @param level the compression level to use; higher is smaller but slower
    /// @return a self-describing zstd frame
    public static byte[] compress(byte[] src, ZstdCompressionLevel level) {
        Objects.requireNonNull(src, "src");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = copyIn(arena, src);
            ZstdByteSize bound = compressBound(ZstdByteSize.lengthOf(src));
            MemorySegment out = arena.allocate(bound.value());
            long written = NativeCall.checkReturnValue(() -> (long) Bindings.COMPRESS.invokeExact(
                    out, bound.value(), in, (long) src.length, level.value()));
            return copyOut(out, written);
        }
    }

    /// Decompresses a complete zstd frame whose decompressed size is recorded in
    /// its header (the case for frames produced by this library).
    ///
    /// **Security:** this overload trusts the content size declared in the frame
    /// header and allocates a buffer of that size. The header is part of the input,
    /// so a hostile frame can declare a large size (up to the maximum array length)
    /// and force a correspondingly large allocation — a decompression-bomb denial of
    /// service. For input you do not control, use [#decompress(byte[], ZstdByteSize)] with a
    /// sane bound instead.
    ///
    /// @param compressed a complete zstd frame
    /// @return the original bytes
    /// @throws ZstdException if the frame is invalid, its content size is not stored
    ///                       (use [#decompress(byte[], ZstdByteSize)] for the latter), or the
    ///                       declared size exceeds the maximum array length
    public static byte[] decompress(byte[] compressed) {
        Objects.requireNonNull(compressed, "compressed");
        return decompressInternal(compressed, frameContentSize(compressed));
    }

    /// Decompresses a zstd frame into a buffer of at most `maxSizeBound` bytes.
    /// Use this when the original size is known out-of-band or not stored in the frame.
    ///
    /// This is the safe entry point for **untrusted** input: `maxSizeBound` caps
    /// the allocation and decode, so a hostile frame cannot trigger an oversized
    /// allocation the way [#decompress(byte[])] can. Pick a bound your caller can
    /// afford; the frame is rejected if its content exceeds it.
    ///
    /// @param compressed a complete zstd frame
    /// @param maxSizeBound upper bound on the decompressed length
    /// @return the original bytes (length ≤ `maxSizeBound`)
    /// @throws ZstdException if the frame is invalid, larger than `maxSizeBound`, or
    ///                       `maxSizeBound` exceeds the maximum array length
    public static byte[] decompress(byte[] compressed, ZstdByteSize maxSizeBound) {
        Objects.requireNonNull(compressed, "compressed");
        return decompressInternal(compressed, maxSizeBound);
    }

    private static byte[] decompressInternal(byte[] compressed, ZstdByteSize maxSize) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = copyIn(arena, compressed);
            MemorySegment out = arena.allocate(maxSize.toArraySize());
            long written = NativeCall.checkReturnValue(() -> (long) Bindings.DECOMPRESS.invokeExact(
                    out, maxSize.value(), in, (long) compressed.length));
            return copyOut(out, written);
        }
    }

    /// Decompressed size recorded in a zstd frame's header, read directly from a
    /// native [MemorySegment] with no copy — use it to size the destination
    /// for the zero-copy [ZstdDecompressContext#decompress(MemorySegment, MemorySegment)].
    ///
    /// @param frame a complete zstd frame
    /// @return the decompressed length in bytes
    /// @throws ZstdException if the frame is invalid or does not store its size
    public static ZstdByteSize decompressedSize(MemorySegment frame) {
        NativeCall.requireReadable(frame, "frame");
        try {
            long size = (long) Bindings.GET_FRAME_CONTENT_SIZE.invokeExact(frame, frame.byteSize());
            return ZstdByteSize.fromFrameContentSize(size);
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    /// Dictionary id stamped in raw dictionary `bytes`, read with the core
    /// `libzstd` reader.
    ///
    /// This reads the id a *dictionary* records, not the id a frame references —
    /// for the latter use [ZstdFrame#dictId(byte[])]. It returns the same value as
    /// [ZstdDictionary#id()] (which uses the equivalent `ZDICT` reader); prefer this
    /// when you only hold raw bytes and do not want to wrap them in a
    /// [ZstdDictionary].
    ///
    /// @param bytes raw dictionary content
    /// @return the dictionary id, or [ZstdDictionaryId#NONE] if `bytes` is not a standard zstd
    ///         dictionary (for example a raw/content-only dictionary with no header)
    public static ZstdDictionaryId dictId(byte[] bytes) {
        try (Arena arena = Arena.ofConfined()) {
            return dictId(copyIn(arena, bytes), bytes.length);
        }
    }

    /// Dictionary id stamped in native dictionary `bytes`, read with no copy.
    /// Otherwise identical to [#dictId(byte[])].
    ///
    /// @param bytes native dictionary content
    /// @return the dictionary id, or [ZstdDictionaryId#NONE] if `bytes` is not a standard zstd dictionary
    public static ZstdDictionaryId dictId(MemorySegment bytes) {
        NativeCall.requireReadable(bytes, "bytes");
        return dictId(bytes, bytes.byteSize());
    }

    private static ZstdDictionaryId dictId(MemorySegment bytes, long size) {
        try {
            return ZstdDictionaryId.of((int) Bindings.GET_DICT_ID_FROM_DICT.invokeExact(bytes, size));
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    /// Maximum compressed size for an input of `srcSize` bytes — the buffer
    /// size guaranteed to never overflow during compression.
    ///
    /// @param srcSize the uncompressed input length in bytes
    /// @return the worst-case compressed size for that input
    public static ZstdByteSize compressBound(ZstdByteSize srcSize) {
        try {
            return ZstdByteSize.of((long) Bindings.COMPRESS_BOUND.invokeExact(srcSize.value()));
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    /// Decompressed size stored in the frame header, validated via
    /// [ZstdByteSize#fromFrameContentSize(long)].
    static ZstdByteSize frameContentSize(byte[] compressed) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = copyIn(arena, compressed);
            long raw = (long) Bindings.GET_FRAME_CONTENT_SIZE.invokeExact(in, (long) compressed.length);
            return ZstdByteSize.fromFrameContentSize(raw);
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    /// Estimates the memory a compression context will use at `level`, before
    /// creating one — useful for budgeting.
    ///
    /// @param level the compression level to use
    /// @return the estimated context size in bytes
    public static ZstdByteSize estimateCompressContextSize(ZstdCompressionLevel level) {
        try {
            return ZstdByteSize.of((long) Bindings.ESTIMATE_CCTX_SIZE.invokeExact(level.value()));
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    /// Estimates the memory a decompression context will use.
    ///
    /// @return the estimated context size in bytes
    public static ZstdByteSize estimateDecompressContextSize() {
        try {
            return ZstdByteSize.of((long) Bindings.ESTIMATE_DCTX_SIZE.invokeExact());
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    /// Estimates the memory a digested compression dictionary of `dictSize` bytes
    /// will use at `level`.
    ///
    /// @param dictSize the raw dictionary size in bytes
    /// @param level    the compression level to use
    /// @return the estimated digested-dictionary size in bytes
    public static ZstdByteSize estimateCompressDictSize(ZstdByteSize dictSize, ZstdCompressionLevel level) {
        try {
            return ZstdByteSize.of((long) Bindings.ESTIMATE_CDICT_SIZE.invokeExact(dictSize.value(), level.value()));
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    /// Estimates the memory a digested decompression dictionary of `dictSize`
    /// bytes will use.
    ///
    /// @param dictSize the raw dictionary size in bytes
    /// @return the estimated digested-dictionary size in bytes
    public static ZstdByteSize estimateDecompressDictSize(ZstdByteSize dictSize) {
        try {
            // ZSTD_dlm_byCopy
            return ZstdByteSize.of((long) Bindings.ESTIMATE_DDICT_SIZE.invokeExact(dictSize.value(), 0));
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    /// Runtime version of the linked zstd library, e.g. `1.6.0` for the bundled build.
    ///
    /// Read [ZstdVersion#toString()] for the `x.y.z` string, [ZstdVersion#number()]
    /// for the packed number, or compare it (it is [Comparable]) to feature-gate
    /// against the runtime library.
    ///
    /// @return the linked zstd library version
    public static ZstdVersion version() {
        try {
            return ZstdVersion.ofNumber((int) Bindings.VERSION_NUMBER.invokeExact());
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    // --- resource factories: every native-backed object starts here ---

    /// Allocates a reusable compression context at the default level
    /// ([ZstdCompressionLevel#DEFAULT]).
    ///
    /// The context owns native memory: the caller must [ZstdCompressContext#close()]
    /// it, ideally with try-with-resources. Not thread-safe.
    ///
    /// @return a new, open compression context
    /// @throws ZstdException if the native allocation fails
    public static ZstdCompressContext newCompressContext() {
        return new ZstdCompressContext();
    }

    /// Allocates a reusable decompression context with default parameters.
    ///
    /// The context owns native memory: the caller must [ZstdDecompressContext#close()]
    /// it, ideally with try-with-resources. Not thread-safe.
    ///
    /// @return a new, open decompression context
    /// @throws ZstdException if the native allocation fails
    public static ZstdDecompressContext newDecompressContext() {
        return new ZstdDecompressContext();
    }

    /// Digests `dict` for compression at the library default level, copying its
    /// bytes into native memory so the source may be discarded afterwards.
    ///
    /// The digested dictionary owns native memory: the caller must
    /// [ZstdCompressDictionary#close()] it. Safe to share across threads.
    ///
    /// @param dict the dictionary to digest
    /// @return a new digested compression dictionary
    /// @throws NullPointerException if `dict` is `null`
    /// @throws ZstdException        if the native allocation fails
    public static ZstdCompressDictionary newCompressDictionary(ZstdDictionary dict) {
        return new ZstdCompressDictionary(dict);
    }

    /// Digests `dict` for compression at `level`, copying its bytes into native
    /// memory so the source may be discarded afterwards.
    ///
    /// The digested dictionary owns native memory: the caller must
    /// [ZstdCompressDictionary#close()] it. Safe to share across threads.
    ///
    /// @param dict  the dictionary to digest
    /// @param level the compression level to fix for this digested dictionary
    /// @return a new digested compression dictionary
    /// @throws NullPointerException if `dict` is `null`
    /// @throws ZstdException        if the native allocation fails
    public static ZstdCompressDictionary newCompressDictionary(ZstdDictionary dict, ZstdCompressionLevel level) {
        return new ZstdCompressDictionary(dict, level);
    }

    /// Digests a native dictionary segment for compression at the library default
    /// level, without a heap copy.
    ///
    /// `dict` must be a native (off-heap) [MemorySegment] — e.g. an mmap slice or
    /// an arena buffer; its bytes are copied, so the segment may be released once
    /// this returns. The digested dictionary owns native memory: the caller must
    /// [ZstdCompressDictionary#close()] it.
    ///
    /// @param dict native dictionary content
    /// @return a new digested compression dictionary
    /// @throws NullPointerException if `dict` is `null`
    /// @throws IllegalArgumentException if `dict` is not a native segment
    /// @throws ZstdException            if the native allocation fails
    public static ZstdCompressDictionary newCompressDictionary(MemorySegment dict) {
        return new ZstdCompressDictionary(dict);
    }

    /// Digests a native dictionary segment for compression at `level`, without a
    /// heap copy. Heap-backed callers should use
    /// [#newCompressDictionary(ZstdDictionary, ZstdCompressionLevel)] instead.
    ///
    /// `dict` must be a native (off-heap) [MemorySegment]; its bytes are copied, so
    /// the segment may be released once this returns. The digested dictionary owns
    /// native memory: the caller must [ZstdCompressDictionary#close()] it.
    ///
    /// @param dict  native dictionary content
    /// @param level the compression level to fix for this digested dictionary
    /// @return a new digested compression dictionary
    /// @throws NullPointerException if `dict` is `null`
    /// @throws IllegalArgumentException if `dict` is not a native segment
    /// @throws ZstdException            if the native allocation fails
    public static ZstdCompressDictionary newCompressDictionary(MemorySegment dict, ZstdCompressionLevel level) {
        return new ZstdCompressDictionary(dict, level);
    }

    /// Digests `dict` for decompression, copying its bytes into native memory so
    /// the source may be discarded afterwards.
    ///
    /// The digested dictionary owns native memory: the caller must
    /// [ZstdDecompressDictionary#close()] it. Safe to share across threads.
    ///
    /// @param dict the dictionary to digest
    /// @return a new digested decompression dictionary
    /// @throws NullPointerException if `dict` is `null`
    /// @throws ZstdException        if the native allocation fails
    public static ZstdDecompressDictionary newDecompressDictionary(ZstdDictionary dict) {
        return new ZstdDecompressDictionary(dict);
    }

    /// Digests a native dictionary segment for decompression, without a heap copy.
    /// Heap-backed callers should use [#newDecompressDictionary(ZstdDictionary)].
    ///
    /// `dict` must be a native (off-heap) [MemorySegment]; its bytes are copied, so
    /// the segment may be released once this returns. The digested dictionary owns
    /// native memory: the caller must [ZstdDecompressDictionary#close()] it.
    ///
    /// @param dict native dictionary content
    /// @return a new digested decompression dictionary
    /// @throws NullPointerException if `dict` is `null`
    /// @throws IllegalArgumentException if `dict` is not a native segment
    /// @throws ZstdException            if the native allocation fails
    public static ZstdDecompressDictionary newDecompressDictionary(MemorySegment dict) {
        return new ZstdDecompressDictionary(dict);
    }

    /// Allocates a zero-copy [MemorySegment] streaming compressor at the default level.
    ///
    /// The stream owns native memory: the caller must [ZstdCompressStream#close()]
    /// it. Not thread-safe.
    ///
    /// @return a new, open streaming compressor
    /// @throws ZstdException if the native allocation fails
    public static ZstdCompressStream newCompressStream() {
        return new ZstdCompressStream();
    }

    /// Allocates a zero-copy [MemorySegment] streaming compressor, compressing
    /// against `dictionary`.
    ///
    /// The stream owns native memory: the caller must [ZstdCompressStream#close()]
    /// it. Not thread-safe.
    ///
    /// @param dictionary the dictionary to compress against
    /// @return a new, open streaming compressor
    /// @throws NullPointerException if `dictionary` is `null`
    /// @throws ZstdException        if the native allocation or dictionary load fails
    public static ZstdCompressStream newCompressStream(ZstdDictionary dictionary) {
        Objects.requireNonNull(dictionary, "dictionary");
        return new ZstdCompressStream(dictionary);
    }

    /// Allocates a zero-copy [MemorySegment] streaming decompressor.
    ///
    /// The stream owns native memory: the caller must [ZstdDecompressStream#close()]
    /// it. Not thread-safe.
    ///
    /// @return a new, open streaming decompressor
    /// @throws ZstdException if the native allocation fails
    public static ZstdDecompressStream newDecompressStream() {
        return new ZstdDecompressStream();
    }

    /// Allocates a zero-copy [MemorySegment] streaming decompressor for frames
    /// compressed against `dictionary`.
    ///
    /// The stream owns native memory: the caller must [ZstdDecompressStream#close()]
    /// it. Not thread-safe.
    ///
    /// @param dictionary the dictionary the frames were compressed against, or `null` for none
    /// @return a new, open streaming decompressor
    /// @throws ZstdException if the native allocation or dictionary load fails
    public static ZstdDecompressStream newDecompressStream(ZstdDictionary dictionary) {
        return new ZstdDecompressStream(dictionary);
    }

    /// Wraps `out` in a `java.io` compressing stream at the library default level.
    ///
    /// The stream owns native memory: the caller must [ZstdOutputStream#close()] it,
    /// which finishes the frame and closes `out`.
    ///
    /// @param out the stream to write the compressed frame to
    /// @return a new compressing stream
    /// @throws NullPointerException if `out` is `null`
    /// @throws ZstdException        if the native allocation fails
    public static ZstdOutputStream newOutputStream(OutputStream out) {
        return new ZstdOutputStream(out);
    }

    /// Wraps `out` in a `java.io` compressing stream at the default level,
    /// compressing against `dictionary`.
    ///
    /// The stream owns native memory: the caller must [ZstdOutputStream#close()] it,
    /// which finishes the frame and closes `out`.
    ///
    /// @param out        the stream to write the compressed frame to
    /// @param dictionary the dictionary to compress against
    /// @return a new compressing stream
    /// @throws NullPointerException if `out` is `null`
    /// @throws ZstdException        if the native allocation or dictionary load fails
    public static ZstdOutputStream newOutputStream(OutputStream out, ZstdDictionary dictionary) {
        return new ZstdOutputStream(out, dictionary);
    }

    /// Wraps `out` in a `java.io` compressing stream that declares the
    /// exact total number of bytes that will be written, so the frame header
    /// records the decompressed size — letting a reader use [#decompress(byte[])]
    /// instead of supplying a bound. Writing a different total raises an error
    /// when the stream closes.
    ///
    /// Arguments are validated before any native allocation, and a failure while
    /// configuring the stream releases it without touching `out`. The stream owns
    /// native memory: the caller must [ZstdOutputStream#close()] it, which finishes
    /// the frame and closes `out`.
    ///
    /// @param out            the stream to write the compressed frame to
    /// @param pledgedSrcSize the exact number of uncompressed bytes that will be written
    /// @return a new compressing stream that stamps the content size into the frame
    /// @throws NullPointerException if any argument is `null`
    /// @throws ZstdException        if the native allocation or configuration fails
    public static ZstdOutputStream newPledgedOutputStream(OutputStream out, ZstdByteSize pledgedSrcSize) {
        return ZstdOutputStream.withPledgedSize(out, pledgedSrcSize);
    }

    /// Wraps `in` in a `java.io` stream that decompresses the zstd frames it carries.
    ///
    /// The stream owns native memory: the caller must [ZstdInputStream#close()] it,
    /// which also closes `in`.
    ///
    /// @param in the stream to read the compressed frame from
    /// @return a new decompressing stream
    /// @throws NullPointerException if `in` is `null`
    /// @throws ZstdException        if the native allocation fails
    public static ZstdInputStream newInputStream(InputStream in) {
        return new ZstdInputStream(in);
    }

    /// Wraps `in` in a `java.io` stream that decompresses frames compressed against
    /// `dictionary`.
    ///
    /// The stream owns native memory: the caller must [ZstdInputStream#close()] it,
    /// which also closes `in`.
    ///
    /// @param in         the stream to read the compressed frame from
    /// @param dictionary the dictionary the frame was compressed against, or `null` for none
    /// @return a new decompressing stream
    /// @throws NullPointerException if `in` is `null`
    /// @throws ZstdException        if the native allocation or dictionary load fails
    public static ZstdInputStream newInputStream(InputStream in, ZstdDictionary dictionary) {
        return new ZstdInputStream(in, dictionary);
    }

    // --- package-private helpers shared with the context classes ---
    // Native-call status checking and segment guards live in NativeCall.

    static MemorySegment copyIn(Arena arena, byte[] src) {
        MemorySegment seg = arena.allocate(Math.max(src.length, 1));
        MemorySegment.copy(src, 0, seg, JAVA_BYTE, 0, src.length);
        return seg;
    }

    static byte[] copyOut(MemorySegment seg, long len) {
        byte[] out = new byte[Math.toIntExact(len)];
        MemorySegment.copy(seg, JAVA_BYTE, 0, out, 0, out.length);
        return out;
    }

    private Zstd() {
        // no instances
    }
}
