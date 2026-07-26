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
public final class ZstdDecompressDictionary extends NativeObject {

    /// Digests `dict` for decompression.
    ///
    /// @param dict the dictionary to digest
    public ZstdDecompressDictionary(ZstdDictionary dict) {
        super(create(dict));
    }

    /// Digests a native dictionary segment for decompression, without a heap copy.
    ///
    /// `dict` must be a native (off-heap) [MemorySegment] — e.g. an mmap slice or
    /// an arena buffer. Its bytes are copied into the digested dictionary, so the
    /// segment may be released once the constructor returns. Heap-backed callers
    /// should use [ZstdDecompressDictionary(ZstdDictionary)] instead.
    ///
    /// @param dict native dictionary content
    public ZstdDecompressDictionary(MemorySegment dict) {
        super(create(dict));
    }

    private static MemorySegment create(ZstdDictionary dict) {
        Objects.requireNonNull(dict, "dict");
        try (Arena arena = Arena.ofConfined()) {
            byte[] raw = dict.raw();
            MemorySegment d = Zstd.copyIn(arena, raw);
            return NativeCall.createOrThrow("ZSTD_createDDict",
                    () -> (MemorySegment) Bindings.CREATE_DDICT.invokeExact(d, (long) raw.length));
        }
    }

    private static MemorySegment create(MemorySegment dict) {
        NativeCall.requireNative(dict, "dict");
        return NativeCall.createOrThrow("ZSTD_createDDict",
                () -> (MemorySegment) Bindings.CREATE_DDICT.invokeExact(dict, dict.byteSize()));
    }

    /// The dictionary id this dictionary decodes frames for.
    ///
    /// @return the dictionary id, or [ZstdDictionaryId#NONE] for a content-only dictionary
    public ZstdDictionaryId id() {
        try {
            return ZstdDictionaryId.of((int) Bindings.GET_DICT_ID_FROM_DDICT.invokeExact(ptr()));
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    /// Current native memory used by this digested dictionary, in bytes.
    ///
    /// @return the live dictionary size
    public ZstdByteSize sizeOf() {
        try {
            return new ZstdByteSize((long) Bindings.SIZEOF_DDICT.invokeExact(ptr()));
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    @Override
    protected void tryClose(MemorySegment ptr) throws Throwable {
        var _ = (long) Bindings.FREE_DDICT.invokeExact(ptr);
    }
}
