package io.github.dfa1.zstd;

import java.util.Objects;

/// Outcome of one zero-copy streaming step.
///
/// The components are raw `long`s rather than [ZstdByteSize]: on this zero-copy
/// path the byte counts feed straight into [java.lang.foreign.MemorySegment#asSlice(long, long)]
/// on every step of a streaming loop, so they are left unboxed by design to keep
/// the loop allocation-free. [#remaining()] is zstd's hint of work left, not a size.
public final class ZstdStreamResult {

    private final long bytesConsumed;
    private final long bytesProduced;
    private final long remaining;

    private ZstdStreamResult(long bytesConsumed, long bytesProduced, long remaining) {
        this.bytesConsumed = bytesConsumed;
        this.bytesProduced = bytesProduced;
        this.remaining = remaining;
    }

    /// Creates a [ZstdStreamResult] from its components; only the library itself builds these.
    ///
    /// @param bytesConsumed bytes read from the source segment
    /// @param bytesProduced bytes written to the destination segment
    /// @param remaining     zstd's hint of work left: for compression the bytes still buffered
    ///                      for the current directive, for decompression a non-zero value while
    ///                      the frame is incomplete; `0` means the directive (flush/end) or
    ///                      frame is fully done
    /// @return the new [ZstdStreamResult]
    static ZstdStreamResult of(long bytesConsumed, long bytesProduced, long remaining) {
        return new ZstdStreamResult(bytesConsumed, bytesProduced, remaining);
    }

    /// Whether the current directive or frame finished — no buffered data remains.
    ///
    /// @return `true` if `remaining` is zero
    public boolean isComplete() {
        return remaining == 0;
    }

    /// Bytes read from the source segment.
    ///
    /// @return bytes read from the source segment
    public long bytesConsumed() {
        return bytesConsumed;
    }

    /// Bytes written to the destination segment.
    ///
    /// @return bytes written to the destination segment
    public long bytesProduced() {
        return bytesProduced;
    }

    /// Zstd's hint of work left: for compression the bytes still buffered for the current
    /// directive, for decompression a non-zero value while the frame is incomplete; `0` means
    /// the directive (flush/end) or frame is fully done.
    ///
    /// @return the `remaining` component
    public long remaining() {
        return remaining;
    }

    /// Value equality over all components.
    ///
    /// @param o the object to compare with
    /// @return `true` if `o` is a [ZstdStreamResult] with equal components
    @Override
    public boolean equals(Object o) {
        return o instanceof ZstdStreamResult other
                && bytesConsumed == other.bytesConsumed
                && bytesProduced == other.bytesProduced
                && remaining == other.remaining;
    }

    /// Hash code consistent with [#equals(Object)].
    ///
    /// @return the component-based hash code
    @Override
    public int hashCode() {
        return Objects.hash(bytesConsumed, bytesProduced, remaining);
    }

    /// Record-style description listing every component.
    ///
    /// @return this value as `ZstdStreamResult[component=value, ...]`, naming every component
    @Override
    public String toString() {
        return "ZstdStreamResult[bytesConsumed=" + bytesConsumed + ", bytesProduced=" + bytesProduced + ", remaining=" + remaining + "]";
    }
}
