package io.github.dfa1.zstd.bench;

import io.github.dfa1.zstd.ZstdByteSize;
import io.github.dfa1.zstd.ZstdCompressContext;
import io.github.dfa1.zstd.ZstdCompressDictionary;
import io.github.dfa1.zstd.ZstdDecompressContext;
import io.github.dfa1.zstd.ZstdDecompressDictionary;
import io.github.dfa1.zstd.ZstdDictionary;
import io.github.dfa1.zstd.ZstdFrame;
import io.github.dfa1.zstd.rfc9842.AvailableDictionaryHeader;
import io.github.dfa1.zstd.rfc9842.Rfc9842Frame;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
/// `PerfTestDemo` (`rfc9842`'s test classpath), which mixes all of that in.
/// Swept across a range of payload sizes to see where a dictionary's benefit peaks
/// and where it stops paying for itself, as payloads grow more able to
/// compress against their own internal redundancy alone.
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Thread)
@Fork(value = 1, jvmArgsAppend = "--enable-native-access=ALL-UNNAMED")
@Warmup(iterations = 3)
@Measurement(iterations = 5)
public class DictionaryTransportBenchmark {

    // A dictionary hand-picked to merely "look like" the payload teaches
    // nothing about real dictionary compression: ZDICT's whole value is
    // finding the redundancy actually present across a corpus of previously
    // served responses. So the dictionary here is trained (via
    // ZstdDictionary#train) on many independently generated batches of the
    // exact same shape as the payload under test — standing in for "the real
    // data we are serving" a production deployment would train against.
    private static final int TRAINING_SAMPLE_COUNT = 300;
    private static final ZstdByteSize MAX_DICT_BYTES = ZstdByteSize.ofKiB(1);

    // Payload size in bytes, swept from 512 B to 64 KB to trace the curve of
    // where a dictionary helps vs. where it stops paying for itself.
    @Param({"512", "1024", "2048", "4096", "8192", "16384", "32768", "65536"})
    private int size;

    private byte[] payload;
    private byte[] gzipped;
    private byte[] zstdCompressed;
    private byte[] dczFramed;

    private ZstdCompressContext cctx;
    private ZstdDecompressContext dctx;
    private ZstdDictionary dictionary;
    private ZstdCompressDictionary compressDictionary;
    private ZstdDecompressDictionary decompressDictionary;
    private AvailableDictionaryHeader availableDictionary;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        int targetBytes = size;

        List<byte[]> trainingSamples = new ArrayList<>(TRAINING_SAMPLE_COUNT);
        Random trainingRandom = new Random(0x5EED);
        for (int i = 0; i < TRAINING_SAMPLE_COUNT; i++) {
            trainingSamples.add(eventBatch(targetBytes, trainingRandom));
        }
        dictionary = ZstdDictionary.train(trainingSamples, MAX_DICT_BYTES);
        compressDictionary = dictionary.compressDict();
        decompressDictionary = dictionary.decompressDict();
        availableDictionary = AvailableDictionaryHeader.of(dictionary);
        cctx = new ZstdCompressContext();
        dctx = new ZstdDecompressContext();

        // The payload under test is its own fixed seed, disjoint from the
        // training corpus above — dictionaries must not be evaluated against
        // the very samples they were trained on.
        payload = eventBatch(targetBytes, new Random(0xC0FFEE));
        gzipped = gzip(payload);
        zstdCompressed = cctx.compress(payload);
        dczFramed = Rfc9842Frame.wrap(cctx.compress(payload, compressDictionary), availableDictionary);
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
        return Rfc9842Frame.wrap(cctx.compress(payload, compressDictionary), availableDictionary);
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
        byte[] frame = Rfc9842Frame.unwrap(dczFramed, availableDictionary);
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

    // Same shape as DczTestServer's nextBatch (rfc9842's test classpath), but driven
    // by a caller-supplied Random: JMH runs need deterministic input, not a
    // live counter, while training still needs many distinct samples.
    private static byte[] eventBatch(int targetBytes, Random random) {
        String[] eventTypes = {"page_view", "click", "scroll"};
        String[] users = {"eve", "frank", "grace", "heidi", "ivan"};
        String[] paths = {"/checkout", "/cart", "/product/42", "/search"};
        String[] devices = {"desktop", "mobile", "tablet"};

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
