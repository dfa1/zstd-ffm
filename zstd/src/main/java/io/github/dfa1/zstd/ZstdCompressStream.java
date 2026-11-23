package io.github.dfa1.zstd;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/// A low-level, zero-copy streaming compressor driven with native
/// [MemorySegment] buffers — no heap `byte[]` bounce.
///
/// Use this when both ends are already off-heap and you want full control over
/// buffering; for ordinary `java.io` pipelines prefer [ZstdOutputStream].
///
/// Drive it like the C API: feed a source segment, drain the destination when it
/// fills, and finish with [ZstdEndDirective#END] until [ZstdStreamResult#isComplete()].
///
/// {@snippet :
/// try (ZstdCompressStream cs = Zstd.newCompressStream(level)) {
///     ZstdStreamResult r;
///     do {
///         r = cs.compress(dst, src.asSlice(srcOff), ZstdEndDirective.END);
///         srcOff += r.bytesConsumed();
///         sink.write(dst.asSlice(0, r.bytesProduced()));
///     } while (!r.isComplete());
/// }
/// }
///
/// Not thread-safe: confine an instance to a single thread.
public final class ZstdCompressStream extends NativeObject {

    private final Arena arena;
    private final ZstdStreamBuffer in;
    private final ZstdStreamBuffer out;

    ZstdCompressStream() {
        this(ZstdCompressionLevel.DEFAULT);
    }

    ZstdCompressStream(ZstdCompressionLevel level) {
        this(level, null);
    }

    ZstdCompressStream(ZstdCompressionLevel level, ZstdDictionary dictionary) {
        // Own the context first, so any failure setting it up is cleaned up by
        // close() — one release path, no leak on a half-built stream.
        super(createCctx());
        this.arena = Arena.ofConfined();
        this.in = new ZstdStreamBuffer(arena);
        this.out = new ZstdStreamBuffer(arena);
        try {
            NativeCall.checkReturnValue(() -> (long) Bindings.CCTX_SET_PARAMETER.invokeExact(
                    ptr(), ZstdCompressParameter.COMPRESSION_LEVEL.value(), level.value()));
            if (dictionary != null) {
                loadDictionary(dictionary);
            }
        } catch (Throwable t) {
            close();
            throw NativeCall.rethrow(t);
        }
    }

    private static MemorySegment createCctx() {
        return NativeCall.createOrThrow("ZSTD_createCCtx", () -> (MemorySegment) Bindings.CREATE_CCTX.invokeExact());
    }

    private void loadDictionary(ZstdDictionary dictionary) {
        try (Arena staging = Arena.ofConfined()) {
            byte[] raw = dictionary.raw();
            MemorySegment d = Zstd.copyIn(staging, raw);
            NativeCall.checkReturnValue(() -> (long) Bindings.CCTX_LOAD_DICTIONARY.invokeExact(
                    ptr(), d, (long) raw.length));
        }
    }

    /// Sets an advanced compression parameter — e.g.
    /// [ZstdCompressParameter#NB_WORKERS] for multithreaded streaming
    /// compression. Set it right after construction, before the first
    /// [#compress] call; changing it mid-frame is not supported.
    ///
    /// @param parameter the parameter to set
    /// @param value     the value, validated natively against the parameter's bounds
    /// @return `this`, for chaining
    /// @throws ZstdException if the value is out of range for the parameter
    public ZstdCompressStream parameter(ZstdCompressParameter parameter, int value) {
        NativeCall.checkReturnValue(() -> (long) Bindings.CCTX_SET_PARAMETER.invokeExact(
                ptr(), parameter.value(), value));
        return this;
    }

    /// Compresses as much of `src` as fits into `dst` in one step.
    ///
    /// Advance the source by [ZstdStreamResult#bytesConsumed()] and write out
    /// [ZstdStreamResult#bytesProduced()] bytes of `dst` after each call. With
    /// [ZstdEndDirective#END], repeat until [ZstdStreamResult#isComplete()].
    ///
    /// @param dst       native, writable destination buffer for compressed bytes
    /// @param src       native source bytes to compress
    /// @param directive whether to continue, flush, or end the frame
    /// @return how much was consumed and produced, and the remaining hint
    /// @throws ZstdException if compression fails
    public ZstdStreamResult compress(MemorySegment dst, MemorySegment src, ZstdEndDirective directive) {
        NativeCall.requireWritable(dst, "dst");
        NativeCall.requireReadable(src, "src");
        in.set(src, src.byteSize());
        out.set(dst, dst.byteSize());
        long remaining = NativeCall.checkReturnValue(() -> (long) Bindings.COMPRESS_STREAM2.invokeExact(
                ptr(), out.segment(), in.segment(), directive.value()));
        return ZstdStreamResult.of(in.pos(), out.pos(), remaining);
    }

    /// A live snapshot of how much this stream has ingested, compressed, produced,
    /// and flushed so far — useful for progress reporting on a long stream.
    ///
    /// @return the current frame progression
    public ZstdFrameProgression progress() {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment p = (MemorySegment) Bindings.GET_FRAME_PROGRESSION.invokeExact(
                    (SegmentAllocator) scratch, ptr());
            return ZstdFrameProgression.of(
                    p.get(JAVA_LONG, 0),    // ingested
                    p.get(JAVA_LONG, 8),    // consumed
                    p.get(JAVA_LONG, 16),   // produced
                    p.get(JAVA_LONG, 24),   // flushed
                    p.get(JAVA_INT, 32),    // currentJobID
                    p.get(JAVA_INT, 36));   // nbActiveWorkers
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    /// Current native memory used by this stream's context, in bytes.
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
        try {
            var _ = (long) Bindings.FREE_CCTX.invokeExact(ptr);
        } finally {
            arena.close();
        }
    }
}
