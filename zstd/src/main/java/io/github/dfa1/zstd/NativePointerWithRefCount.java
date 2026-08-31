package io.github.dfa1.zstd;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
public abstract class NativePointerWithRefCount implements AutoCloseable {

    private final MemorySegment ptr;
    private final AtomicInteger refCount = new AtomicInteger(1); // the constructor's own reference
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /// Takes the first reference on a freshly created native pointer.
    ///
    /// @param owningPointer the non-NULL native pointer this object now shares ownership of
    protected NativePointerWithRefCount(MemorySegment owningPointer) {
        this.ptr = owningPointer;
    }

    /// Returns the live native pointer, failing if every reference has already been released.
    ///
    /// @return the live native pointer
    /// @throws IllegalStateException if the last reference has already been released
    protected final MemorySegment ptr() {
        if (refCount.get() == 0) {
            throw new IllegalStateException("native object is closed");
        }
        return ptr;
    }

    /// Acquires a new reference on top of the constructor's own.
    ///
    /// @throws IllegalStateException if the last reference has already been released —
    ///                                a released count never resurrects
    void retain() {
        if (refCount.getAndUpdate(c -> c == 0 ? 0 : c + 1) == 0) {
            throw new IllegalStateException("native object is closed");
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

    /// Releases the native resource. Called at most once, when the last
    /// outstanding reference — the constructor's own included — is released.
    ///
    /// @param ptr the native pointer to free
    /// @throws Throwable if the native free call fails; the exception is swallowed by [#release()]
    @SuppressWarnings("java:S112") // implementations wrap MethodHandle.invokeExact, declared to throw Throwable
    protected abstract void tryClose(MemorySegment ptr) throws Throwable;
}
