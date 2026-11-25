package io.github.dfa1.zstd;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Objects;
import java.util.function.Consumer;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/// A streaming zstd compressor that writes a single zstd frame to an underlying
/// [OutputStream].
///
/// Memory use is bounded and independent of the total payload size, so this is
/// the way to compress data that does not fit in memory or arrives incrementally
/// (files, sockets, serialization). For a whole in-memory payload of known size,
/// [Zstd#compress(byte[])] or [ZstdCompressContext] is simpler.
///
/// Closing finishes the frame and closes the underlying stream.
///
/// {@snippet :
/// try (ZstdOutputStream zout = Zstd.newOutputStream(Files.newOutputStream(path))) {
///     zout.level(ZstdCompressionLevel.of(19)); // configure inside the try: a failing setter can't leak zout
///     source.transferTo(zout);
/// }
/// }
///
/// Not thread-safe: confine an instance to the thread that created it.
/// [#close()] from another thread throws [WrongThreadException] and frees nothing.
public final class ZstdOutputStream extends OutputStream implements ZstdCompressSettings<ZstdOutputStream> {

    // ZSTD_EndDirective values from zstd.h — see
    // https://facebook.github.io/zstd/doc/api_manual_latest.html
    private static final int ZSTD_E_CONTINUE = 0;
    private static final int ZSTD_E_FLUSH = 1;
    private static final int ZSTD_E_END = 2;

    private final OutputStream out;
    private final Arena arena;
    private final MemorySegment cctx;
    private final MemorySegment inSeg;
    private final MemorySegment outSeg;
    private final long inCap;
    private final long outCap;
    private final ZstdStreamBuffer in;
    private final ZstdStreamBuffer outBuf;
    private final byte[] drain;
    private final byte[] single;
    private final Thread owner;
    private boolean closed;

    ZstdOutputStream(OutputStream out) {
        this(out, null);
    }

    /// Builds a compressing stream that declares `pledgedSrcSize` up front; the
    /// public entry point is [Zstd#newPledgedOutputStream(OutputStream, ZstdByteSize)].
    ///
    /// Arguments are validated before any native allocation, and a failure while
    /// setting the pledged size releases the native context and arena without
    /// touching `out`.
    ///
    /// @param out            the stream to write the compressed frame to
    /// @param pledgedSrcSize the exact number of uncompressed bytes that will be written
    /// @return a stream that will stamp the content size into the frame
    static ZstdOutputStream withPledgedSize(OutputStream out, ZstdByteSize pledgedSrcSize) {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(pledgedSrcSize, "pledgedSrcSize");
        long size = pledgedSrcSize.value();
        return initOrRelease(new ZstdOutputStream(out), stream -> stream.setPledgedSrcSize(size));
    }

    /// Runs `init` on a freshly built `stream`; if it throws, frees the stream's
    /// native resources (without writing to or closing the wrapped stream) and
    /// rethrows, so a half-configured stream never leaks.
    ///
    /// @param stream the newly constructed stream
    /// @param init   the configuration step to apply
    /// @return `stream`, once `init` succeeded
    static ZstdOutputStream initOrRelease(ZstdOutputStream stream, Consumer<ZstdOutputStream> init) {
        try {
            init.accept(stream);
            return stream;
        } catch (RuntimeException | Error e) {
            stream.release();
            throw e;
        }
    }

    /// Frees the native context and arena without finishing the frame or closing
    /// the wrapped stream; later writes fail as on a closed stream.
    private void release() {
        if (closed) {
            return;
        }
        closed = true;
        freeCctx(cctx);
        arena.close();
    }

    private void setPledgedSrcSize(long pledgedSrcSize) {
        NativeCall.checkReturnValue(() -> (long) Bindings.CCTX_SET_PLEDGED_SRC_SIZE.invokeExact(cctx, pledgedSrcSize));
    }

    ZstdOutputStream(OutputStream out, ZstdDictionary dictionary) {
        this.out = Objects.requireNonNull(out, "out");
        this.owner = Thread.currentThread();
        this.arena = Arena.ofConfined();
        this.single = new byte[1];
        MemorySegment c = null;
        try {
            this.in = new ZstdStreamBuffer(arena);
            this.outBuf = new ZstdStreamBuffer(arena);
            c = NativeCall.createOrThrow("ZSTD_createCCtx", () -> (MemorySegment) Bindings.CREATE_CCTX.invokeExact());
            this.cctx = c;
            if (dictionary != null) {
                loadDictionary(dictionary);
            }
            this.inCap = (long) Bindings.CSTREAM_IN_SIZE.invokeExact();
            this.outCap = (long) Bindings.CSTREAM_OUT_SIZE.invokeExact();
            this.inSeg = arena.allocate(inCap);
            this.outSeg = arena.allocate(outCap);
            this.drain = new byte[Math.toIntExact(outCap)];
        } catch (Throwable t) {
            // Free the context if it was created, then the arena, so a failed
            // constructor leaks neither the native cctx nor the arena buffers.
            if (c != null && !MemorySegment.NULL.equals(c)) {
                freeCctx(c);
            }
            arena.close();
            throw NativeCall.rethrow(t);
        }
    }

    private static void freeCctx(MemorySegment cctx) {
        try {
            var _ = (long) Bindings.FREE_CCTX.invokeExact(cctx);
        } catch (Throwable _) {
            // best-effort free
        }
    }

    private void loadDictionary(ZstdDictionary dictionary) {
        try (Arena staging = Arena.ofConfined()) {
            byte[] raw = dictionary.raw();
            MemorySegment dictSeg = Zstd.copyIn(staging, raw);
            NativeCall.checkReturnValue(() -> (long) Bindings.CCTX_LOAD_DICTIONARY.invokeExact(
                    cctx, dictSeg, (long) raw.length));
        }
    }

    void setParameter(ZstdCompressParameter parameter, int value) {
        if (closed) {
            // close() frees the cctx: setting a parameter on it would be a use-after-free
            throw new IllegalStateException("stream closed");
        }
        NativeCall.checkReturnValue(() -> (long) Bindings.CCTX_SET_PARAMETER.invokeExact(
                cctx, parameter.value(), value));
    }

    @Override
    public void write(int b) throws IOException {
        single[0] = (byte) b;
        write(single, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        ensureOpen();
        java.util.Objects.checkFromIndexSize(off, len, b.length);
        int pos = off;
        int remaining = len;
        while (remaining > 0) {
            int chunk = (int) Math.min(remaining, inCap);
            MemorySegment.copy(b, pos, inSeg, JAVA_BYTE, 0, chunk);
            in.set(inSeg, chunk);
            do {
                drainOutput(ZSTD_E_CONTINUE);
            } while (in.pos() < chunk);
            pos += chunk;
            remaining -= chunk;
        }
    }

    @Override
    public void flush() throws IOException {
        ensureOpen();
        in.set(inSeg, 0);
        long remainingHint;
        do {
            remainingHint = drainOutput(ZSTD_E_FLUSH);
        } while (remainingHint != 0);
        out.flush();
    }

    @Override
    public void close() throws IOException {
        NativeCall.requireOwnerThread(owner);
        if (closed) {
            return;
        }
        try {
            in.set(inSeg, 0);
            long remainingHint;
            do {
                remainingHint = drainOutput(ZSTD_E_END);
            } while (remainingHint != 0);
            out.flush();
        } finally {
            closed = true;
            freeCctx(cctx);
            arena.close();
            out.close();
        }
    }

    /// Runs one compressStream2 call and writes whatever it produced to `out`.
    /// Returns the zstd "remaining" hint (0 means the directive is fully flushed).
    private long drainOutput(int directive) throws IOException {
        outBuf.set(outSeg, outCap);
        long remainingHint = NativeCall.checkReturnValue(() -> (long) Bindings.COMPRESS_STREAM2.invokeExact(
                cctx, outBuf.segment(), in.segment(), directive));
        int produced = Math.toIntExact(outBuf.pos());
        if (produced > 0) {
            MemorySegment.copy(outSeg, JAVA_BYTE, 0, drain, 0, produced);
            out.write(drain, 0, produced);
        }
        return remainingHint;
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("stream closed");
        }
    }
}
