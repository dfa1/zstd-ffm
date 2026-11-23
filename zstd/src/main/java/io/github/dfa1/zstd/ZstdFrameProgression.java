package io.github.dfa1.zstd;

import java.util.Objects;

/// A live snapshot of a streaming compressor's progress, from
/// `ZSTD_getFrameProgression`.
public final class ZstdFrameProgression {

    private final long ingested;
    private final long consumed;
    private final long produced;
    private final long flushed;
    private final int currentJobId;
    private final int activeWorkers;

    private ZstdFrameProgression(
            long ingested,
            long consumed,
            long produced,
            long flushed,
            int currentJobId,
            int activeWorkers) {
        this.ingested = ingested;
        this.consumed = consumed;
        this.produced = produced;
        this.flushed = flushed;
        this.currentJobId = currentJobId;
        this.activeWorkers = activeWorkers;
    }

    /// Creates a [ZstdFrameProgression] from its components; only the library itself builds these.
    ///
    /// @param ingested      input bytes read and buffered so far
    /// @param consumed      input bytes actually compressed so far
    /// @param produced      output bytes generated so far, including still-buffered
    /// @param flushed       output bytes flushed out so far
    /// @param currentJobId  current multithreading job id (0 when single-threaded)
    /// @param activeWorkers number of worker threads currently active (0 when single-threaded)
    /// @return the new [ZstdFrameProgression]
    static ZstdFrameProgression of(
            long ingested,
            long consumed,
            long produced,
            long flushed,
            int currentJobId,
            int activeWorkers) {
        return new ZstdFrameProgression(ingested, consumed, produced, flushed, currentJobId, activeWorkers);
    }

    /// Input bytes read and buffered so far.
    ///
    /// @return input bytes read and buffered so far
    public long ingested() {
        return ingested;
    }

    /// Input bytes actually compressed so far.
    ///
    /// @return input bytes actually compressed so far
    public long consumed() {
        return consumed;
    }

    /// Output bytes generated so far, including still-buffered.
    ///
    /// @return output bytes generated so far, including still-buffered
    public long produced() {
        return produced;
    }

    /// Output bytes flushed out so far.
    ///
    /// @return output bytes flushed out so far
    public long flushed() {
        return flushed;
    }

    /// Current multithreading job id (0 when single-threaded).
    ///
    /// @return current multithreading job id (0 when single-threaded)
    public int currentJobId() {
        return currentJobId;
    }

    /// Number of worker threads currently active (0 when single-threaded).
    ///
    /// @return number of worker threads currently active (0 when single-threaded)
    public int activeWorkers() {
        return activeWorkers;
    }

    /// Value equality over all components.
    ///
    /// @param o the object to compare with
    /// @return `true` if `o` is a [ZstdFrameProgression] with equal components
    @Override
    public boolean equals(Object o) {
        return o instanceof ZstdFrameProgression other
                && ingested == other.ingested
                && consumed == other.consumed
                && produced == other.produced
                && flushed == other.flushed
                && currentJobId == other.currentJobId
                && activeWorkers == other.activeWorkers;
    }

    /// Hash code consistent with [#equals(Object)].
    ///
    /// @return the component-based hash code
    @Override
    public int hashCode() {
        return Objects.hash(ingested, consumed, produced, flushed, currentJobId, activeWorkers);
    }

    /// Record-style description listing every component.
    ///
    /// @return `ZstdFrameProgression[component=value, ...]`
    @Override
    public String toString() {
        return "ZstdFrameProgression[ingested=" + ingested + ", consumed=" + consumed + ", produced=" + produced + ", flushed=" + flushed + ", currentJobId=" + currentJobId + ", activeWorkers=" + activeWorkers + "]";
    }
}
