package io.github.dfa1.zstd;

import java.lang.foreign.Arena;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.invoke.MethodHandle;
import java.util.Objects;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/// The inclusive range of values a compression or decompression parameter
/// accepts, from `ZSTD_cParam_getBounds` / `ZSTD_dParam_getBounds`.
public final class ZstdBounds {

    // Offsets into the returned ZSTD_bounds struct, derived from the named layout
    // rather than hand-counted, so they track the struct definition.
    private static final long ERROR_OFFSET = Bindings.BOUNDS_LAYOUT.byteOffset(PathElement.groupElement("error"));
    private static final long LOWER_OFFSET = Bindings.BOUNDS_LAYOUT.byteOffset(PathElement.groupElement("lowerBound"));
    private static final long UPPER_OFFSET = Bindings.BOUNDS_LAYOUT.byteOffset(PathElement.groupElement("upperBound"));

    private final int lowerBound;
    private final int upperBound;

    private ZstdBounds(int lowerBound, int upperBound) {
        this.lowerBound = lowerBound;
        this.upperBound = upperBound;
    }

    /// Creates a [ZstdBounds] from its components; only the library itself builds these.
    ///
    /// @param lowerBound the smallest accepted value, inclusive
    /// @param upperBound the largest accepted value, inclusive
    /// @return the new [ZstdBounds]
    static ZstdBounds of(int lowerBound, int upperBound) {
        return new ZstdBounds(lowerBound, upperBound);
    }

    /// Calls a `*_getBounds` function (which returns a `ZSTD_bounds` struct by
    /// value: `{ size_t error; int lowerBound; int upperBound; }`).
    static ZstdBounds query(MethodHandle getBounds, int parameter) {
        try (Arena arena = Arena.ofConfined()) {
            // getBounds returns a ZSTD_bounds struct by value. For a struct return,
            // the FFM linker prepends a SegmentAllocator parameter to the handle:
            // it allocates BOUNDS_LAYOUT.byteSize() bytes from that allocator, the
            // native call writes the struct there, and the handle returns a segment
            // viewing it. Passing the arena makes the struct arena-owned (freed on
            // close); the cast satisfies invokeExact's exact-type requirement.
            MemorySegment bounds = (MemorySegment) getBounds.invokeExact((SegmentAllocator) arena, parameter);
            long error = bounds.get(JAVA_LONG, ERROR_OFFSET);
            if (NativeCall.isError(error)) {
                throw new ZstdException("parameter has no queryable bounds");
            }
            return new ZstdBounds(bounds.get(JAVA_INT, LOWER_OFFSET), bounds.get(JAVA_INT, UPPER_OFFSET));
        } catch (Throwable t) {
            throw NativeCall.rethrow(t);
        }
    }

    /// The smallest accepted value, inclusive.
    ///
    /// @return the smallest accepted value, inclusive
    public int lowerBound() {
        return lowerBound;
    }

    /// The largest accepted value, inclusive.
    ///
    /// @return the largest accepted value, inclusive
    public int upperBound() {
        return upperBound;
    }

    /// Value equality over all components.
    ///
    /// @param o the object to compare with
    /// @return `true` if `o` is a [ZstdBounds] with equal components
    @Override
    public boolean equals(Object o) {
        return o instanceof ZstdBounds other
                && lowerBound == other.lowerBound
                && upperBound == other.upperBound;
    }

    /// Hash code consistent with [#equals(Object)].
    ///
    /// @return the component-based hash code
    @Override
    public int hashCode() {
        return Objects.hash(lowerBound, upperBound);
    }

    /// Record-style description listing every component.
    ///
    /// @return this value as `ZstdBounds[component=value, ...]`, naming every component
    @Override
    public String toString() {
        return "ZstdBounds[lowerBound=" + lowerBound + ", upperBound=" + upperBound + "]";
    }
}
