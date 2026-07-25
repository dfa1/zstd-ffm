package io.github.dfa1.zstd.bench;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import io.github.dfa1.zstd.Zstd;
import io.github.dfa1.zstd.ZstdByteSize;
import io.github.dfa1.zstd.ZstdCompressContext;
import io.github.dfa1.zstd.ZstdCompressionLevel;
import io.github.dfa1.zstd.ZstdDecompressContext;
import java.io.UncheckedIOException;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
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

/// Throughput on zstd's own vendored golden corpus — the real, version-matched
/// fixtures under `third_party/zstd/tests/golden-compression`, rather than the
/// synthetic ~3x-compressible payloads of [CompressBenchmark] / [DecompressBenchmark].
///
/// These files are small (143 B to 256 KiB) and structurally varied (an HTTP
/// header, a pathological Huffman case, long literal/match runs, a block-splitter
/// regression), so they exercise the per-call native-boundary overhead far more
/// than the bandwidth-bound synthetic 64 MiB case. This is where FFM-vs-JNI
/// fixed costs actually show up. Each `@Param` is one corpus file.
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Fork(value = 1, jvmArgsAppend = "--enable-native-access=ALL-UNNAMED")
@Warmup(iterations = 3)
@Measurement(iterations = 5)
public class GoldenCorpusBenchmark {

    @Param({
        "http",
        "huffman-compressed-larger",
        "large-literal-and-match-lengths",
        "PR-3517-block-splitter-corruption-test",
    })
    private String file;

    @Param({"3"})
    private int level;

    private byte[] src;
    private int srcSize;
    private byte[] frame;

    private int bound;

    private ZstdCompressContext cctx;
    private ZstdDecompressContext dctx;

    private Arena arena;
    private MemorySegment srcSeg;
    private MemorySegment frameSeg;
    private MemorySegment compressDstSeg;
    private MemorySegment decompressDstSeg;

    // zstd-jni's own zero-copy path: reusable context + direct (off-heap)
    // ByteBuffers, reused across calls — the fair peer of our MemorySegment path
    // (no per-call allocation, no heap bounce), not the allocating
    // Zstd.compress(byte[]) used by compressJni/decompressJni.
    private com.github.luben.zstd.ZstdCompressCtx jniCctx;
    private com.github.luben.zstd.ZstdDecompressCtx jniDctx;
    private ByteBuffer jniSrcBuf;
    private ByteBuffer jniFrameBuf;
    private ByteBuffer jniCompressDstBuf;
    private ByteBuffer jniDecompressDstBuf;

    @Setup(Level.Trial)
    public void setup() {
        src = read(corpus().resolve("golden-compression").resolve(file));
        srcSize = src.length;
        frame = Zstd.compress(src);

        cctx = new ZstdCompressContext().level(new ZstdCompressionLevel(level));
        dctx = new ZstdDecompressContext();
        bound = Zstd.compressBound(new ZstdByteSize(srcSize)).toArraySize();

        arena = Arena.ofConfined();
        srcSeg = arena.allocate(srcSize);
        MemorySegment.copy(src, 0, srcSeg, JAVA_BYTE, 0, srcSize);
        frameSeg = arena.allocate(frame.length);
        MemorySegment.copy(frame, 0, frameSeg, JAVA_BYTE, 0, frame.length);
        compressDstSeg = arena.allocate(bound);
        decompressDstSeg = arena.allocate(srcSize);

        jniCctx = new com.github.luben.zstd.ZstdCompressCtx().setLevel(level);
        jniDctx = new com.github.luben.zstd.ZstdDecompressCtx();
        jniSrcBuf = ByteBuffer.allocateDirect(srcSize).put(src).flip();
        jniFrameBuf = ByteBuffer.allocateDirect(frame.length).put(frame).flip();
        jniCompressDstBuf = ByteBuffer.allocateDirect(bound);
        jniDecompressDstBuf = ByteBuffer.allocateDirect(srcSize);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        cctx.close();
        dctx.close();
        arena.close();
        jniCctx.close();
        jniDctx.close();
    }

    @Benchmark
    public byte[] compressJavaBytes() {
        return cctx.compress(src);
    }

    @Benchmark
    public long compressJavaSegment() {
        return cctx.compress(compressDstSeg, srcSeg);
    }

    @Benchmark
    public byte[] compressJni() {
        return com.github.luben.zstd.Zstd.compress(src, level);
    }

    @Benchmark
    public int compressJniByteBuffer() {
        return jniCctx.compressDirectByteBuffer(jniCompressDstBuf, 0, bound, jniSrcBuf, 0, srcSize);
    }

    @Benchmark
    public byte[] decompressJavaBytes() {
        return dctx.decompress(frame, new ZstdByteSize(srcSize));
    }

    @Benchmark
    public long decompressJavaSegment() {
        return dctx.decompress(decompressDstSeg, frameSeg);
    }

    @Benchmark
    public byte[] decompressJni() {
        return com.github.luben.zstd.Zstd.decompress(frame, srcSize);
    }

    @Benchmark
    public int decompressJniByteBuffer() {
        return jniDctx.decompressDirectByteBuffer(
                jniDecompressDstBuf, 0, srcSize, jniFrameBuf, 0, frame.length);
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read corpus file " + file, e);
        }
    }

    /// Walks up from the working directory to find `third_party/zstd/tests`, the
    /// vendored corpus shipped via the `third_party/zstd` git submodule.
    private static Path corpus() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("third_party/zstd/tests");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "golden corpus not found: third_party/zstd/tests is missing — "
                        + "run `git submodule update --init --recursive`");
    }
}
