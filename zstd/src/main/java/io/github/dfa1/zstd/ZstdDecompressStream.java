package io.github.dfa1.zstd;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Objects;

import static java.lang.foreign.ValueLayout.JAVA_LONG;

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
    // size_t cells the _simpleArgs calls advance: bytes written to dst, read from src
    private final MemorySegment dstPos;
    private final MemorySegment srcPos;

    ZstdDecompressStream() {
        this(null);
    }

    @SuppressWarnings("java:S1181") // loadDictionary wraps MethodHandle.invokeExact (throws Throwable); must catch Throwable
    ZstdDecompressStream(ZstdDictionary dictionary) {
        // Own the context first, so any failure setting it up is cleaned up by
        // close() — one release path, no leak on a half-built stream.
        super(createDctx());
        try {
            // Inside the try: a failed arena or allocation must still free the
            // context (and the arena, once it exists) through close().
            this.arena = Arena.ofConfined();
            this.dstPos = arena.allocate(JAVA_LONG);
            this.srcPos = arena.allocate(JAVA_LONG);
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

    /// Sets the largest back-reference window this stream will allocate a
    /// buffer for, as a power of two: a memory limit against hostile or
    /// oversized frames. A frame declaring a larger window fails with
    /// [ZstdErrorCode#FRAME_PARAMETER_WINDOW_TOO_LARGE]. Raise it to stream
    /// frames built with a `windowLog` above the default limit, `27` (128 MiB);
    /// [ZstdWindowLog#AUTO] restores that default.
    ///
    /// zstd checks the limit only when it buffers: a frame that declares its
    /// content size and fits in one output step is decoded in a single shot,
    /// needs no window buffer, and is not checked.
    ///
    /// Set it before the first [#decompress] call or between frames.
    ///
    /// @param windowLogMax the base-2 log of the maximum accepted window size
    /// @return `this`, for chaining
    /// @throws ZstdException if a frame is in progress
    /// @throws IllegalStateException if this stream is closed
    public ZstdDecompressStream windowLogMax(ZstdWindowLog windowLogMax) {
        Objects.requireNonNull(windowLogMax, "windowLogMax");
        return parameter(ZstdDecompressParameter.WINDOW_LOG_MAX, windowLogMax.value());
    }

    /// Skips validation of each frame's content checksum; see
    /// [ZstdDecompressContext#ignoreChecksum(boolean)]. Off by default.
    ///
    /// **Experimental** in upstream zstd. While on, **corrupted payloads are
    /// no longer detected** by the checksum. Set it before the first
    /// [#decompress] call or between frames.
    ///
    /// @param enabled whether to skip checksum validation
    /// @return `this`, for chaining
    /// @throws ZstdException if a frame is in progress
    /// @throws IllegalStateException if this stream is closed
    public ZstdDecompressStream ignoreChecksum(boolean enabled) {
        return parameter(ZstdDecompressParameter.FORCE_IGNORE_CHECKSUM, enabled ? 1 : 0);
    }

    private ZstdDecompressStream parameter(ZstdDecompressParameter parameter, int value) {
        NativeCall.checkReturnValue(() -> (long) Bindings.DCTX_SET_PARAMETER.invokeExact(
                ptr(), parameter.value(), value));
        return this;
    }

    /// Decompresses as much of `src` as fits into `dst` in one step.
    ///
    /// Advance the source by [ZstdStreamResult#bytesConsumed()] and read out
    /// [ZstdStreamResult#bytesProduced()] bytes of `dst` after each call. The
    /// frame is fully decoded once a result is [ZstdStreamResult#isComplete()].
    ///
    /// @param dst native, writable destination buffer for decompressed bytes
    /// @param src native source frame bytes
    /// @return how much was consumed and produced, and the remaining hint
    /// @throws ZstdException if the frame is invalid
    public ZstdStreamResult decompress(MemorySegment dst, MemorySegment src) {
        NativeCall.requireWritable(dst, "dst");
        NativeCall.requireReadable(src, "src");
        dstPos.set(JAVA_LONG, 0, 0L);
        srcPos.set(JAVA_LONG, 0, 0L);
        long remaining = NativeCall.checkReturnValue(() -> (long) Bindings.DECOMPRESS_STREAM_SIMPLE_ARGS.invokeExact(
                ptr(), dst, dst.byteSize(), dstPos, src, src.byteSize(), srcPos));
        return ZstdStreamResult.of(srcPos.get(JAVA_LONG, 0), dstPos.get(JAVA_LONG, 0), remaining);
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
            // null only when the constructor failed before creating the arena
            if (arena != null) {
                arena.close();
            }
        }
    }
}
