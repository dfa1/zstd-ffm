package io.github.dfa1.zstd;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Objects;

/// A dictionary digested once for decompression.
///
/// Pre-processes the dictionary so each
/// [ZstdDecompressContext#decompress(byte[], ZstdByteSize, ZstdDecompressDictionary)]
/// call skips that cost. The raw [ZstdDictionary] bytes are copied into native
/// memory, so the source may be discarded afterwards.
///
/// Immutable once built and safe to share across threads (the digested dictionary is read-only).
/// Closing it while another thread is still using it is safe too: the native
/// memory is freed only once that call returns.
///
/// A [ZstdDecompressContext] may hold a live reference to this dictionary via
/// [ZstdDecompressContext#refDictionary(ZstdDecompressDictionary)]; the
/// underlying native dictionary is only freed once every such borrower has
/// released it, so closing this object while a context still references it
/// is safe.
public final class ZstdDecompressDictionary extends NativePointerWithRefCount {

    ZstdDecompressDictionary(ZstdDictionary dict) {
        super(createNative(dict));
    }

    ZstdDecompressDictionary(MemorySegment dict) {
        super(createNative(dict));
    }

    private static MemorySegment createNative(ZstdDictionary dict) {
        Objects.requireNonNull(dict, "dict");
        try (Arena arena = Arena.ofConfined()) {
            byte[] raw = dict.raw();
            MemorySegment d = Zstd.copyIn(arena, raw);
            return NativeCall.createOrThrow("ZSTD_createDDict",
                    () -> (MemorySegment) Bindings.CREATE_DDICT.invokeExact(d, (long) raw.length));
        }
    }

    private static MemorySegment createNative(MemorySegment dict) {
        NativeCall.requireReadable(dict, "dict");
        return NativeCall.createOrThrow("ZSTD_createDDict",
                () -> (MemorySegment) Bindings.CREATE_DDICT.invokeExact(dict, dict.byteSize()));
    }

    /// The dictionary id this dictionary decodes frames for.
    ///
    /// @return the dictionary id, or [ZstdDictionaryId#NONE] for a content-only dictionary
    public ZstdDictionaryId id() {
        return ZstdDictionaryId.of((int) withRetained(
                dict -> NativeCall.invoke(() -> (int) Bindings.GET_DICT_ID_FROM_DDICT.invokeExact(dict))));
    }

    /// Current native memory used by this digested dictionary, in bytes.
    ///
    /// @return the live dictionary size
    public ZstdByteSize sizeOf() {
        return ZstdByteSize.of(withRetained(
                dict -> NativeCall.invoke(() -> (long) Bindings.SIZEOF_DDICT.invokeExact(dict))));
    }

    @Override
    protected void tryClose(MemorySegment ptr) throws Throwable {
        var _ = (long) Bindings.FREE_DDICT.invokeExact(ptr);
    }
}
