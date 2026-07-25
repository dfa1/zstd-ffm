package io.github.dfa1.zstd;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

// Backing for ZSTD_inBuffer / ZSTD_outBuffer, which share a layout of a
// void pointer then two size_t values: { void* ptr; size_t size; size_t pos; }.
// The struct shape, its total size and the field offsets are all derived from
// LAYOUT below rather than hardcoded.
final class ZstdStreamBuffer {

    private static final MemoryLayout.PathElement PTR = MemoryLayout.PathElement.groupElement("ptr");
    private static final MemoryLayout.PathElement SIZE = MemoryLayout.PathElement.groupElement("size");
    private static final MemoryLayout.PathElement POS = MemoryLayout.PathElement.groupElement("pos");

    private static final java.lang.foreign.StructLayout LAYOUT = MemoryLayout.structLayout(
            ADDRESS.withName("ptr"),
            JAVA_LONG.withName("size"),
            JAVA_LONG.withName("pos"));

    // Layout VarHandles have coordinates (MemorySegment, long base); we always pass base 0.
    private static final VarHandle PTR_HANDLE = LAYOUT.varHandle(PTR);
    private static final VarHandle SIZE_HANDLE = LAYOUT.varHandle(SIZE);
    private static final VarHandle POS_HANDLE = LAYOUT.varHandle(POS);

    private final MemorySegment struct;

    ZstdStreamBuffer(Arena arena) {
        this.struct = arena.allocate(LAYOUT);
    }

    MemorySegment segment() {
        return struct;
    }

    /// Points the buffer at `buffer` with the given size and a fresh position of 0.
    void set(MemorySegment buffer, long size) {
        PTR_HANDLE.set(struct, 0L, buffer);
        SIZE_HANDLE.set(struct, 0L, size);
        POS_HANDLE.set(struct, 0L, 0L);
    }

    long size() {
        return (long) SIZE_HANDLE.get(struct, 0L);
    }

    long pos() {
        return (long) POS_HANDLE.get(struct, 0L);
    }
}
