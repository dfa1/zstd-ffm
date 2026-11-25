package io.github.dfa1.zstd;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/// Package-private helpers that adapt raw FFM downcalls to the zstd error
/// convention: run a native call, decode a zstd `size_t` error code into a
/// [ZstdException], and guard native-segment arguments. Shared by the
/// binding classes so the conventions live in one place.
final class NativeCall {

    /// A native call returning a zstd `size_t` status that may encode an error.
    @FunctionalInterface
    @SuppressWarnings("java:S112") // wraps MethodHandle.invokeExact, which is declared to throw Throwable
    interface ZstdCall {
        long run() throws Throwable;
    }

    /// Invokes a native call whose result is not a zstd status code (an id, a
    /// size), rethrowing whatever it throws unchecked.
    static long invoke(ZstdCall c) {
        try {
            return c.run();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /// Invokes a size-returning zstd call and converts a zstd error code into a
    /// [ZstdException].
    static long checkReturnValue(ZstdCall c) {
        long code;
        try {
            code = c.run();
        } catch (Throwable t) {
            throw rethrow(t);
        }
        if (isError(code)) {
            throw new ZstdException(errorName(code), ZstdErrorCode.of(errorCode(code)));
        }
        return code;
    }

    /// A native factory call returning a freshly allocated object pointer.
    @FunctionalInterface
    @SuppressWarnings("java:S112") // wraps MethodHandle.invokeExact, which is declared to throw Throwable
    interface NativeFactory {
        MemorySegment create() throws Throwable;
    }

    /// Runs a zstd `create`-style call and rejects the `NULL` it returns on
    /// allocation failure, raising a [ZstdException] that names the call.
    static MemorySegment createOrThrow(String what, NativeFactory factory) {
        MemorySegment p;
        try {
            p = factory.create();
        } catch (Throwable t) {
            throw rethrow(t);
        }
        if (MemorySegment.NULL.equals(p)) {
            throw new ZstdException(what + " returned NULL");
        }
        return p;
    }

    static boolean isError(long code) {
        try {
            return ((int) Bindings.IS_ERROR.invokeExact(code)) != 0;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    private static int errorCode(long code) {
        try {
            return (int) Bindings.GET_ERROR_CODE.invokeExact(code);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @SuppressWarnings("restricted") // reinterpret needed to read a C string of unknown length
    private static String errorName(long code) {
        try {
            MemorySegment p = (MemorySegment) Bindings.GET_ERROR_NAME.invokeExact(code);
            return p.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.US_ASCII);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /// Whether `seg` denotes "no segment": either a Java `null` reference or the
    /// [MemorySegment#NULL] zero-address sentinel. Both map to a null pointer in C,
    /// which the dictionary entry points read as "clear".
    static boolean isNull(MemorySegment seg) {
        return seg == null || MemorySegment.NULL.equals(seg);
    }

    /// The shared base of [#requireReadable(MemorySegment, String)] and
    /// [#requireWritable(MemorySegment, String)]: the segment handed to zstd must
    /// be backed by native (off-heap) memory, since its address is dereferenced in
    /// C. Fails fast with a clear message instead of the FFM linker's cryptic
    /// error. Entry points call one of those two, so each site states whether zstd
    /// reads or writes the segment.
    ///
    /// Liveness and thread confinement are left to the linker: every guarded
    /// segment is then passed as a direct downcall argument, which the linker
    /// checks and keeps alive for the call. Never store a caller's segment address
    /// into a native struct instead, since the linker cannot check that.
    static void requireNative(MemorySegment seg, String name) {
        Objects.requireNonNull(seg, name);
        if (!seg.isNative()) {
            throw new IllegalArgumentException(
                    name + " must be a native (off-heap) MemorySegment; got a heap segment");
        }
    }

    /// Guards a zero-copy input (a source, frame, dictionary or prefix) that zstd
    /// only reads through: it must be native, and may be read-only, so a read-only
    /// mmap of a compressed file is accepted.
    static void requireReadable(MemorySegment seg, String name) {
        requireNative(seg, name);
    }

    /// Guards a segment the library holds onto after the call returns, rather than
    /// passing it as a downcall argument (where the FFM linker would check it):
    /// it must be readable, still alive, and accessible from the calling thread.
    /// Throws what the linker would: [IllegalStateException] for a closed
    /// segment, [WrongThreadException] for one confined to another thread.
    static void requireRetainable(MemorySegment seg, String name) {
        requireReadable(seg, name);
        if (!seg.scope().isAlive()) {
            throw new IllegalStateException(name + " is already closed");
        }
        if (!seg.isAccessibleBy(Thread.currentThread())) {
            throw new WrongThreadException(name + " is confined to another thread");
        }
    }

    /// Guards a zero-copy destination: on top of [#requireNative(MemorySegment, String)],
    /// the segment must be writable, since zstd writes through its address. The FFM
    /// linker passes a read-only segment's address unchecked, so without this a
    /// read-only mmap faults the JVM (SIGBUS/SIGSEGV) and an
    /// [MemorySegment#asReadOnly()] view of writable memory is silently overwritten.
    static void requireWritable(MemorySegment seg, String name) {
        requireNative(seg, name);
        if (seg.isReadOnly()) {
            throw new IllegalArgumentException(
                    name + " must be a writable MemorySegment; got a read-only segment");
        }
    }

    /// Guards closing a stream whose buffers live in a confined arena: only the
    /// thread that created it may close it. Fails before anything is freed, so the
    /// stream stays whole and its owner can still use and close it; otherwise the
    /// native context would be freed and the arena leaked, since only its owner
    /// can close it.
    static void requireOwnerThread(Thread owner) {
        if (owner != Thread.currentThread()) {
            throw new WrongThreadException("stream is confined to " + owner + "; close it from that thread");
        }
    }

    /// Rethrows any `Throwable` as if unchecked, laundering the checked
    /// `Throwable` that [java.lang.invoke.MethodHandle#invokeExact] declares.
    /// The shared sink for every binding class's native-call catch blocks.
    @SuppressWarnings("unchecked")
    static <E extends Throwable> RuntimeException rethrow(Throwable t) throws E {
        throw (E) t;
    }

    private NativeCall() {
        // no instances
    }
}
