package io.github.dfa1.zstd.bench;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import io.airlift.compress.v3.zstd.ZstdJavaCompressor;
import io.github.dfa1.zstd.Zstd;
import io.github.dfa1.zstd.ZstdByteSize;
import io.github.dfa1.zstd.ZstdCompressContext;
import io.github.dfa1.zstd.ZstdCompressionLevel;
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

/// Compression throughput: zstd-ffm in heap (`byte[]`) and zero-copy
/// (`MemorySegment`) modes, against zstd-jni (JNI) and aircompressor (pure Java).
///
/// The `MemorySegment` path is the one expected to win: input and output are
/// already off-heap, so there is no `byte[]` bounce in or out of native code.
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Fork(value = 1, jvmArgsAppend = "--enable-native-access=ALL-UNNAMED")
@Warmup(iterations = 3)
@Measurement(iterations = 5)
public class CompressBenchmark {

    // 1 KiB, 64 KiB, 1 MiB, 64 MiB — JMH @Param requires compile-time constant
    // Strings, so these can't be expressed via ZstdByteSize.ofKiB/ofMiB.
    @Param({"1024", "65536", "1048576", "67108864"})
    private int size;

    @Param({"3"})
    private int level;

    private byte[] src;

    private ZstdCompressContext ffmCtx;

    private Arena arena;
    private MemorySegment srcSeg;
    private MemorySegment dstSeg;

    private ZstdJavaCompressor airCompressor;
    private byte[] airDst;

    @Setup(Level.Trial)
    public void setup() {
        src = BenchData.generate(size);
        int bound = Zstd.compressBound(new ZstdByteSize(size)).toArraySize();

        ffmCtx = new ZstdCompressContext().level(new ZstdCompressionLevel(level));

        arena = Arena.ofConfined();
        srcSeg = arena.allocate(size);
        MemorySegment.copy(src, 0, srcSeg, JAVA_BYTE, 0, size);
        dstSeg = arena.allocate(bound);

        airCompressor = new ZstdJavaCompressor();
        airDst = new byte[airCompressor.maxCompressedLength(size)];
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        ffmCtx.close();
        arena.close();
    }

    @Benchmark
    public byte[] zstdJavaBytes() {
        return ffmCtx.compress(src);
    }

    @Benchmark
    public long zstdJavaSegment() {
        return ffmCtx.compress(dstSeg, srcSeg);
    }

    // Fresh context per call (vs ffmCtx, reused above) isolates the native
    // ZSTD_createCCtx/ZSTD_freeCCtx cost that context reuse avoids.
    @Benchmark
    public byte[] zstdJavaBytesFreshContext() {
        try (ZstdCompressContext ctx = new ZstdCompressContext().level(new ZstdCompressionLevel(level))) {
            return ctx.compress(src);
        }
    }

    @Benchmark
    public long zstdJavaSegmentFreshContext() {
        try (ZstdCompressContext ctx = new ZstdCompressContext().level(new ZstdCompressionLevel(level))) {
            return ctx.compress(dstSeg, srcSeg);
        }
    }

    @Benchmark
    public byte[] zstdJni() {
        return com.github.luben.zstd.Zstd.compress(src, level);
    }

    @Benchmark
    public int aircompressor() {
        return airCompressor.compress(src, 0, size, airDst, 0, airDst.length);
    }
}
