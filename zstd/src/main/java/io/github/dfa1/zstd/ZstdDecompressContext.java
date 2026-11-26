package io.github.dfa1.zstd;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Objects;

/// A reusable decompression context.
///
/// Reusing one context across many [#decompress] calls amortizes native
/// state allocation, making it cheaper than the stateless [Zstd#decompress]
/// on hot paths. Not thread-safe: confine an instance to one thread or pool it.
///
/// {@snippet :
/// try (ZstdDecompressContext ctx = Zstd.newDecompressContext()) {
///     for (byte[] frame : frames) {
///         sink.accept(ctx.decompress(frame));
///     }
/// }
/// }
public final class ZstdDecompressContext extends NativeObject implements ZstdDecompressSettings<ZstdDecompressContext> {

    private static final String COMPRESSED = "compressed";

    private ZstdDecompressDictionary refDict;
    /// The segment last passed to [#refPrefix(MemorySegment)] that no frame has
    /// used yet, or `null`. Held in Java only: zstd is handed the prefix just
    /// before the frame that uses it, so it never keeps a pointer into an arena
    /// the caller may close in between.
    private MemorySegment pendingPrefix;

    ZstdDecompressContext() {
        super(createNative());
    }

    private static MemorySegment createNative() {
        return NativeCall.createOrThrow("ZSTD_createDCtx", () -> (MemorySegment) Bindings.CREATE_DCTX.invokeExact());
    }

    void setParameter(ZstdDecompressParameter parameter, int value) {
        NativeCall.checkReturnValue(() -> (long) Bindings.DCTX_SET_PARAMETER.invokeExact(
                ptr(), parameter.value(), value));
    }

    /// Resets this context so it can be reused for the next frame without the
    /// cost of freeing and recreating its native state.
    ///
    /// - [ZstdResetDirective#SESSION_ONLY] aborts the current frame and drops
    ///   buffered state, keeping all parameters and any dictionary.
    /// - [ZstdResetDirective#PARAMETERS] and
    ///   [ZstdResetDirective#SESSION_AND_PARAMETERS] also restore every
    ///   parameter to its default and clear the dictionary. A parameter reset is
    ///   valid only between frames — one-shot [#decompress(byte[], ZstdByteSize)] always
    ///   consumes its frame, so this constraint only bites advanced multi-frame reuse.
    ///
    /// @param directive what to clear
    /// @return `this`, for chaining
    /// @throws ZstdException if the reset fails natively
    public ZstdDecompressContext reset(ZstdResetDirective directive) {
        Objects.requireNonNull(directive, "directive");
        NativeCall.checkReturnValue(() -> (long) Bindings.DCTX_RESET.invokeExact(ptr(), directive.value()));
        if (directive != ZstdResetDirective.SESSION_ONLY) {
            refDict = NativePointerWithRefCount.release(refDict);
            pendingPrefix = null;
        }
        return this;
    }

    /// Loads `dict` as the sticky dictionary for this context, so subsequent
    /// [#decompress(byte[], ZstdByteSize)] / [#decompress(MemorySegment, MemorySegment)]
    /// calls decode frames compressed against it while still honoring the
    /// advanced parameters (e.g. window-log max) set on this context.
    ///
    /// The dictionary is copied internally, so `dict` may be discarded afterwards.
    /// It stays loaded until replaced, cleared with [#loadDictionary(ZstdDictionary)]
    /// passing `null`, or dropped by a parameter [#reset(ZstdResetDirective)]. For
    /// a dictionary reused across many contexts, digest it once and attach it with
    /// [#refDictionary(ZstdDecompressDictionary)] instead.
    ///
    /// @param dict the dictionary to load, or `null` to clear the loaded dictionary
    /// @return `this`, for chaining
    /// @throws ZstdException if the dictionary cannot be loaded
    public ZstdDecompressContext loadDictionary(ZstdDictionary dict) {
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
    public ZstdDecompressContext loadDictionary(MemorySegment dict) {
        if (NativeCall.isNull(dict)) {
            return loadDictionary(MemorySegment.NULL, 0L);
        }
        NativeCall.requireReadable(dict, "dict");
        return loadDictionary(dict, dict.byteSize());
    }

    private ZstdDecompressContext loadDictionary(MemorySegment dict, long size) {
        NativeCall.checkReturnValue(() -> (long) Bindings.DCTX_LOAD_DICTIONARY.invokeExact(ptr(), dict, size));
        refDict = NativePointerWithRefCount.release(refDict);
        pendingPrefix = null;
        return this;
    }

    /// Attaches a pre-digested `dict` to this context by reference — no per-call
    /// digesting and no copy. Subsequent [#decompress(byte[], ZstdByteSize)] /
    /// [#decompress(MemorySegment, MemorySegment)] calls decode against it while
    /// honoring this context's advanced parameters. This is the hot path for a
    /// pooled context recycled with [#reset(ZstdResetDirective)] between frames.
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
    public ZstdDecompressContext refDictionary(ZstdDecompressDictionary dict) {
        MemorySegment dctxPtr = ptr();
        refDict = NativePointerWithRefCount.swap(refDict, dict, ddict ->
                NativeCall.checkReturnValue(() -> (long) Bindings.DCTX_REF_DDICT.invokeExact(dctxPtr, ddict)));
        pendingPrefix = null;
        return this;
    }

    /// References native `prefix` content as a single-use dictionary for decoding
    /// the **next** frame only — the decompression counterpart of
    /// [ZstdCompressContext#refPrefix(MemorySegment)]. It must be the **same** content
    /// the frame was compressed against, or decoding fails.
    ///
    /// zstd gets a native copy of `prefix` when the next frame starts (no digest,
    /// no heap copy), so another thread closing `prefix`'s arena mid-frame cannot
    /// make it read freed memory. `prefix` must stay valid until the next
    /// [#decompress(byte[], ZstdByteSize)] /
    /// [#decompress(MemorySegment, MemorySegment)], which consumes it — it does not
    /// stick across frames. The per-call dictionary overloads
    /// (`decompress(src, maxSize, dict)`) do **not** use it: the prefix stays
    /// pending for the next frame decoded without one. If `prefix`'s arena is
    /// closed while the prefix is pending, that next call throws
    /// [IllegalStateException] and drops the prefix instead of reading freed
    /// memory. Pass `null` / [MemorySegment#NULL] to clear a prefix set but not
    /// yet consumed.
    ///
    /// Heap callers that cannot manage native lifetime should use a copying
    /// dictionary ([#loadDictionary(ZstdDictionary)]) instead.
    ///
    /// @param prefix native prefix content, or `null` / [MemorySegment#NULL] to clear it
    /// @return `this`, for chaining
    /// @throws ZstdException if the prefix cannot be referenced
    /// @throws IllegalStateException if `prefix` is already closed
    /// @throws WrongThreadException if `prefix` is confined to another thread
    public ZstdDecompressContext refPrefix(MemorySegment prefix) {
        if (NativeCall.isNull(prefix)) {
            return refPrefix(MemorySegment.NULL, 0L);
        }
        NativeCall.requireRetainable(prefix, "prefix");
        return refPrefix(prefix, prefix.byteSize());
    }

    private ZstdDecompressContext refPrefix(MemorySegment prefix, long size) {
        // Clear natively now, as referencing a prefix always has (it drops any
        // loaded or referenced dictionary); the prefix itself is handed to zstd
        // only by decompressDCtx, right before the frame that uses it.
        clearNativePrefix();
        refDict = NativePointerWithRefCount.release(refDict);
        pendingPrefix = size == 0 ? null : prefix;
        return this;
    }

    private void clearNativePrefix() {
        NativeCall.checkReturnValue(() -> (long) Bindings.DCTX_REF_PREFIX.invokeExact(ptr(), MemorySegment.NULL, 0L));
    }

    /// Runs `ZSTD_decompressDCtx`, first handing zstd a copy of the pending prefix, if any.
    ///
    /// The prefix is single use: it is taken from [#pendingPrefix] up front and
    /// zstd consumes it before decoding (`ZSTD_getDDict` drops a single-use
    /// dictionary as it returns it) whether or not decompression then succeeds.
    /// zstd reads it during `ZSTD_decompressDCtx`, which does not take it as an
    /// argument, so the linker cannot keep the caller's arena open: another thread
    /// could close a shared one mid-frame (#185). zstd therefore gets a copy in a
    /// confined arena that lives exactly as long as the call. If the call fails
    /// before C ran (the linker refusing `dst`/`src`), zstd still holds the
    /// prefix, so it is cleared before the error propagates.
    private long decompressDCtx(MemorySegment dst, long dstCapacity, MemorySegment src, long srcSize) {
        MemorySegment prefix = pendingPrefix;
        pendingPrefix = null;
        if (prefix == null) {
            return NativeCall.checkReturnValue(() -> (long) Bindings.DECOMPRESS_DCTX.invokeExact(
                    ptr(), dst, dstCapacity, src, srcSize));
        }
        if (!prefix.scope().isAlive()) {
            throw new IllegalStateException("the prefix passed to refPrefix was closed before a frame used it");
        }
        try (Arena frame = Arena.ofConfined()) {
            MemorySegment copy = frame.allocate(prefix.byteSize()).copyFrom(prefix);
            NativeCall.checkReturnValue(() -> (long) Bindings.DCTX_REF_PREFIX.invokeExact(
                    ptr(), copy, copy.byteSize()));
            return NativeCall.checkReturnValue(() -> (long) Bindings.DECOMPRESS_DCTX.invokeExact(
                    ptr(), dst, dstCapacity, src, srcSize));
        } catch (RuntimeException e) {
            // A ZstdException means zstd ran and consumed (or never took) the prefix.
            if (!(e instanceof ZstdException)) {
                clearNativePrefix();
            }
            throw e;
        }
    }

    /// Decompresses a frame, sizing the output from the decompressed length
    /// stored in its header — no bound to supply when the frame already records
    /// its own size.
    ///
    /// Use this for frames you produced or otherwise trust. For **untrusted**
    /// input prefer [#decompress(byte[], ZstdByteSize)], whose explicit bound
    /// caps the allocation a hostile frame could otherwise demand.
    ///
    /// @param compressed a complete zstd frame that stores its decompressed size
    /// @return the original bytes
    /// @throws ZstdException if the frame is invalid or does not store its size
    public byte[] decompress(byte[] compressed) {
        Objects.requireNonNull(compressed, COMPRESSED);
        return decompress(compressed, Zstd.frameContentSize(compressed));
    }

    /// Decompresses a frame into a buffer of at most `maxSize` bytes.
    ///
    /// This is the safe entry point for **untrusted** input: `maxSize` caps the
    /// allocation and decode independently of the frame, so a hostile frame that
    /// declares — or omits — an oversized content size cannot drive an oversized
    /// allocation the way the size-reading [#decompress(byte[])] can. Pick a bound
    /// your caller can afford; the frame is rejected if its content exceeds it.
    ///
    /// @param compressed a complete zstd frame
    /// @param maxSize    upper bound on the decompressed length
    /// @return the original bytes (length ≤ `maxSize`)
    public byte[] decompress(byte[] compressed, ZstdByteSize maxSize) {
        Objects.requireNonNull(compressed, COMPRESSED);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = Zstd.copyIn(arena, compressed);
            MemorySegment out = arena.allocate(maxSize.toArraySize());
            long written = decompressDCtx(out, maxSize.value(), in, compressed.length);
            return Zstd.copyOut(out, written);
        }
    }

    /// Decompresses a frame that was compressed against `dict`.
    ///
    /// The dictionary is re-digested on every call; for repeated use digest it
    /// once into a [ZstdDecompressDictionary] and use
    /// [#decompress(byte[], ZstdByteSize, ZstdDecompressDictionary)].
    ///
    /// @param compressed a complete zstd frame
    /// @param maxSize    upper bound on the decompressed length
    /// @param dict       the dictionary the frame was compressed against
    /// @return the original bytes
    public byte[] decompress(byte[] compressed, ZstdByteSize maxSize, ZstdDictionary dict) {
        Objects.requireNonNull(compressed, COMPRESSED);
        Objects.requireNonNull(dict, "dict");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = Zstd.copyIn(arena, compressed);
            byte[] d = dict.raw();
            MemorySegment dseg = Zstd.copyIn(arena, d);
            MemorySegment out = arena.allocate(maxSize.toArraySize());
            long written = NativeCall.checkReturnValue(() -> (long) Bindings.DECOMPRESS_USING_DICT.invokeExact(
                    ptr(), out, maxSize.value(), in, (long) compressed.length, dseg, (long) d.length));
            return Zstd.copyOut(out, written);
        }
    }

    /// Decompresses a frame against a pre-digested `dict`.
    ///
    /// @param compressed a complete zstd frame
    /// @param maxSize    upper bound on the decompressed length
    /// @param dict       the pre-digested decompression dictionary
    /// @return the original bytes
    public byte[] decompress(byte[] compressed, ZstdByteSize maxSize, ZstdDecompressDictionary dict) {
        Objects.requireNonNull(compressed, COMPRESSED);
        Objects.requireNonNull(dict, "dict");
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = Zstd.copyIn(arena, compressed);
            MemorySegment out = arena.allocate(maxSize.toArraySize());
            long written = dict.withRetained(ddict -> NativeCall.checkReturnValue(
                    () -> (long) Bindings.DECOMPRESS_USING_DDICT.invokeExact(
                            ptr(), out, maxSize.value(), in, (long) compressed.length, ddict)));
            return Zstd.copyOut(out, written);
        }
    }

    /// Zero-copy decompression: reads the frame from `src` and writes the
    /// result straight into `dst`, both native [MemorySegment]s the
    /// caller owns. No heap `byte[]` bounce — the segment addresses go
    /// directly to zstd. This is the fast path when input is an mmap slice and
    /// output is an arena buffer that becomes the materialized array as-is;
    /// see `docs/explanation.md`.
    ///
    /// Size `dst` to the decompressed length (read it from the frame with
    /// [ZstdFrame#decompressedSize(MemorySegment)], or known out-of-band).
    ///
    /// Returns a raw `long` rather than a [ZstdByteSize]: on this zero-copy path
    /// the byte count feeds straight into [MemorySegment#asSlice(long, long)], so
    /// it is left unboxed by design to keep the fast path allocation-free. The
    /// heap `byte[]` overloads, which size an array, return richer types.
    ///
    /// @param dst the native, writable destination buffer to write the result into
    /// @param src the native source frame to decompress
    /// @return the number of bytes written into `dst`
    /// @throws ZstdException if `dst` is too small or the frame is invalid
    public long decompress(MemorySegment dst, MemorySegment src) {
        NativeCall.requireWritable(dst, "dst");
        NativeCall.requireReadable(src, "src");
        return decompressDCtx(dst, dst.byteSize(), src, src.byteSize());
    }

    /// Zero-copy decompression against a pre-digested `dict`, segment to segment.
    ///
    /// @param dst  the native, writable destination buffer to write the result into
    /// @param src  the native source frame to decompress
    /// @param dict the pre-digested decompression dictionary
    /// @return the number of bytes written into `dst`
    public long decompress(MemorySegment dst, MemorySegment src, ZstdDecompressDictionary dict) {
        NativeCall.requireWritable(dst, "dst");
        NativeCall.requireReadable(src, "src");
        return dict.withRetained(ddict -> NativeCall.checkReturnValue(
                () -> (long) Bindings.DECOMPRESS_USING_DDICT.invokeExact(
                        ptr(), dst, dst.byteSize(), src, src.byteSize(), ddict)));
    }

    /// Zero-copy decompression that sizes and allocates the output for you: reads
    /// the decompressed length from `frame`'s header, allocates a segment of
    /// exactly that size in `arena`, and decompresses into it. The returned
    /// segment is owned by `arena` and ready to use as a backing buffer.
    ///
    /// Requires the frame to store its decompressed size (frames produced by this
    /// library do); for size-less frames use [#decompress(MemorySegment, MemorySegment)]
    /// with a destination you size yourself.
    ///
    /// @param arena the arena to allocate the output segment in
    /// @param frame a complete zstd frame storing its decompressed size
    /// @return a segment of exactly the decompressed length, allocated in `arena`
    /// @throws ZstdException if the frame is invalid or stores no size
    public MemorySegment decompress(Arena arena, MemorySegment frame) {
        ZstdByteSize size = Zstd.decompressedSize(frame);
        MemorySegment out = arena.allocate(size.value());
        decompress(out, frame);
        return out;
    }

    /// Zero-copy decompression against a pre-digested `dict`, allocating the
    /// exact-sized output in `arena`.
    ///
    /// @param arena the arena to allocate the output segment in
    /// @param frame a complete zstd frame storing its decompressed size
    /// @param dict  the pre-digested decompression dictionary
    /// @return a segment of exactly the decompressed length, allocated in `arena`
    public MemorySegment decompress(Arena arena, MemorySegment frame, ZstdDecompressDictionary dict) {
        ZstdByteSize size = Zstd.decompressedSize(frame);
        MemorySegment out = arena.allocate(size.value());
        decompress(out, frame, dict);
        return out;
    }

    /// Current native memory used by this context, in bytes.
    ///
    /// @return the live context size
    public ZstdByteSize sizeOf() {
        try {
            return ZstdByteSize.of((long) Bindings.SIZEOF_DCTX.invokeExact(ptr()));
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    @Override
    protected void tryClose(MemorySegment ptr) throws Throwable {
        refDict = NativePointerWithRefCount.release(refDict);
        var _ = (long) Bindings.FREE_DCTX.invokeExact(ptr);
    }
}
