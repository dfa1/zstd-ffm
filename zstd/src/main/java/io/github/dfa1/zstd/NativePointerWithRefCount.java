package io.github.dfa1.zstd;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.ToLongFunction;

/// Base class for a native pointer shared by more than one borrower — the
/// `shared_ptr` shape, where [NativeObject]'s unconditional free on
/// [NativeObject#close()] would be wrong.
///
/// The constructor takes the first reference. [#retain()] acquires another,
/// on top of whichever ones are already outstanding; [#release()] gives one
/// back, freeing the native pointer only when the count reaches zero.
/// [#close()] releases the constructor's own reference and, unlike the
/// retain/release pair — which is the caller's own discipline to balance —
/// is safe to call more than once.
abstract class NativePointerWithRefCount implements AutoCloseable {

    /// Reachable only through [#withRetained(ToLongFunction)] and
    /// [#swap(NativePointerWithRefCount, NativePointerWithRefCount, Consumer)],
    /// both of which hold a reference while native code uses it: there is
    /// deliberately no bare accessor, so a native call without one cannot compile.
    private final MemorySegment ptr;
    private final AtomicInteger refCount;
    private final AtomicBoolean closed;

    /// Takes the first reference on a freshly created native pointer.
    /// Package-private: only this library's digested dictionaries extend it.
    ///
    /// @param owningPointer the non-NULL native pointer this object now shares ownership of
    NativePointerWithRefCount(MemorySegment owningPointer) {
        this.ptr = owningPointer;
        this.refCount = new AtomicInteger(1); // the constructor's own reference
        this.closed = new AtomicBoolean(false);
    }

    /// Acquires a new reference on top of the constructor's own.
    ///
    /// @throws IllegalStateException if this handle was closed, or the last
    ///                               reference has already been released — a
    ///                               released count never resurrects
    void retain() {
        if (closed.get()) {
            throw new IllegalStateException("native object is closed");
        }
        if (refCount.getAndUpdate(c -> c == 0 ? 0 : c + 1) == 0) {
            throw new IllegalStateException("native object is closed");
        }
    }

    /// Runs `nativeCall` with this handle's pointer while holding a reference on
    /// it, so a [#close()] from another thread during the call defers the native
    /// free until the call returns instead of freeing the pointer under it.
    ///
    /// @param nativeCall the native call, given the live pointer
    /// @return what `nativeCall` returns
    /// @throws IllegalStateException if this handle is already closed
    final long withRetained(ToLongFunction<MemorySegment> nativeCall) {
        retain();
        try {
            // Holding a reference keeps the pointer valid even if another thread
            // closes this handle after retain() succeeded.
            return nativeCall.applyAsLong(ptr);
        } finally {
            release();
        }
    }

    /// Releases one reference acquired by [#retain()]. Frees the native pointer
    /// when this was the last outstanding reference.
    void release() {
        if (refCount.decrementAndGet() == 0) {
            try {
                tryClose(ptr);
            } catch (Throwable _) {
                // destructors must not throw
            }
        }
    }

    @Override
    public final void close() {
        if (closed.compareAndSet(false, true)) {
            release();
        }
    }

    /// Releases `held` (if any) — the counterpart to a field that stores a
    /// borrowed reference, e.g. `refDict = NativePointerWithRefCount.release(refDict)`.
    ///
    /// @param held the currently held reference, or `null`
    /// @return `null`, always — assign it back to the caller's field
    static <D extends NativePointerWithRefCount> D release(D held) {
        if (held != null) {
            held.release();
        }
        return null;
    }

    /// Swaps a field holding a borrowed reference from `held` to `next`, gated
    /// on `nativeCall` succeeding.
    ///
    /// Retains `next` (if non-`null`), then passes `nativeCall` its live
    /// pointer — [MemorySegment#NULL] if `next` is `null` — to perform the
    /// native call that must succeed before the switch commits. Only once
    /// `nativeCall` returns does this release `held` (if any). If `nativeCall`
    /// throws, the just-acquired retain on `next` is released and `held` is
    /// returned unchanged.
    ///
    /// @param held       the reference currently held, or `null`
    /// @param next       the new reference to hold, or `null` to clear it
    /// @param nativeCall performs the native call, given `next`'s pointer (or
    ///                   [MemorySegment#NULL])
    /// @return the reference the caller's field should now hold: `next` on
    ///         success, `held` unchanged if `nativeCall` throws
    static <D extends NativePointerWithRefCount> D swap(D held, D next, Consumer<MemorySegment> nativeCall) {
        if (next != null) {
            next.retain();
        }
        // The retain above keeps next's pointer valid even if another thread closes
        // `next` right after it.
        MemorySegment nextPtr = next == null ? MemorySegment.NULL : ((NativePointerWithRefCount) next).ptr;
        try {
            nativeCall.accept(nextPtr);
        } catch (RuntimeException e) {
            if (next != null) {
                next.release();
            }
            throw e;
        }
        release(held);
        return next;
    }

    /// Releases the native resource. Called at most once, when the last
    /// outstanding reference — the constructor's own included — is released.
    ///
    /// @param ptr the native pointer to free
    /// @throws Throwable if the native free call fails; the exception is swallowed by `release()`
    @SuppressWarnings("java:S112") // implementations wrap MethodHandle.invokeExact, declared to throw Throwable
    protected abstract void tryClose(MemorySegment ptr) throws Throwable;
}
