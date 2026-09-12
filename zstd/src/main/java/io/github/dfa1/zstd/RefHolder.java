package io.github.dfa1.zstd;

import java.lang.foreign.MemorySegment;
import java.util.function.Consumer;

/// Tracks the one outstanding [NativePointerWithRefCount#retain()] a context
/// holds on a dictionary it references by pointer — the bookkeeping shared by
/// [ZstdCompressContext#refDictionary(ZstdCompressDictionary)] and
/// [ZstdDecompressContext#refDictionary(ZstdDecompressDictionary)].
final class RefHolder<D extends NativePointerWithRefCount> {

    private D held;

    /// Replaces the held reference with `next`, gated on `nativeCall` succeeding.
    ///
    /// Retains `next` (if non-`null`), then passes `nativeCall` its live
    /// pointer — [MemorySegment#NULL] if `next` is `null` — to perform the
    /// native call that must succeed before the switch commits. Only once
    /// `nativeCall` returns does this release whatever was previously held and
    /// store `next` as the new reference. If `nativeCall` throws, the
    /// just-acquired retain on `next` is released and the previously held
    /// reference is left untouched.
    ///
    /// @param next       the new reference to hold, or `null` to clear it
    /// @param nativeCall performs the native call, given `next`'s pointer (or
    ///                   [MemorySegment#NULL])
    void set(D next, Consumer<MemorySegment> nativeCall) {
        if (next != null) {
            next.retain();
        }
        MemorySegment ptr = next == null ? MemorySegment.NULL : next.ptr();
        try {
            nativeCall.accept(ptr);
        } catch (RuntimeException e) {
            if (next != null) {
                next.release();
            }
            throw e;
        }
        release();
        held = next;
    }

    /// Releases the currently held reference, if any.
    void release() {
        if (held != null) {
            held.release();
            held = null;
        }
    }
}
