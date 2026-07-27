package io.github.dfa1.zstd.bench;

import io.github.dfa1.zstd.ZstdCompressStream;
import io.github.dfa1.zstd.ZstdCompressionLevel;
import io.github.dfa1.zstd.ZstdEndDirective;
import io.github.dfa1.zstd.ZstdOutputStream;
import io.github.dfa1.zstd.ZstdStreamResult;
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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/// Whole-file compression: this library's `mmap` + `MemorySegment` path
/// (with and without a `posix_madvise(WILLNEED)` readahead hint) against two
/// conventional buffered-stream paths - this library's own [ZstdOutputStream]
/// and zstd-jni's classic `ZstdOutputStream`, both over a buffered
/// `FileInputStream` - the comparison behind the numbers in
/// `docs/zero-copy.md`.
///
/// Sizes run from 4 MiB up to 10 GiB, so each `@Benchmark` invocation is one
/// full-file compression (seconds to tens of seconds), not a tight throughput
/// loop — [Mode#SingleShotTime] times individual invocations rather than
/// counting iterations in a fixed window.
///
/// The source file is generated once per size and cached under the system
/// temp directory, reused across all four variants' trials rather than
/// regenerated per (variant, size) — each variant's JMH fork otherwise
/// rewrites an identical multi-gigabyte file for no reason. Only the timed
/// compress-and-write work is measured. Every variant writes its compressed
/// output to a real file, matching real usage rather than discarding output
/// to a null sink.
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Fork(value = 1, jvmArgsAppend = "--enable-native-access=ALL-UNNAMED")
@Warmup(iterations = 1)
@Measurement(iterations = 3)
public class LargeFileBenchmark {

    /// Chunk used both to buffer zstd-jni's read loop and to size the native
    /// destination buffer the mmap path streams compressed output through.
    private static final int CHUNK = 8 * 1024 * 1024;

    /// `POSIX_MADV_WILLNEED`, shared across the POSIX platforms this binds on
    /// (macOS and Linux use the same value); unavailable on Windows.
    private static final int POSIX_MADV_WILLNEED = 3;

    private static final MethodHandle POSIX_MADVISE = lookupPosixMadvise();

    // 4 MiB, 64 MiB, 2.25 GiB, 4 GiB, 10 GiB — matches the sizes historically
    // reported in docs/zero-copy.md so the JMH numbers replace them directly.
    @Param({"4194304", "67108864", "2415919104", "4294967296", "10737418240"})
    private long size;

    @Param({"3"})
    private int level;

    private Path sourceFile;
    private Path destFile;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        sourceFile = cachedSourceFile(size);
        destFile = Files.createTempFile("large-bench-dst", ".zst");
    }

    @TearDown(Level.Trial)
    public void tearDown() throws IOException {
        Files.deleteIfExists(destFile);
    }

    @Benchmark
    public long mmapNoAdvise() throws IOException {
        return mmapCompress(false);
    }

    @Benchmark
    public long mmapWithAdvise() throws IOException {
        return mmapCompress(true);
    }

    // Same buffered-read/heap-write shape as zstdJniStream below, but through
    // this library's own ZstdOutputStream - isolates the mmap+MemorySegment
    // path's gain over our own conventional stream API, not just zstd-jni's.
    @Benchmark
    public long zstdJavaStream() throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(sourceFile), CHUNK);
             ZstdOutputStream out = new ZstdOutputStream(Files.newOutputStream(destFile), new ZstdCompressionLevel(level))) {
            byte[] buffer = new byte[CHUNK];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                total += n;
            }
            return total;
        }
    }

    @Benchmark
    public long zstdJniStream() throws IOException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(sourceFile), CHUNK);
             com.github.luben.zstd.ZstdOutputStream out =
                     new com.github.luben.zstd.ZstdOutputStream(Files.newOutputStream(destFile), level)) {
            byte[] buffer = new byte[CHUNK];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                total += n;
            }
            return total;
        }
    }

    /// Maps `sourceFile`, optionally hints the OS with `posix_madvise`, and
    /// streams the compressed frame out through a direct `ByteBuffer` view of
    /// the native destination buffer — no heap `byte[]` bounce on the way out.
    private long mmapCompress(boolean advise) throws IOException {
        try (FileChannel in = FileChannel.open(sourceFile, StandardOpenOption.READ);
             FileChannel out = FileChannel.open(destFile, StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING);
             Arena arena = Arena.ofConfined();
             ZstdCompressStream cs = new ZstdCompressStream(new ZstdCompressionLevel(level))) {
            MemorySegment source = in.map(FileChannel.MapMode.READ_ONLY, 0, size, arena);
            if (advise) {
                adviseWillNeed(source);
            }

            MemorySegment dst = arena.allocate(CHUNK);
            ByteBuffer dstView = dst.asByteBuffer();
            long srcOff = 0;
            long total = source.byteSize();
            ZstdStreamResult r;
            do {
                MemorySegment srcSlice = source.asSlice(srcOff, total - srcOff);
                r = cs.compress(dst, srcSlice, ZstdEndDirective.END);
                srcOff += r.bytesConsumed();
                dstView.clear().limit((int) r.bytesProduced());
                while (dstView.hasRemaining()) {
                    out.write(dstView);
                }
            } while (!r.isComplete());
            return srcOff;
        }
    }

    /// Hints the OS that `mapped` will be read sequentially and in full, right
    /// after mapping — a no-op if `posix_madvise` is unavailable (e.g. Windows,
    /// where there is no direct equivalent).
    private static void adviseWillNeed(MemorySegment mapped) {
        if (POSIX_MADVISE == null) {
            return;
        }
        try {
            var _ = (int) POSIX_MADVISE.invokeExact(mapped, mapped.byteSize(), POSIX_MADV_WILLNEED);
        } catch (Throwable t) {
            throw new RuntimeException("posix_madvise failed", t);
        }
    }

    @SuppressWarnings("restricted") // downcallHandle needs it; posix_madvise's signature is fixed and safe
    private static MethodHandle lookupPosixMadvise() {
        Linker linker = Linker.nativeLinker();
        SymbolLookup stdlib = linker.defaultLookup();
        return stdlib.find("posix_madvise")
                .map(addr -> linker.downcallHandle(addr,
                        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT)))
                .orElse(null);
    }

    /// A source file of exactly `size` bytes, generated once and cached under
    /// the system temp directory keyed by size, rather than regenerated for
    /// every trial — `mmapNoAdvise`, `mmapWithAdvise`, and `zstdJniStream` each
    /// run in their own JMH fork per size, so without this a fresh 10 GiB file
    /// would be written three times over for that one size point alone. Left
    /// on disk afterward (like the rest of this benchmark's temp files) for
    /// later trials/runs to reuse rather than deleted per trial; a mismatched
    /// size (e.g. a stale file from an interrupted prior run) forces a rewrite.
    private static Path cachedSourceFile(long size) throws IOException {
        Path file = Path.of(System.getProperty("java.io.tmpdir"), "large-bench-src-" + size + ".bin");
        if (!Files.exists(file) || Files.size(file) != size) {
            writeCompressibleFile(file, size);
        }
        return file;
    }

    /// Writes `size` bytes of realistic, compressible content to `file`: one
    /// [BenchData] chunk generated once, then repeated — cheap to write at
    /// multi-gigabyte scale, and no less representative than regenerating it,
    /// since [BenchData#generate] is itself a repeating pseudo-random pattern.
    private static void writeCompressibleFile(Path file, long size) throws IOException {
        byte[] chunk = BenchData.generate(CHUNK);
        ByteBuffer view = ByteBuffer.wrap(chunk);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            long written = 0;
            while (written < size) {
                int len = (int) Math.min(chunk.length, size - written);
                view.clear().limit(len);
                while (view.hasRemaining()) {
                    written += channel.write(view);
                }
            }
        }
    }
}
