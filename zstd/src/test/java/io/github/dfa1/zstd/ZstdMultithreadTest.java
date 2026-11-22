package io.github.dfa1.zstd;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static io.github.dfa1.zstd.ZstdTestSupport.bytesOf;
import static io.github.dfa1.zstd.ZstdTestSupport.segmentOf;
import static org.assertj.core.api.Assertions.assertThat;

/// Multithreaded compression via `NB_WORKERS`. zstd engages workers only when
/// the pledged source size exceeds its internal job-size minimum (512 KiB), so
/// these tests use a 2 MiB in-memory payload. MT output is format-valid but not
/// byte-identical to single-threaded output — no test here may byte-compare
/// compressed frames.
class ZstdMultithreadTest {

    /// Large enough to clear zstd's 512 KiB MT engagement threshold.
    private static final byte[] LARGE_PAYLOAD =
            "multithreaded compression payload ".repeat(64 * 1024).getBytes(StandardCharsets.UTF_8);

    /// Far below the 512 KiB threshold: MT silently disengages.
    private static final byte[] SMALL_PAYLOAD =
            "small payload ".repeat(64).getBytes(StandardCharsets.UTF_8);

    @Nested
    class RoundTrip {

        @ParameterizedTest
        @ValueSource(ints = {1, 2})
        void roundTripsWithWorkers(int nbWorkers) {
            // Given a context configured with worker threads
            byte[] frame;
            try (ZstdCompressContext sut = Zstd.newCompressContext()) {
                sut.parameter(ZstdCompressParameter.NB_WORKERS, nbWorkers);

                // When compressing a payload large enough to engage the workers
                frame = sut.compress(LARGE_PAYLOAD);
            }

            // Then the frame decompresses back to the original
            assertThat(Zstd.decompress(frame)).isEqualTo(LARGE_PAYLOAD);
        }

        @Test
        void roundTripsBelowJobSizeThreshold() {
            // Given a context with workers but a payload below zstd's 512 KiB
            // MT minimum, where multithreading silently disengages
            byte[] frame;
            try (ZstdCompressContext sut = Zstd.newCompressContext()) {
                sut.parameter(ZstdCompressParameter.NB_WORKERS, 2);

                // When compressing the small payload
                frame = sut.compress(SMALL_PAYLOAD);
            }

            // Then the frame still round-trips
            assertThat(Zstd.decompress(frame)).isEqualTo(SMALL_PAYLOAD);
        }

        @Test
        void honorsJobSizeAndOverlapLog() {
            // Given workers combined with an explicit job size and overlap
            byte[] frame;
            try (ZstdCompressContext sut = Zstd.newCompressContext()) {
                sut.parameter(ZstdCompressParameter.NB_WORKERS, 2)
                        .parameter(ZstdCompressParameter.JOB_SIZE, 1024 * 1024)
                        .parameter(ZstdCompressParameter.OVERLAP_LOG, 5);

                // When compressing across multiple jobs
                frame = sut.compress(LARGE_PAYLOAD);
            }

            // Then the frame decompresses back to the original
            assertThat(Zstd.decompress(frame)).isEqualTo(LARGE_PAYLOAD);
        }
    }

    @Nested
    class SegmentStreamRoundTrip {

        @ParameterizedTest
        @ValueSource(ints = {1, 2})
        void roundTripsWithWorkers(int nbWorkers) {
            // Given a segment stream configured with worker threads
            byte[] frame;
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressStream sut = Zstd.newCompressStream()) {
                sut.parameter(ZstdCompressParameter.NB_WORKERS, nbWorkers);

                // When compressing a payload large enough to engage the workers
                MemorySegment src = segmentOf(arena, LARGE_PAYLOAD);
                MemorySegment dst = arena.allocate(Zstd.compressBound(ZstdByteSize.of(LARGE_PAYLOAD.length)).value());
                ZstdStreamResult r = sut.compress(dst, src, ZstdEndDirective.END);
                frame = bytesOf(dst, r.bytesProduced());
            }

            // Then the frame decompresses back to the original
            assertThat(Zstd.decompress(frame)).isEqualTo(LARGE_PAYLOAD);
        }
    }

    @Nested
    class OutputStreamRoundTrip {

        @ParameterizedTest
        @ValueSource(ints = {1, 2})
        void roundTripsWithWorkers(int nbWorkers) throws IOException {
            // Given an output stream configured with worker threads
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (ZstdOutputStream sut = Zstd.newOutputStream(sink)) {
                sut.parameter(ZstdCompressParameter.NB_WORKERS, nbWorkers);

                // When writing a payload large enough to engage the workers
                sut.write(LARGE_PAYLOAD);
            }

            // Then the frame decompresses back to the original
            assertThat(Zstd.decompress(sink.toByteArray(), ZstdByteSize.of(LARGE_PAYLOAD.length))).isEqualTo(LARGE_PAYLOAD);
        }
    }

    @Nested
    class Lifecycle {

        @Test
        void workerPoolSurvivesReset() {
            // Given a context that compressed once without workers (baseline size)
            try (ZstdCompressContext sut = Zstd.newCompressContext()) {
                sut.compress(LARGE_PAYLOAD);
                long baseline = sut.sizeOf().value();

                // When compressing with workers, then resetting session and parameters
                sut.parameter(ZstdCompressParameter.NB_WORKERS, 2);
                sut.compress(LARGE_PAYLOAD);
                long afterMultithread = sut.sizeOf().value();
                sut.reset(ZstdResetDirective.SESSION_AND_PARAMETERS);
                long afterReset = sut.sizeOf().value();

                // Then the worker pool and job buffers were allocated by the MT
                // compress, and reset did NOT release them — only close() does.
                // This pins the lifecycle constraint recorded in ADR 0015: a
                // context that ever compressed with workers must not be pooled.
                assertThat(afterMultithread).isGreaterThan(baseline);
                assertThat(afterReset).isGreaterThan(baseline);
            }
        }
    }

    @Nested
    class Validation {

        @Test
        void nbWorkersUpperBoundIsPositive() {
            // Given the bounds zstd reports for NB_WORKERS
            ZstdBounds bounds = ZstdCompressParameter.NB_WORKERS.bounds();

            // Then the upper bound is positive — on a single-threaded build zstd
            // reports [0, 0], so this fails loudly if ZSTD_MULTITHREAD regresses
            assertThat(bounds.upperBound()).isGreaterThanOrEqualTo(1);
        }

        @Test
        void clampsWorkerCountAboveBoundsInsteadOfRejecting() {
            // Given a worker count above the native maximum — zstd clamps
            // NB_WORKERS into bounds (like COMPRESSION_LEVEL) instead of
            // erroring, unlike most other advanced parameters
            int aboveMax = ZstdCompressParameter.NB_WORKERS.bounds().upperBound() + 1;
            byte[] frame;
            try (ZstdCompressContext sut = Zstd.newCompressContext()) {
                // When setting it and compressing
                sut.parameter(ZstdCompressParameter.NB_WORKERS, aboveMax);
                frame = sut.compress(LARGE_PAYLOAD);
            }

            // Then the value was clamped to the maximum and the frame round-trips
            assertThat(Zstd.decompress(frame)).isEqualTo(LARGE_PAYLOAD);
        }

        @Test
        void clampsNegativeWorkerCountToSingleThreaded() {
            // Given a negative worker count, which zstd clamps to 0
            byte[] frame;
            try (ZstdCompressContext sut = Zstd.newCompressContext()) {
                // When setting it and compressing
                sut.parameter(ZstdCompressParameter.NB_WORKERS, -1);
                frame = sut.compress(SMALL_PAYLOAD);
            }

            // Then compression proceeds single-threaded and round-trips
            assertThat(Zstd.decompress(frame)).isEqualTo(SMALL_PAYLOAD);
        }
    }
}
