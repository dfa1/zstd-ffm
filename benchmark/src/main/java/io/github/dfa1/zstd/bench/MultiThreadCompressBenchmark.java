package io.github.dfa1.zstd.bench;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import io.github.dfa1.zstd.Zstd;
import io.github.dfa1.zstd.ZstdByteSize;
import io.github.dfa1.zstd.ZstdCompressContext;
import io.github.dfa1.zstd.ZstdCompressionLevel;
import io.github.dfa1.zstd.ZstdCompressParameter;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/// Multithreaded compression throughput (`NB_WORKERS`, ADR 0015), kept apart
/// from [CompressBenchmark] so its longitudinal single-threaded numbers stay
/// comparable across runs (ADR 0012) and no legs are wasted below zstd's
/// 512 KiB MT engagement threshold.
///
/// `nbWorkers = 0` is the single-threaded baseline leg. At level 3 the default
/// job size is about 8 MiB (4x the window), so the 1 MiB size measures pure
/// MT overhead on a single-job input — expect no speedup there by design —
/// while 64 MiB spans multiple jobs and is where workers actually pay off.
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Fork(value = 1, jvmArgsAppend = "--enable-native-access=ALL-UNNAMED")
@Warmup(iterations = 3)
@Measurement(iterations = 5)
public class MultiThreadCompressBenchmark {

    // 1 MiB, 64 MiB — JMH @Param requires compile-time constant Strings, so
    // these can't be expressed via ZstdByteSize.ofMiB.
    @Param({"1048576", "67108864"})
    private int size;

    @Param({"0", "2", "4"})
    private int nbWorkers;

    @Param({"3"})
    private int level;

    private ZstdCompressContext ctx;

    private Arena arena;
    private MemorySegment srcSeg;
    private MemorySegment dstSeg;

    @Setup(Level.Trial)
    public void setup() {
        byte[] src = BenchData.generate(size);
        int bound = Zstd.compressBound(new ZstdByteSize(size)).toArraySize();

        ctx = new ZstdCompressContext().level(new ZstdCompressionLevel(level));
        if (nbWorkers > 0) {
            ctx.parameter(ZstdCompressParameter.NB_WORKERS, nbWorkers);
        }

        arena = Arena.ofConfined();
        srcSeg = arena.allocate(size);
        MemorySegment.copy(src, 0, srcSeg, JAVA_BYTE, 0, size);
        dstSeg = arena.allocate(bound);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        ctx.close();
        arena.close();
    }

    @Benchmark
    public long zstdJavaSegment() {
        return ctx.compress(dstSeg, srcSeg);
    }
}
