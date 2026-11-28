package io.github.dfa1.zstd;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                sut.workers(nbWorkers);

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
                sut.workers(2);

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
                sut.workers(2)
                        .jobSize(ZstdByteSize.ofMiB(1))
                        .overlapLog(5);

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
                sut.workers(nbWorkers);

                // When compressing a payload large enough to engage the workers
                MemorySegment src = segmentOf(arena, LARGE_PAYLOAD);
                MemorySegment dst = arena.allocate(Zstd.compressBound(ZstdByteSize.lengthOf(LARGE_PAYLOAD)).value());
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
                sut.workers(nbWorkers);

                // When writing a payload large enough to engage the workers
                sut.write(LARGE_PAYLOAD);
            }

            // Then the frame decompresses back to the original
            assertThat(Zstd.decompress(sink.toByteArray(), ZstdByteSize.lengthOf(LARGE_PAYLOAD))).isEqualTo(LARGE_PAYLOAD);
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
                sut.workers(2);
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

        @ParameterizedTest
        @ValueSource(ints = {-1, Integer.MAX_VALUE})
        void workerCountOutsideTheBoundsIsRejectedBeforeReachingZstd(int count) {
            // Given a context and a worker count zstd would silently clamp (below 0, above the maximum)
            try (ZstdCompressContext sut = Zstd.newCompressContext()) {
                // When setting it
                ThrowingCallable result = () -> sut.workers(count);

                // Then it is rejected up front instead
                assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("workers(" + count + ")");
            }
        }

        @Test
        void workerCountAtTheMaximumIsAccepted() {
            // Given a context and the largest worker count the library accepts
            int max = ZstdCompressParameter.NB_WORKERS.bounds().upperBound();
            try (ZstdCompressContext sut = Zstd.newCompressContext()) {
                // When setting it
                ThrowingCallable result = () -> sut.workers(max);

                // Then it is accepted
                assertThatCode(result).doesNotThrowAnyException();
            }
        }
    }
}
