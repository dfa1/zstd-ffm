package io.github.dfa1.zstd;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Objects;

/// A reusable compression context.
///
/// Reusing one context across many [#compress] calls amortizes native
/// state allocation, making it cheaper than the stateless [Zstd#compress]
/// on hot paths. Not thread-safe: confine an instance to one thread or pool it.
///
/// A context that has compressed with [ZstdCompressParameter#NB_WORKERS]
/// set above zero owns live native worker threads and job buffers that
/// [#reset(ZstdResetDirective)] does **not** release — only [#close()]
/// frees them. Give such a context a dedicated owner and close it when
/// done; never keep it in a long-lived pool.
///
/// {@snippet :
/// try (ZstdCompressContext ctx = new ZstdCompressContext().level(new ZstdCompressionLevel(19))) {
///     for (byte[] msg : messages) {
///         sink.accept(ctx.compress(msg));
///     }
/// }
/// }
public final class ZstdCompressContext extends NativeObject {

    private ZstdCompressionLevel level;

    /// Creates a new compression context at the default level.
    public ZstdCompressContext() {
        super(create());
        this.level = ZstdCompressionLevel.DEFAULT;
    }

    private static MemorySegment create() {
        return NativeCall.createOrThrow("ZSTD_createCCtx", () -> (MemorySegment) Bindings.CREATE_CCTX.invokeExact());
    }

    /// Sets the compression level for subsequent [#compress] calls.
    ///
    /// @param level the compression level to use
    /// @return `this`, for chaining
    public ZstdCompressContext level(ZstdCompressionLevel level) {
        this.level = level;
        setParam(ZstdCompressParameter.COMPRESSION_LEVEL, level.value());
        return this;
    }

    /// Sets an advanced compression parameter for subsequent
    /// [#compress(byte[])] / [#compress(MemorySegment, MemorySegment)]
    /// calls. The setting is sticky across calls until changed.
    ///
    /// Advanced parameters do not apply to the dictionary `compress` overloads.
    ///
    /// @param parameter the parameter to set
    /// @param value     the value, validated natively against the parameter's bounds
    /// @return `this`, for chaining
    /// @throws ZstdException if the value is out of range for the parameter
    public ZstdCompressContext parameter(ZstdCompressParameter parameter, int value) {
        setParam(parameter, value);
        return this;
    }

    /// Appends a 32-bit content checksum to each frame, which decompression
    /// verifies and rejects on mismatch. Off by default.
    ///
    /// @param enabled whether to write a checksum
    /// @return `this`, for chaining
    public ZstdCompressContext checksum(boolean enabled) {
        return parameter(ZstdCompressParameter.CHECKSUM_FLAG, enabled ? 1 : 0);
    }

    /// Enables long-distance matching for a better ratio on large, repetitive inputs.
    ///
    /// @param enabled whether to enable long-distance matching
    /// @return `this`, for chaining
    public ZstdCompressContext longDistanceMatching(boolean enabled) {
        return parameter(ZstdCompressParameter.ENABLE_LONG_DISTANCE_MATCHING, enabled ? 1 : 0);
    }

    /// Sets the maximum back-reference distance as a power of two (larger window =
    /// better ratio, more memory). Decompression of large windows may require
    /// raising the decompressor's window limit.
    ///
    /// @param windowLog the base-2 log of the window size
    /// @return `this`, for chaining
    public ZstdCompressContext windowLog(ZstdWindowLog windowLog) {
        Objects.requireNonNull(windowLog, "windowLog");
        return parameter(ZstdCompressParameter.WINDOW_LOG, windowLog.value());
    }

    /// Resets this context so it can be reused for the next frame without the
    /// cost of freeing and recreating its native state.
    ///
    /// - [ZstdResetDirective#SESSION_ONLY] aborts the current frame and drops
    ///   unflushed data, keeping the level, parameters, and any dictionary.
    /// - [ZstdResetDirective#PARAMETERS] and
    ///   [ZstdResetDirective#SESSION_AND_PARAMETERS] also restore every
    ///   parameter to its default and clear the dictionary; the level returns to
    ///   [ZstdCompressionLevel#DEFAULT]. A parameter reset is valid only
    ///   between frames — one-shot [#compress(byte[])] always finishes its frame,
    ///   so this constraint only bites advanced multi-frame reuse.
    ///
    /// @param directive what to clear
    /// @return `this`, for chaining
    /// @throws ZstdException if the reset fails natively
    public ZstdCompressContext reset(ZstdResetDirective directive) {
        Objects.requireNonNull(directive, "directive");
        NativeCall.checkReturnValue(() -> (long) Bindings.CCTX_RESET.invokeExact(ptr(), directive.value()));
        if (directive != ZstdResetDirective.SESSION_ONLY) {
            this.level = ZstdCompressionLevel.DEFAULT;
        }
        return this;
    }

    /// Loads `dict` as the sticky dictionary for this context, so subsequent
    /// [#compress(byte[])] / [#compress(MemorySegment, MemorySegment)] calls
    /// compress against it **while still honoring the advanced parameters**
    /// (checksum, window log, long-distance matching) set on this context — the
    /// combination the per-call `compress(src, dict)` overloads cannot give you,
    /// since they route through the legacy dictionary path.
    ///
    /// The dictionary is copied internally and digested at the next compression
    /// using this context's level and parameters, so `dict` may be discarded
    /// afterwards.
    /// It stays loaded until replaced, cleared with [#loadDictionary(ZstdDictionary)]
    /// passing `null`, or dropped by a parameter [#reset(ZstdResetDirective)]. For
    /// a dictionary reused across many contexts, digest it once and attach it with
    /// [#refDictionary(ZstdCompressDictionary)] instead.
    ///
    /// @param dict the dictionary to load, or `null` to clear the loaded dictionary
    /// @return `this`, for chaining
    /// @throws ZstdException if the dictionary cannot be loaded
    public ZstdCompressContext loadDictionary(ZstdDictionary dict) {
        if (dict == null) {
            return loadDictionary(MemorySegment.NULL, 0L);
        }
        try (Arena arena = Arena.ofConfined()) {
            byte[] raw = dict.raw();
            return loadDictionary(Zstd.copyIn(arena, raw), raw.length);
        }
    }

    /// Loads dictionary content straight from a native [MemorySegment], without a
    /// heap copy — the zero-copy path when your dictionary is already off-heap
    /// (e.g. an mmap slice). Otherwise identical to
    /// [#loadDictionary(ZstdDictionary)].
    ///
    /// @param dict native dictionary content (its bytes are copied into the
    ///             context), or `null` / [MemorySegment#NULL] to clear the loaded dictionary
    /// @return `this`, for chaining
    /// @throws ZstdException if the dictionary cannot be loaded
    public ZstdCompressContext loadDictionary(MemorySegment dict) {
        if (NativeCall.isNull(dict)) {
            return loadDictionary(MemorySegment.NULL, 0L);
        }
        NativeCall.requireNative(dict, "dict");
        return loadDictionary(dict, dict.byteSize());
    }

    private ZstdCompressContext loadDictionary(MemorySegment dict, long size) {
        NativeCall.checkReturnValue(() -> (long) Bindings.CCTX_LOAD_DICTIONARY.invokeExact(ptr(), dict, size));
        return this;
    }

    /// Attaches a pre-digested `dict` to this context by reference — no per-call
    /// digesting and no copy. Subsequent [#compress(byte[])] /
    /// [#compress(MemorySegment, MemorySegment)] calls compress against it while
    /// honoring this context's advanced parameters; the compression level comes
    /// from the [ZstdCompressDictionary]. This is the hot path for a pooled context
    /// recycled with [#reset(ZstdResetDirective)] between frames.
    ///
    /// The reference is borrowed: `dict` must stay open for as long as this
    /// context uses it. The reference is dropped by a parameter
    /// [#reset(ZstdResetDirective)] or by passing `null`.
    ///
    /// @param dict the digested dictionary to reference, or `null` to clear it
    /// @return `this`, for chaining
    /// @throws ZstdException if the dictionary cannot be referenced
    public ZstdCompressContext refDictionary(ZstdCompressDictionary dict) {
        MemorySegment cdict = dict == null ? MemorySegment.NULL : dict.ptr();
        NativeCall.checkReturnValue(() -> (long) Bindings.CCTX_REF_CDICT.invokeExact(ptr(), cdict));
        return this;
    }

    /// References native `prefix` content as a single-use dictionary for the
    /// **next** compression only — the building block for delta compression:
    /// compress a new version against a similar previous one as the prefix,
    /// storing little more than the difference.
    ///
    /// Unlike [#loadDictionary(ZstdDictionary)], a prefix is referenced (no
    /// digest, no heap copy), writes no dictionary ID into the frame, and is
    /// consumed by the next [#compress(MemorySegment, MemorySegment)] /
    /// [#compress(byte[])] — it does not stick across frames. The decompressor
    /// must reference the **same** prefix with
    /// [ZstdDecompressContext#refPrefix(MemorySegment)] to decode the frame.
    ///
    /// Because the prefix is referenced, `prefix` must stay valid until the next
    /// compression completes. Heap callers that cannot manage native lifetime
    /// should use a copying dictionary ([#loadDictionary(ZstdDictionary)]) instead.
    ///
    /// @param prefix native prefix content, or `null` / [MemorySegment#NULL] to clear it
    /// @return `this`, for chaining
    /// @throws ZstdException if the prefix cannot be referenced
    public ZstdCompressContext refPrefix(MemorySegment prefix) {
        if (NativeCall.isNull(prefix)) {
            return refPrefix(MemorySegment.NULL, 0L);
        }
        NativeCall.requireNative(prefix, "prefix");
        return refPrefix(prefix, prefix.byteSize());
    }

    private ZstdCompressContext refPrefix(MemorySegment prefix, long size) {
        NativeCall.checkReturnValue(() -> (long) Bindings.CCTX_REF_PREFIX.invokeExact(ptr(), prefix, size));
        return this;
    }

    /// Compresses `src` into a new zstd frame using this context and its
    /// advanced parameters.
    ///
    /// @param src the bytes to compress
    /// @return a self-describing zstd frame
    public byte[] compress(byte[] src) {
        Objects.requireNonNull(src, "src");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = Zstd.copyIn(arena, src);
            ZstdByteSize bound = Zstd.compressBound(new ZstdByteSize(src.length));
            MemorySegment out = arena.allocate(bound.value());
            long written = NativeCall.checkReturnValue(() -> (long) Bindings.COMPRESS2.invokeExact(
                    ptr(), out, bound.value(), in, (long) src.length));
            return Zstd.copyOut(out, written);
        }
    }

    private void setParam(ZstdCompressParameter parameter, int value) {
        NativeCall.checkReturnValue(() -> (long) Bindings.CCTX_SET_PARAMETER.invokeExact(ptr(), parameter.value(), value));
    }

    /// Compresses `src` against `dict` at this context's level.
    ///
    /// The dictionary is re-digested on every call; for repeated compression
    /// against the same dictionary, digest it once into a [ZstdCompressDictionary]
    /// and use [#compress(byte[], ZstdCompressDictionary)].
    ///
    /// @param src  the bytes to compress
    /// @param dict the dictionary to compress against
    /// @return a self-describing zstd frame
    public byte[] compress(byte[] src, ZstdDictionary dict) {
        Objects.requireNonNull(src, "src");
        Objects.requireNonNull(dict, "dict");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = Zstd.copyIn(arena, src);
            byte[] d = dict.raw();
            MemorySegment dseg = Zstd.copyIn(arena, d);
            ZstdByteSize bound = Zstd.compressBound(new ZstdByteSize(src.length));
            MemorySegment out = arena.allocate(bound.value());
            long written = NativeCall.checkReturnValue(() -> (long) Bindings.COMPRESS_USING_DICT.invokeExact(
                    ptr(), out, bound.value(), in, (long) src.length, dseg, (long) d.length, level.value()));
            return Zstd.copyOut(out, written);
        }
    }

    /// Compresses `src` against a pre-digested `dict` (the level was
    /// fixed when the [ZstdCompressDictionary] was built).
    ///
    /// @param src  the bytes to compress
    /// @param dict the pre-digested compression dictionary
    /// @return a self-describing zstd frame
    public byte[] compress(byte[] src, ZstdCompressDictionary dict) {
        Objects.requireNonNull(src, "src");
        Objects.requireNonNull(dict, "dict");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = Zstd.copyIn(arena, src);
            ZstdByteSize bound = Zstd.compressBound(new ZstdByteSize(src.length));
            MemorySegment out = arena.allocate(bound.value());
            MemorySegment cdict = dict.ptr();
            long written = NativeCall.checkReturnValue(() -> (long) Bindings.COMPRESS_USING_CDICT.invokeExact(
                    ptr(), out, bound.value(), in, (long) src.length, cdict));
            return Zstd.copyOut(out, written);
        }
    }

    /// Zero-copy compression: reads `src` and writes the frame straight into
    /// `dst`, both native [MemorySegment]s the caller owns. No heap
    /// `byte[]` bounce — hand zstd the segment addresses directly. This is
    /// the fast path when your bytes are already off-heap (e.g. an mmap slice and
    /// an arena-allocated output); see `docs/zero-copy.md`.
    ///
    /// Size `dst` with [Zstd#compressBound(ZstdByteSize)] to guarantee it fits.
    ///
    /// Returns a raw `long` rather than a [ZstdByteSize]: on this zero-copy path
    /// the byte count feeds straight into [MemorySegment#asSlice(long, long)], so
    /// it is left unboxed by design to keep the fast path allocation-free. The
    /// heap `byte[]` overloads, which size an array, return richer types.
    ///
    /// @param dst the native destination buffer to write the frame into
    /// @param src the native source bytes to compress
    /// @return the number of bytes written into `dst`
    /// @throws ZstdException if `dst` is too small or compression fails
    public long compress(MemorySegment dst, MemorySegment src) {
        NativeCall.requireNative(dst, "dst");
        NativeCall.requireNative(src, "src");
        return NativeCall.checkReturnValue(() -> (long) Bindings.COMPRESS2.invokeExact(
                ptr(), dst, dst.byteSize(), src, src.byteSize()));
    }

    /// Zero-copy compression against a pre-digested `dict`, segment to segment.
    ///
    /// @param dst  the native destination buffer to write the frame into
    /// @param src  the native source bytes to compress
    /// @param dict the pre-digested compression dictionary
    /// @return the number of bytes written into `dst`
    public long compress(MemorySegment dst, MemorySegment src, ZstdCompressDictionary dict) {
        NativeCall.requireNative(dst, "dst");
        NativeCall.requireNative(src, "src");
        MemorySegment cdict = dict.ptr();
        return NativeCall.checkReturnValue(() -> (long) Bindings.COMPRESS_USING_CDICT.invokeExact(
                ptr(), dst, dst.byteSize(), src, src.byteSize(), cdict));
    }

    /// Zero-copy compression that allocates the output for you: reserves a
    /// worst-case buffer ([Zstd#compressBound(ZstdByteSize)]) in `arena`,
    /// compresses into it, and returns a slice trimmed to the actual frame length.
    /// The returned segment is owned by `arena`.
    ///
    /// @param arena the arena to allocate the output segment in
    /// @param src   the native source bytes to compress
    /// @return the zstd frame, a slice of an `arena`-owned segment
    public MemorySegment compress(Arena arena, MemorySegment src) {
        MemorySegment dst = arena.allocate(Zstd.compressBound(new ZstdByteSize(src.byteSize())).value());
        long written = compress(dst, src);
        return dst.asSlice(0, written);
    }

    /// Zero-copy compression against a pre-digested `dict`, allocating the
    /// output in `arena` and returning a slice trimmed to the frame length.
    ///
    /// @param arena the arena to allocate the output segment in
    /// @param src   the native source bytes to compress
    /// @param dict  the pre-digested compression dictionary
    /// @return the zstd frame, a slice of an `arena`-owned segment
    public MemorySegment compress(Arena arena, MemorySegment src, ZstdCompressDictionary dict) {
        MemorySegment dst = arena.allocate(Zstd.compressBound(new ZstdByteSize(src.byteSize())).value());
        long written = compress(dst, src, dict);
        return dst.asSlice(0, written);
    }

    /// Current native memory used by this context, in bytes.
    ///
    /// @return the live context size
    public ZstdByteSize sizeOf() {
        try {
            return new ZstdByteSize((long) Bindings.SIZEOF_CCTX.invokeExact(ptr()));
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    @Override
    protected void tryClose(MemorySegment ptr) throws Throwable {
        var _ = (long) Bindings.FREE_CCTX.invokeExact(ptr);
    }
}
