package io.github.dfa1.zstd;

import java.util.Objects;

/// A live snapshot of a streaming compressor's progress, from
/// `ZSTD_getFrameProgression`.
///
/// The byte counts are [ZstdByteSize]; `currentJobId` and `activeWorkers` stay
/// `int`: a job id is a counter with no unit, and a worker count is reported by
/// zstd, not chosen by the caller, so there is nothing to validate.
public final class ZstdFrameProgression {

    private final ZstdByteSize ingested;
    private final ZstdByteSize consumed;
    private final ZstdByteSize produced;
    private final ZstdByteSize flushed;
    private final int currentJobId;
    private final int activeWorkers;

    private ZstdFrameProgression(
            ZstdByteSize ingested,
            ZstdByteSize consumed,
            ZstdByteSize produced,
            ZstdByteSize flushed,
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
            ZstdByteSize ingested,
            ZstdByteSize consumed,
            ZstdByteSize produced,
            ZstdByteSize flushed,
            int currentJobId,
            int activeWorkers) {
        return new ZstdFrameProgression(ingested, consumed, produced, flushed, currentJobId, activeWorkers);
    }

    /// Input bytes read and buffered so far.
    ///
    /// @return input bytes read and buffered so far
    public ZstdByteSize ingested() {
        return ingested;
    }

    /// Input bytes actually compressed so far.
    ///
    /// @return input bytes actually compressed so far
    public ZstdByteSize consumed() {
        return consumed;
    }

    /// Output bytes generated so far, including still-buffered.
    ///
    /// @return output bytes generated so far, including still-buffered
    public ZstdByteSize produced() {
        return produced;
    }

    /// Output bytes flushed out so far.
    ///
    /// @return output bytes flushed out so far
    public ZstdByteSize flushed() {
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
                && ingested.equals(other.ingested)
                && consumed.equals(other.consumed)
                && produced.equals(other.produced)
                && flushed.equals(other.flushed)
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
    /// @return this value as `ZstdFrameProgression[component=value, ...]`, naming every component
    @Override
    public String toString() {
        return "ZstdFrameProgression[ingested=" + ingested + ", consumed=" + consumed + ", produced=" + produced + ", flushed=" + flushed + ", currentJobId=" + currentJobId + ", activeWorkers=" + activeWorkers + "]";
    }
}
