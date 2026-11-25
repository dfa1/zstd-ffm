package io.github.dfa1.zstd;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicReference;

/// Base class for wrappers that own a native zstd pointer.
///
/// The pointer lives in an [AtomicReference] that is swapped to
/// [MemorySegment#NULL] on [#close()], so [#tryClose] runs
/// exactly once even under concurrent or repeated close calls.
abstract class NativeObject implements AutoCloseable {

    private final AtomicReference<MemorySegment> ptr;
    // the only thread allowed to close, or null when any thread may
    private final Thread owner;

    /// Takes ownership of a freshly created native pointer, closable from any
    /// thread. Package-private: only this library's contexts and segment streams
    /// extend it.
    ///
    /// @param owningPointer the non-NULL native pointer this object now owns
    NativeObject(MemorySegment owningPointer) {
        this(owningPointer, null);
    }

    /// Takes ownership of a freshly created native pointer, closable only from
    /// `owner`.
    ///
    /// @param owningPointer the non-NULL native pointer this object now owns
    /// @param owner         the only thread allowed to close, or `null` if any thread may
    NativeObject(MemorySegment owningPointer, Thread owner) {
        this.ptr = new AtomicReference<>(owningPointer);
        this.owner = owner;
    }

    /// Returns the live native pointer, failing if this object is already closed.
    ///
    /// @return the live native pointer
    /// @throws IllegalStateException if this object has been closed
    protected final MemorySegment ptr() {
        MemorySegment p = ptr.get();
        if (MemorySegment.NULL.equals(p)) {
            throw new IllegalStateException("native object is closed");
        }
        return p;
    }

    @Override
    public final void close() {
        if (owner != null) {
            NativeCall.requireOwnerThread(owner);
        }
        MemorySegment p = ptr.getAndSet(MemorySegment.NULL);
        if (!MemorySegment.NULL.equals(p)) {
            try {
                tryClose(p);
            } catch (Throwable _) {
                // destructors must not throw
            }
        }
    }

    /// Releases the native resource. Called exactly once with a non-NULL pointer.
    ///
    /// @param ptr the non-NULL native pointer to free
    /// @throws Throwable if the native free call fails; the exception is swallowed by [#close()]
    @SuppressWarnings("java:S112") // implementations wrap MethodHandle.invokeExact, declared to throw Throwable
    protected abstract void tryClose(MemorySegment ptr) throws Throwable;
}
