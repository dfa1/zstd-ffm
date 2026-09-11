package io.github.dfa1.zstd;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Objects;

/// A dictionary digested once for compression at a fixed level.
///
/// Building a `ZstdCompressDictionary` pre-processes the dictionary so that each
/// [ZstdCompressContext#compress(byte[], ZstdCompressDictionary)] call skips that
/// cost — the right choice when compressing many payloads against the same
/// dictionary. The raw [ZstdDictionary] bytes are copied into native
/// memory, so the source may be discarded afterwards.
///
/// Immutable once built and safe to share across threads (the digested dictionary is read-only).
///
/// A [ZstdCompressContext] may hold a live reference to this dictionary via
/// [ZstdCompressContext#refDictionary(ZstdCompressDictionary)]; the underlying
/// native dictionary is only freed once every such borrower has released it,
/// so closing this object while a context still references it is safe.
public final class ZstdCompressDictionary extends NativePointerWithRefCount {

    private final ZstdCompressionLevel level;

    /// Digests `dict` for compression at the given level.
    ///
    /// @param dict  the dictionary to digest
    /// @param level the compression level to fix for this digested dictionary
    public ZstdCompressDictionary(ZstdDictionary dict, ZstdCompressionLevel level) {
        super(create(dict, level));
        this.level = level;
    }

    /// Digests `dict` for compression at the library default level.
    ///
    /// @param dict the dictionary to digest
    public ZstdCompressDictionary(ZstdDictionary dict) {
        this(dict, ZstdCompressionLevel.DEFAULT);
    }

    /// Digests a native dictionary segment for compression at the given level,
    /// without a heap copy.
    ///
    /// `dict` must be a native (off-heap) [MemorySegment] — e.g. an mmap slice or
    /// an arena buffer. Its bytes are copied into the digested dictionary, so the
    /// segment may be released once the constructor returns. Heap-backed callers
    /// should use [ZstdCompressDictionary(ZstdDictionary, ZstdCompressionLevel)] instead.
    ///
    /// @param dict  native dictionary content
    /// @param level the compression level to fix for this digested dictionary
    public ZstdCompressDictionary(MemorySegment dict, ZstdCompressionLevel level) {
        super(create(dict, level));
        this.level = level;
    }

    /// Digests a native dictionary segment for compression at the library default
    /// level, without a heap copy.
    ///
    /// @param dict native dictionary content
    public ZstdCompressDictionary(MemorySegment dict) {
        this(dict, ZstdCompressionLevel.DEFAULT);
    }

    private static MemorySegment create(ZstdDictionary dict, ZstdCompressionLevel level) {
        Objects.requireNonNull(dict, "dict");
        try (Arena arena = Arena.ofConfined()) {
            byte[] raw = dict.raw();
            MemorySegment d = Zstd.copyIn(arena, raw);
            return NativeCall.createOrThrow("ZSTD_createCDict",
                    () -> (MemorySegment) Bindings.CREATE_CDICT.invokeExact(d, (long) raw.length, level.value()));
        }
    }

    private static MemorySegment create(MemorySegment dict, ZstdCompressionLevel level) {
        NativeCall.requireNative(dict, "dict");
        return NativeCall.createOrThrow("ZSTD_createCDict",
                () -> (MemorySegment) Bindings.CREATE_CDICT.invokeExact(dict, dict.byteSize(), level.value()));
    }

    /// The level this dictionary was digested at.
    ///
    /// @return the fixed compression level
    public ZstdCompressionLevel level() {
        return level;
    }

    /// The dictionary id stamped into frames compressed with this dictionary.
    ///
    /// @return the dictionary id, or [ZstdDictionaryId#NONE] for a content-only dictionary
    public ZstdDictionaryId id() {
        try {
            return ZstdDictionaryId.of((int) Bindings.GET_DICT_ID_FROM_CDICT.invokeExact(ptr()));
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    /// Current native memory used by this digested dictionary, in bytes.
    ///
    /// @return the live dictionary size
    public ZstdByteSize sizeOf() {
        try {
            return new ZstdByteSize((long) Bindings.SIZEOF_CDICT.invokeExact(ptr()));
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    @Override
    protected void tryClose(MemorySegment ptr) throws Throwable {
        var _ = (long) Bindings.FREE_CDICT.invokeExact(ptr);
    }
}
