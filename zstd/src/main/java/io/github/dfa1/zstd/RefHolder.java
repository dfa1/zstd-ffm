package io.github.dfa1.zstd;

/// Tracks the one outstanding [NativePointerWithRefCount#retain()] a context
/// holds on a dictionary it references by pointer — the bookkeeping shared by
/// [ZstdCompressContext#refDictionary(ZstdCompressDictionary)] and
/// [ZstdDecompressContext#refDictionary(ZstdDecompressDictionary)].
final class RefHolder<D extends NativePointerWithRefCount> {

    private D held;

    /// Replaces the held reference with `next`, gated on `nativeCall` succeeding.
    ///
    /// Retains `next` (if non-`null`), then runs `nativeCall` — which may
    /// safely dereference `next`, since it is already retained. Only once
    /// `nativeCall` returns does this release whatever was previously held and
    /// store `next` as the new reference. If `nativeCall` throws, the
    /// just-acquired retain on `next` is released and the previously held
    /// reference is left untouched.
    ///
    /// @param next       the new reference to hold, or `null` to clear it
    /// @param nativeCall performs the native call that must succeed before the
    ///                   switch commits
    void set(D next, Runnable nativeCall) {
        if (next != null) {
            next.retain();
        }
        try {
            nativeCall.run();
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
