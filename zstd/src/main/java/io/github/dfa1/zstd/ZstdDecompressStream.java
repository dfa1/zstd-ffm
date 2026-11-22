package io.github.dfa1.zstd;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/// A low-level, zero-copy streaming decompressor driven with native
/// [MemorySegment] buffers — no heap `byte[]` bounce.
///
/// Use this when both ends are already off-heap; for ordinary `java.io`
/// pipelines prefer [ZstdInputStream]. Feed compressed input, drain the
/// destination when it fills; a result is [ZstdStreamResult#isComplete()] when
/// the current frame is fully decoded.
///
/// Not thread-safe: confine an instance to a single thread.
public final class ZstdDecompressStream extends NativeObject {

    private final Arena arena;
    private final ZstdStreamBuffer in;
    private final ZstdStreamBuffer out;

    ZstdDecompressStream() {
        this(null);
    }

    @SuppressWarnings("java:S1181") // loadDictionary wraps MethodHandle.invokeExact (throws Throwable); must catch Throwable
    ZstdDecompressStream(ZstdDictionary dictionary) {
        // Own the context first, so any failure setting it up is cleaned up by
        // close() — one release path, no leak on a half-built stream.
        super(createDctx());
        this.arena = Arena.ofConfined();
        this.in = new ZstdStreamBuffer(arena);
        this.out = new ZstdStreamBuffer(arena);
        try {
            if (dictionary != null) {
                loadDictionary(dictionary);
            }
        } catch (Throwable t) {
            close();
            throw NativeCall.rethrow(t);
        }
    }

    private static MemorySegment createDctx() {
        return NativeCall.createOrThrow("ZSTD_createDCtx", () -> (MemorySegment) Bindings.CREATE_DCTX.invokeExact());
    }

    private void loadDictionary(ZstdDictionary dictionary) {
        try (Arena staging = Arena.ofConfined()) {
            byte[] raw = dictionary.raw();
            MemorySegment d = Zstd.copyIn(staging, raw);
            NativeCall.checkReturnValue(() -> (long) Bindings.DCTX_LOAD_DICTIONARY.invokeExact(
                    ptr(), d, (long) raw.length));
        }
    }

    /// Decompresses as much of `src` as fits into `dst` in one step.
    ///
    /// Advance the source by [ZstdStreamResult#bytesConsumed()] and read out
    /// [ZstdStreamResult#bytesProduced()] bytes of `dst` after each call. The
    /// frame is fully decoded once a result is [ZstdStreamResult#isComplete()].
    ///
    /// @param dst native destination buffer for decompressed bytes
    /// @param src native source frame bytes
    /// @return how much was consumed and produced, and the remaining hint
    /// @throws ZstdException if the frame is invalid
    public ZstdStreamResult decompress(MemorySegment dst, MemorySegment src) {
        in.set(src, src.byteSize());
        out.set(dst, dst.byteSize());
        long remaining = NativeCall.checkReturnValue(() -> (long) Bindings.DECOMPRESS_STREAM.invokeExact(
                ptr(), out.segment(), in.segment()));
        return ZstdStreamResult.of(in.pos(), out.pos(), remaining);
    }

    /// Current native memory used by this stream's context, in bytes.
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
        try {
            var _ = (long) Bindings.FREE_DCTX.invokeExact(ptr);
        } finally {
            arena.close();
        }
    }
}
