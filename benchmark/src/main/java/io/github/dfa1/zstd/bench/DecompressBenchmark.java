package io.github.dfa1.zstd.bench;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import io.airlift.compress.v3.zstd.ZstdJavaDecompressor;
import io.github.dfa1.zstd.Zstd;
import io.github.dfa1.zstd.ZstdByteSize;
import io.github.dfa1.zstd.ZstdDecompressContext;
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

/// Decompression throughput: zstd-ffm in heap (`byte[]`) and zero-copy
/// (`MemorySegment`) modes, against zstd-jni (JNI) and aircompressor (pure Java).
///
/// Frames are produced once at setup with zstd-ffm (they carry the decompressed
/// size, which every decoder here relies on for output sizing).
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Fork(value = 1, jvmArgsAppend = "--enable-native-access=ALL-UNNAMED")
@Warmup(iterations = 3)
@Measurement(iterations = 5)
public class DecompressBenchmark {

    // 1 KiB, 64 KiB, 1 MiB, 64 MiB — JMH @Param requires compile-time constant
    // Strings, so these can't be expressed via ZstdByteSize.ofKiB/ofMiB.
    @Param({"1024", "65536", "1048576", "67108864"})
    private int size;

    private int originalSize;
    private byte[] frame;

    private ZstdDecompressContext ffmCtx;

    private Arena arena;
    private MemorySegment frameSeg;
    private MemorySegment dstSeg;

    private ZstdJavaDecompressor airDecompressor;
    private byte[] airDst;

    @Setup(Level.Trial)
    public void setup() {
        originalSize = size;
        frame = Zstd.compress(BenchData.generate(size));

        ffmCtx = new ZstdDecompressContext();

        arena = Arena.ofConfined();
        frameSeg = arena.allocate(frame.length);
        MemorySegment.copy(frame, 0, frameSeg, JAVA_BYTE, 0, frame.length);
        dstSeg = arena.allocate(originalSize);

        airDecompressor = new ZstdJavaDecompressor();
        airDst = new byte[originalSize];
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        ffmCtx.close();
        arena.close();
    }

    @Benchmark
    public byte[] zstdJavaBytes() {
        return ffmCtx.decompress(frame, new ZstdByteSize(originalSize));
    }

    @Benchmark
    public long zstdJavaSegment() {
        return ffmCtx.decompress(dstSeg, frameSeg);
    }

    // Fresh context per call (vs ffmCtx, reused above) isolates the native
    // ZSTD_createDCtx/ZSTD_freeDCtx cost that context reuse avoids.
    @Benchmark
    public byte[] zstdJavaBytesFreshContext() {
        try (ZstdDecompressContext ctx = new ZstdDecompressContext()) {
            return ctx.decompress(frame, new ZstdByteSize(originalSize));
        }
    }

    @Benchmark
    public long zstdJavaSegmentFreshContext() {
        try (ZstdDecompressContext ctx = new ZstdDecompressContext()) {
            return ctx.decompress(dstSeg, frameSeg);
        }
    }

    @Benchmark
    public byte[] zstdJni() {
        return com.github.luben.zstd.Zstd.decompress(frame, originalSize);
    }

    @Benchmark
    public int aircompressor() {
        return airDecompressor.decompress(frame, 0, frame.length, airDst, 0, airDst.length);
    }
}
