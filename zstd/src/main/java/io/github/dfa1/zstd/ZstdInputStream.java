package io.github.dfa1.zstd;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Objects;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/// A streaming zstd decompressor that reads a zstd frame from an underlying
/// [InputStream].
///
/// Memory use is bounded and independent of the frame size, so this decodes
/// arbitrarily large or incrementally-arriving compressed data. For a complete
/// in-memory frame, [Zstd#decompress(byte[])] or [ZstdDecompressContext]
/// is simpler.
///
/// Closing closes the underlying stream.
///
/// {@snippet :
/// try (ZstdInputStream zin = Zstd.newInputStream(Files.newInputStream(path))) {
///     zin.transferTo(sink);
/// }
/// }
///
/// Not thread-safe: confine an instance to a single thread.
public final class ZstdInputStream extends InputStream {

    private final InputStream in;
    private final Arena arena;
    private final MemorySegment dctx;
    private final MemorySegment inSeg;
    private final MemorySegment outSeg;
    private final long outCap;
    private final ZstdStreamBuffer inBuf;
    private final ZstdStreamBuffer outBufView;
    private final byte[] feed;
    private final byte[] hold;
    private final byte[] single;
    private int holdStart;
    private int holdEnd;
    private boolean inputEof;
    private boolean closed;
    /// zstd's "bytes still expected" hint from the last decompressStream call;
    /// non-zero at EOF means the final frame was truncated.
    private long lastHint;

    ZstdInputStream(InputStream in) {
        this(in, null);
    }

    ZstdInputStream(InputStream in, ZstdDictionary dictionary) {
        this.in = Objects.requireNonNull(in, "in");
        this.arena = Arena.ofConfined();
        this.single = new byte[1];
        MemorySegment d = null;
        try {
            this.inBuf = new ZstdStreamBuffer(arena);
            this.outBufView = new ZstdStreamBuffer(arena);
            d = NativeCall.createOrThrow("ZSTD_createDCtx", () -> (MemorySegment) Bindings.CREATE_DCTX.invokeExact());
            this.dctx = d;
            if (dictionary != null) {
                loadDictionary(dictionary);
            }
            long inCap = (long) Bindings.DSTREAM_IN_SIZE.invokeExact();
            this.outCap = (long) Bindings.DSTREAM_OUT_SIZE.invokeExact();
            this.inSeg = arena.allocate(inCap);
            this.outSeg = arena.allocate(outCap);
            this.feed = new byte[Math.toIntExact(inCap)];
            this.hold = new byte[Math.toIntExact(outCap)];
        } catch (Throwable t) {
            // Free the context if it was created, then the arena, so a failed
            // constructor leaks neither the native dctx nor the arena buffers.
            if (d != null && !MemorySegment.NULL.equals(d)) {
                freeDctx(d);
            }
            arena.close();
            throw NativeCall.rethrow(t);
        }
    }

    private static void freeDctx(MemorySegment dctx) {
        try {
            var _ = (long) Bindings.FREE_DCTX.invokeExact(dctx);
        } catch (Throwable _) {
            // best-effort free
        }
    }

    private void loadDictionary(ZstdDictionary dictionary) {
        try (Arena staging = Arena.ofConfined()) {
            byte[] raw = dictionary.raw();
            MemorySegment dictSeg = Zstd.copyIn(staging, raw);
            NativeCall.checkReturnValue(() -> (long) Bindings.DCTX_LOAD_DICTIONARY.invokeExact(
                    dctx, dictSeg, (long) raw.length));
        }
    }

    @Override
    public int read() throws IOException {
        int n = read(single, 0, 1);
        return n == -1 ? -1 : (single[0] & 0xFF);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        ensureOpen();
        java.util.Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) {
            return 0;
        }
        if (holdStart == holdEnd && !produce()) {
            return -1;
        }
        int n = Math.min(len, holdEnd - holdStart);
        System.arraycopy(hold, holdStart, b, off, n);
        holdStart += n;
        return n;
    }

    /// Decodes the next slice of output into `hold`. Returns false at end of stream.
    // The branching here (input refill, truncation detection, no-progress guard) is
    // essential to correct EOF/truncation handling and is covered by tests; splitting
    // it would obscure the decode loop rather than clarify it.
    @SuppressWarnings("java:S3776") // cognitive complexity is inherent to the streaming decode/EOF logic
    private boolean produce() throws IOException {
        while (true) {
            if (inBuf.pos() == inBuf.size()) {
                int r = inputEof ? -1 : in.read(feed);
                if (r == -1) {
                    inputEof = true;
                    // The frame boundary is clean only when the last decompressStream
                    // call reported nothing outstanding; otherwise the stream was cut
                    // mid-frame and the remaining bytes are lost.
                    if (lastHint != 0) {
                        throw new ZstdException("truncated zstd stream: " + lastHint
                                + " more input byte(s) expected");
                    }
                    return false;
                }
                MemorySegment.copy(feed, 0, inSeg, JAVA_BYTE, 0, r);
                inBuf.set(inSeg, r);
            }
            outBufView.set(outSeg, outCap);
            lastHint = NativeCall.checkReturnValue(() -> (long) Bindings.DECOMPRESS_STREAM.invokeExact(
                    dctx, outBufView.segment(), inBuf.segment()));
            int produced = Math.toIntExact(outBufView.pos());
            if (produced > 0) {
                MemorySegment.copy(outSeg, JAVA_BYTE, 0, hold, 0, produced);
                holdStart = 0;
                holdEnd = produced;
                return true;
            }
            // Nothing produced. If the decoder neither advanced its input nor wants
            // more, it cannot make progress on this input — stop to avoid spinning.
            // (Input drained but not at EOF: fall through and loop to refill from `in`.)
            if (inBuf.pos() == inBuf.size() && inputEof) {
                if (lastHint != 0) {
                    throw new ZstdException("truncated zstd stream: " + lastHint
                            + " more input byte(s) expected");
                }
                return false;
            }
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
    /// Set it right after construction, before the first `read`.
    ///
    /// @param windowLogMax the base-2 log of the maximum accepted window size
    /// @return `this`, for chaining
    /// @throws ZstdException if a frame is in progress
    /// @throws IllegalStateException if this stream is closed
    public ZstdInputStream windowLogMax(ZstdWindowLog windowLogMax) {
        Objects.requireNonNull(windowLogMax, "windowLogMax");
        return parameter(ZstdDecompressParameter.WINDOW_LOG_MAX, windowLogMax.value());
    }

    /// Skips validation of each frame's content checksum; see
    /// [ZstdDecompressContext#ignoreChecksum(boolean)]. Off by default.
    ///
    /// **Experimental** in upstream zstd. While on, **corrupted payloads are
    /// no longer detected** by the checksum. Set it right after construction,
    /// before the first `read`.
    ///
    /// @param enabled whether to skip checksum validation
    /// @return `this`, for chaining
    /// @throws ZstdException if a frame is in progress
    /// @throws IllegalStateException if this stream is closed
    public ZstdInputStream ignoreChecksum(boolean enabled) {
        return parameter(ZstdDecompressParameter.FORCE_IGNORE_CHECKSUM, enabled ? 1 : 0);
    }

    private ZstdInputStream parameter(ZstdDecompressParameter parameter, int value) {
        if (closed) {
            // close() frees the dctx: setting a parameter on it would be a use-after-free
            throw new IllegalStateException("stream closed");
        }
        NativeCall.checkReturnValue(() -> (long) Bindings.DCTX_SET_PARAMETER.invokeExact(
                dctx, parameter.value(), value));
        return this;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        freeDctx(dctx);
        arena.close();
        in.close();
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("stream closed");
        }
    }
}
