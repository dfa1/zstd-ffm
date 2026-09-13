package io.github.dfa1.zstd.bench;

import io.github.dfa1.zstd.ZstdByteSize;
import io.github.dfa1.zstd.ZstdCompressContext;
import io.github.dfa1.zstd.ZstdCompressDictionary;
import io.github.dfa1.zstd.ZstdDecompressContext;
import io.github.dfa1.zstd.ZstdDecompressDictionary;
import io.github.dfa1.zstd.ZstdDictionary;
import io.github.dfa1.zstd.ZstdFrame;
import io.github.dfa1.zstd.rfc9842.Rfc9842Frame;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
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

/// Pure in-process codec comparison — gzip vs plain zstd (no dictionary) vs
/// zstd with a pre-digested dictionary (the RFC 9842 `dcz` codec) — with no
/// HTTP, no JSON generation, and no socket I/O in the measured path, unlike
/// `docs/examples/rfc9842/PerfTest.java`, which mixes all of that in. Same
/// payload shapes as that demo (a batch of similar small JSON "event"
/// records, at its "small" ~2.8 KB and "large" ~50 KB sizes) and the same toy
/// dictionary, so the two can be compared directly.
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Fork(value = 1, jvmArgsAppend = "--enable-native-access=ALL-UNNAMED")
@Warmup(iterations = 3)
@Measurement(iterations = 5)
public class DictionaryTransportBenchmark {

    private static final byte[] DICTIONARY_BYTES = ("""
            {"event":"page_view","user":"alice","path":"/home","timestamp":1699999001,"properties":{"referrer":"https://example.com","device":"desktop"}}
            {"event":"page_view","user":"bob","path":"/pricing","timestamp":1699999002,"properties":{"referrer":"https://example.com","device":"mobile"}}
            {"event":"click","user":"carol","path":"/signup","timestamp":1699999003,"properties":{"referrer":"https://example.com","device":"desktop"}}
            {"event":"page_view","user":"dave","path":"/docs","timestamp":1699999004,"properties":{"referrer":"https://example.com","device":"mobile"}}
            """).getBytes(StandardCharsets.UTF_8);

    // Matches docs/examples/rfc9842/Server.java's SMALL_TARGET_BYTES/LARGE_TARGET_BYTES.
    @Param({"small", "large"})
    private String size;

    private byte[] payload;
    private byte[] gzipped;
    private byte[] zstdCompressed;
    private byte[] dczFramed;

    private ZstdCompressContext cctx;
    private ZstdDecompressContext dctx;
    private ZstdDictionary dictionary;
    private ZstdCompressDictionary compressDictionary;
    private ZstdDecompressDictionary decompressDictionary;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        dictionary = ZstdDictionary.of(DICTIONARY_BYTES);
        compressDictionary = dictionary.compressDict();
        decompressDictionary = dictionary.decompressDict();
        cctx = new ZstdCompressContext();
        dctx = new ZstdDecompressContext();

        payload = eventBatch("small".equals(size) ? 2_800 : 50_000);
        gzipped = gzip(payload);
        zstdCompressed = cctx.compress(payload);
        dczFramed = Rfc9842Frame.wrap(cctx.compress(payload, compressDictionary), dictionary);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        cctx.close();
        dctx.close();
        compressDictionary.close();
        decompressDictionary.close();
    }

    @Benchmark
    public byte[] gzipCompress() throws IOException {
        return gzip(payload);
    }

    @Benchmark
    public byte[] zstdCompress() {
        return cctx.compress(payload);
    }

    @Benchmark
    public byte[] dczCompress() {
        return Rfc9842Frame.wrap(cctx.compress(payload, compressDictionary), dictionary);
    }

    @Benchmark
    public byte[] gzipDecompress() throws IOException {
        return gunzip(gzipped);
    }

    @Benchmark
    public byte[] zstdDecompress() {
        return dctx.decompress(zstdCompressed);
    }

    @Benchmark
    public byte[] dczDecompress() {
        byte[] frame = Rfc9842Frame.unwrap(dczFramed, dictionary);
        ZstdByteSize contentSize = ZstdFrame.decompressedSize(frame);
        return dctx.decompress(frame, contentSize, decompressDictionary);
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 3));
        try (GZIPOutputStream gz = new GZIPOutputStream(out, Math.min(65536, Math.max(512, data.length)))) {
            gz.write(data);
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] data) throws IOException {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return gz.readAllBytes();
        }
    }

    // Same shape as docs/examples/rfc9842/Server.java's nextBatch, but with a
    // fixed seed: JMH runs need deterministic input, not a live counter.
    private static byte[] eventBatch(int targetBytes) {
        String[] eventTypes = {"page_view", "click", "scroll"};
        String[] users = {"eve", "frank", "grace", "heidi", "ivan"};
        String[] paths = {"/checkout", "/cart", "/product/42", "/search"};
        String[] devices = {"desktop", "mobile", "tablet"};

        Random random = new Random(0xC0FFEE);
        StringBuilder batch = new StringBuilder();
        while (batch.length() < targetBytes) {
            batch.append("""
                    {"event":"%s","user":"%s","path":"%s","timestamp":%d,"properties":{"referrer":"https://example.com","device":"%s"}}
                    """.formatted(
                    eventTypes[random.nextInt(eventTypes.length)],
                    users[random.nextInt(users.length)],
                    paths[random.nextInt(paths.length)],
                    1_700_000_000L + random.nextInt(1_000_000),
                    devices[random.nextInt(devices.length)]));
        }
        return batch.toString().getBytes(StandardCharsets.UTF_8);
    }
}
