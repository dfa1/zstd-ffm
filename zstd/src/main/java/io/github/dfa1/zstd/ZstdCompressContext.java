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
/// A context that has compressed with [#workers(int)]
/// set above zero owns live native worker threads and job buffers that
/// [#reset(ZstdResetDirective)] does **not** release — only [#close()]
/// frees them. Give such a context a dedicated owner and close it when
/// done; never keep it in a long-lived pool.
///
/// {@snippet :
/// try (ZstdCompressContext ctx = Zstd.newCompressContext()) {
///     ctx.level(ZstdCompressionLevel.of(19)); // configure inside the try: a failing setter can't leak ctx
///     for (byte[] msg : messages) {
///         sink.accept(ctx.compress(msg));
///     }
/// }
/// }
public final class ZstdCompressContext extends NativeObject implements ZstdCompressSettings<ZstdCompressContext> {

    private ZstdCompressionLevel level;
    private ZstdCompressDictionary refDict;
    /// The segment last passed to [#refPrefix(MemorySegment)] that no frame has
    /// used yet, or `null`. Held in Java only: zstd is handed the prefix just
    /// before the frame that uses it, so it never keeps a pointer into an arena
    /// the caller may close in between.
    private MemorySegment pendingPrefix;

    ZstdCompressContext() {
        super(createNative());
        this.level = ZstdCompressionLevel.DEFAULT;
    }

    private static MemorySegment createNative() {
        return NativeCall.createOrThrow("ZSTD_createCCtx", () -> (MemorySegment) Bindings.CREATE_CCTX.invokeExact());
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
            refDict = NativePointerWithRefCount.release(refDict);
            pendingPrefix = null;
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
        NativeCall.requireReadable(dict, "dict");
        return loadDictionary(dict, dict.byteSize());
    }

    private ZstdCompressContext loadDictionary(MemorySegment dict, long size) {
        NativeCall.checkReturnValue(() -> (long) Bindings.CCTX_LOAD_DICTIONARY.invokeExact(ptr(), dict, size));
        refDict = NativePointerWithRefCount.release(refDict);
        pendingPrefix = null;
        return this;
    }

    /// Attaches a pre-digested `dict` to this context by reference — no per-call
    /// digesting and no copy. Subsequent [#compress(byte[])] /
    /// [#compress(MemorySegment, MemorySegment)] calls compress against it while
    /// honoring this context's advanced parameters; the compression level comes
    /// from the [ZstdCompressDictionary]. This is the hot path for a pooled context
    /// recycled with [#reset(ZstdResetDirective)] between frames.
    ///
    /// This context keeps `dict` alive for as long as it references it: closing
    /// `dict` while it is still referenced is safe, but the caller no longer
    /// controls exactly when its native memory is freed. The reference is
    /// dropped by a parameter [#reset(ZstdResetDirective)], by passing `null`,
    /// by calling [#loadDictionary(ZstdDictionary)] or [#refPrefix(MemorySegment)]
    /// (both natively supersede it), or by closing this context.
    ///
    /// @param dict the digested dictionary to reference, or `null` to clear it
    /// @return `this`, for chaining
    /// @throws ZstdException if the dictionary cannot be referenced
    public ZstdCompressContext refDictionary(ZstdCompressDictionary dict) {
        MemorySegment cctxPtr = ptr();
        refDict = NativePointerWithRefCount.swap(refDict, dict, cdict ->
                NativeCall.checkReturnValue(() -> (long) Bindings.CCTX_REF_CDICT.invokeExact(cctxPtr, cdict)));
        pendingPrefix = null;
        return this;
    }

    /// References native `prefix` content as a single-use dictionary for the
    /// **next** compression only — the building block for delta compression:
    /// compress a new version against a similar previous one as the prefix,
    /// storing little more than the difference.
    ///
    /// Unlike [#loadDictionary(ZstdDictionary)], a prefix is not digested: zstd
    /// gets a native copy when the next frame starts (no heap copy), so another
    /// thread closing `prefix`'s arena mid-frame cannot make it read freed memory.
    /// It writes no dictionary ID into the frame, and is consumed by the next
    /// [#compress(MemorySegment, MemorySegment)] / [#compress(byte[])] — it does
    /// not stick across frames. The decompressor
    /// must reference the **same** prefix with
    /// [ZstdDecompressContext#refPrefix(MemorySegment)] to decode the frame.
    ///
    /// Because the copy is taken then, `prefix` must stay valid until the next
    /// [#compress(MemorySegment, MemorySegment)] / [#compress(byte[])] uses it.
    /// The per-call dictionary overloads (`compress(src, dict)`) do **not** use
    /// it: the prefix stays pending for the next frame without one. If
    /// `prefix`'s arena is closed while the prefix is pending, that next call
    /// throws [IllegalStateException] and drops the prefix instead of reading
    /// freed memory. Heap callers that cannot manage native lifetime should use
    /// a copying dictionary ([#loadDictionary(ZstdDictionary)]) instead.
    ///
    /// @param prefix native prefix content, or `null` / [MemorySegment#NULL] to clear it
    /// @return `this`, for chaining
    /// @throws ZstdException if the prefix cannot be referenced
    /// @throws IllegalStateException if `prefix` is already closed
    /// @throws WrongThreadException if `prefix` is confined to another thread
    public ZstdCompressContext refPrefix(MemorySegment prefix) {
        if (NativeCall.isNull(prefix)) {
            return refPrefix(MemorySegment.NULL, 0L);
        }
        NativeCall.requireRetainable(prefix, "prefix");
        return refPrefix(prefix, prefix.byteSize());
    }

    private ZstdCompressContext refPrefix(MemorySegment prefix, long size) {
        // Clear natively now, as referencing a prefix always has (it drops any
        // loaded or referenced dictionary); the prefix itself is handed to zstd
        // only by compress2, right before the frame that uses it.
        clearNativePrefix();
        refDict = NativePointerWithRefCount.release(refDict);
        pendingPrefix = size == 0 ? null : prefix;
        return this;
    }

    private void clearNativePrefix() {
        NativeCall.checkReturnValue(() -> (long) Bindings.CCTX_REF_PREFIX.invokeExact(ptr(), MemorySegment.NULL, 0L));
    }

    /// Runs `ZSTD_compress2`, first handing zstd a copy of the pending prefix, if any.
    ///
    /// The prefix is single use: it is taken from [#pendingPrefix] up front and
    /// zstd consumes it at stream init whether or not compression then succeeds.
    /// zstd reads it during `ZSTD_compress2`, which does not take it as an
    /// argument, so the linker cannot keep the caller's arena open: another thread
    /// could close a shared one mid-frame (#185). zstd therefore gets a copy in a
    /// confined arena that lives exactly as long as the call. If the call fails
    /// before C ran (the linker refusing `dst`/`src`), zstd still holds the
    /// prefix, so it is cleared before the error propagates.
    private long compress2(MemorySegment dst, long dstCapacity, MemorySegment src, long srcSize) {
        MemorySegment prefix = pendingPrefix;
        pendingPrefix = null;
        if (prefix == null) {
            return NativeCall.checkReturnValue(() -> (long) Bindings.COMPRESS2.invokeExact(
                    ptr(), dst, dstCapacity, src, srcSize));
        }
        if (!prefix.scope().isAlive()) {
            throw new IllegalStateException("the prefix passed to refPrefix was closed before a frame used it");
        }
        try (Arena frame = Arena.ofConfined()) {
            MemorySegment copy = frame.allocate(prefix.byteSize()).copyFrom(prefix);
            // A failed earlier frame can leave the stream mid-stage; ZSTD_compress2 resets the
            // session itself, but refPrefix must see an idle stream. Session reset keeps
            // parameters and dictionaries.
            NativeCall.checkReturnValue(() -> (long) Bindings.CCTX_RESET.invokeExact(
                    ptr(), ZstdResetDirective.SESSION_ONLY.value()));
            NativeCall.checkReturnValue(() -> (long) Bindings.CCTX_REF_PREFIX.invokeExact(
                    ptr(), copy, copy.byteSize()));
            // With workers, a too-small dst makes ZSTD_compress2 return while jobs still read
            // the copy. A worst-case buffer lets zstd always finish the frame first.
            long bound = Zstd.compressBound(ZstdByteSize.of(srcSize)).value();
            MemorySegment out = dstCapacity >= bound ? dst : frame.allocate(bound);
            long outCapacity = out == dst ? dstCapacity : bound;
            long written = NativeCall.checkReturnValue(() -> (long) Bindings.COMPRESS2.invokeExact(
                    ptr(), out, outCapacity, src, srcSize));
            if (out != dst) {
                if (written > dstCapacity) {
                    throw new ZstdException("Destination buffer is too small", ZstdErrorCode.DST_SIZE_TOO_SMALL);
                }
                MemorySegment.copy(out, 0, dst, 0, written);
            }
            return written;
        } catch (RuntimeException e) {
            // A ZstdException means zstd ran and consumed (or never took) the prefix.
            if (!(e instanceof ZstdException)) {
                clearNativePrefix();
            }
            throw e;
        }
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
            ZstdByteSize bound = Zstd.compressBound(ZstdByteSize.of(src));
            MemorySegment out = arena.allocate(bound.value());
            long written = compress2(out, bound.value(), in, src.length);
            return Zstd.copyOut(out, written);
        }
    }

    void setParameter(ZstdCompressParameter parameter, int value) {
        NativeCall.checkReturnValue(() -> (long) Bindings.CCTX_SET_PARAMETER.invokeExact(ptr(), parameter.value(), value));
        if (parameter == ZstdCompressParameter.COMPRESSION_LEVEL) {
            // The per-call dictionary overloads read the level from this cache, so
            // level(...) must land here.
            this.level = value == 0 ? ZstdCompressionLevel.DEFAULT : ZstdCompressionLevel.of(value);
        }
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
            ZstdByteSize bound = Zstd.compressBound(ZstdByteSize.of(src));
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
            ZstdByteSize bound = Zstd.compressBound(ZstdByteSize.of(src));
            MemorySegment out = arena.allocate(bound.value());
            long written = dict.withRetained(cdict -> NativeCall.checkReturnValue(
                    () -> (long) Bindings.COMPRESS_USING_CDICT.invokeExact(
                            ptr(), out, bound.value(), in, (long) src.length, cdict)));
            return Zstd.copyOut(out, written);
        }
    }

    /// Zero-copy compression: reads `src` and writes the frame straight into
    /// `dst`, both native [MemorySegment]s the caller owns. No heap
    /// `byte[]` bounce — hand zstd the segment addresses directly. This is
    /// the fast path when your bytes are already off-heap (e.g. an mmap slice and
    /// an arena-allocated output); see `docs/explanation.md`.
    ///
    /// Size `dst` with [Zstd#compressBound(ZstdByteSize)] to guarantee it fits.
    ///
    /// Returns a raw `long` rather than a [ZstdByteSize]: on this zero-copy path
    /// the byte count feeds straight into [MemorySegment#asSlice(long, long)], so
    /// it is left unboxed by design to keep the fast path allocation-free. The
    /// heap `byte[]` overloads, which size an array, return richer types.
    ///
    /// @param dst the native, writable destination buffer to write the frame into
    /// @param src the native source bytes to compress
    /// @return the number of bytes written into `dst`
    /// @throws ZstdException if `dst` is too small or compression fails
    public long compress(MemorySegment dst, MemorySegment src) {
        NativeCall.requireWritable(dst, "dst");
        NativeCall.requireReadable(src, "src");
        return compress2(dst, dst.byteSize(), src, src.byteSize());
    }

    /// Zero-copy compression against a pre-digested `dict`, segment to segment.
    ///
    /// @param dst  the native, writable destination buffer to write the frame into
    /// @param src  the native source bytes to compress
    /// @param dict the pre-digested compression dictionary
    /// @return the number of bytes written into `dst`
    public long compress(MemorySegment dst, MemorySegment src, ZstdCompressDictionary dict) {
        NativeCall.requireWritable(dst, "dst");
        NativeCall.requireReadable(src, "src");
        return dict.withRetained(cdict -> NativeCall.checkReturnValue(
                () -> (long) Bindings.COMPRESS_USING_CDICT.invokeExact(
                        ptr(), dst, dst.byteSize(), src, src.byteSize(), cdict)));
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
        MemorySegment dst = arena.allocate(Zstd.compressBound(ZstdByteSize.of(src.byteSize())).value());
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
        MemorySegment dst = arena.allocate(Zstd.compressBound(ZstdByteSize.of(src.byteSize())).value());
        long written = compress(dst, src, dict);
        return dst.asSlice(0, written);
    }

    /// Current native memory used by this context, in bytes.
    ///
    /// @return the live context size
    public ZstdByteSize sizeOf() {
        try {
            return ZstdByteSize.of((long) Bindings.SIZEOF_CCTX.invokeExact(ptr()));
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    @Override
    protected void tryClose(MemorySegment ptr) throws Throwable {
        refDict = NativePointerWithRefCount.release(refDict);
        var _ = (long) Bindings.FREE_CCTX.invokeExact(ptr);
    }
}
