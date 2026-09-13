import io.github.dfa1.zstd.ZstdByteSize;
import io.github.dfa1.zstd.ZstdDecompressContext;
import io.github.dfa1.zstd.ZstdDecompressDictionary;
import io.github.dfa1.zstd.ZstdDictionary;
import io.github.dfa1.zstd.ZstdFrame;
import io.github.dfa1.zstd.rfc9842.AvailableDictionary;
import io.github.dfa1.zstd.rfc9842.DictionaryId;
import io.github.dfa1.zstd.rfc9842.Rfc9842Frame;
import io.github.dfa1.zstd.rfc9842.UseAsDictionary;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

/// Small, sequential perf comparison of Server.java's four `Content-Encoding`
/// tiers — identity, gzip, plain zstd, and RFC 9842 `dcz` — against the same
/// live server, reporting requests/second, the full round-trip latency
/// distribution (p50/p90/p99/max, not just a mean — an average alone hides
/// how much the tail differs between tiers), and total bytes transferred for
/// each. Not a rigorous benchmark (single connection, single thread, no
/// warmup methodology beyond discarding the first few calls) — just enough to
/// see the shape of the trade-off. Server.java's response size is fixed at
/// startup (its `args[0]`); pass the same value here purely as a label for
/// the output table.
///
/// Run from the repository root (see README.md in this directory for the
/// one-time build step and the exact classpath), after starting Server.java:
/// {@snippet :
/// java --enable-native-access=ALL-UNNAMED \
///      --class-path "$(find . -path '*/target/classes' | tr '\n' ':')" \
///      docs/examples/rfc9842/PerfTest.java
/// }
public class PerfTest {

    private static final URI BASE = URI.create("http://localhost:9842");
    private static final URI DATA = BASE.resolve("/api/data");

    // 20 was the original value here, and looked reasonable — until a JFR
    // profile at 50,000 measured requests showed dcz's histogram "bump" (a
    // recurring 15-30% slow cluster, reproducible across five separate runs)
    // shrink to noise. dcz's request path touches more distinct methods
    // (dictionary reference, Rfc9842Frame.unwrap, extra header parsing) than
    // plain zstd's, so it needs more invocations to fully tier up through the
    // JIT; 20 wasn't enough for either tier to reach steady state, and it
    // showed up as a spurious "dcz has a slow tail" finding that was really
    // just "dcz warms up slower." 2,000 gets close to steady state without
    // making every run take noticeably longer.
    private static final int WARMUP_REQUESTS = 2_000;
    private static final int MEASURED_REQUESTS = 10_000;

    private interface RequestFactory {
        HttpRequest create();
    }

    public static void main(String[] args) throws Exception {
        // Label only, purely informational: the server itself was started
        // with the response size fixed (see Server.java's args[0]) and this
        // doesn't change what gets requested.
        String size = args.length > 0 ? args[0] : "default";

        HttpClient http = HttpClient.newHttpClient();

        HttpResponse<byte[]> dictResponse = http.send(
                HttpRequest.newBuilder(BASE.resolve("/dictionary")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        ZstdDictionary dictionary = ZstdDictionary.of(dictResponse.body());
        UseAsDictionary useAsDictionary = UseAsDictionary.parse(
                dictResponse.headers().firstValue("Use-As-Dictionary").orElseThrow());
        String availableDictionary = AvailableDictionary.of(dictionary).toHeaderValue();
        String dictionaryId = new DictionaryId(useAsDictionary.id()).toHeaderValue();

        System.out.printf("%-6s %-10s %10s %14s %9s %9s %9s %9s %9s %12s%n",
                "size", "encoding", "req/s", "avg bytes/req", "p50 µs", "p90 µs", "p95 µs", "p99 µs", "max µs",
                "total bytes");
        // Pre-digested once, like Server.java's compressDictionary: dctx.decompress(byte[],
        // ZstdByteSize, ZstdDictionary) re-digests the dictionary from scratch on every
        // single call. Passing the raw ZstdDictionary there on every request was silently
        // taxing the dcz tier's decode cost here, the same bug fixed server-side earlier.
        try (ZstdDecompressContext dctx = new ZstdDecompressContext();
             ZstdDecompressDictionary decompressDictionary = dictionary.decompressDict()) {
            runAllTiers(size, DATA, http, dctx, dictionary, decompressDictionary, availableDictionary, dictionaryId);
        }
    }

    private static void runAllTiers(String size, URI data, HttpClient http, ZstdDecompressContext dctx,
                                     ZstdDictionary dictionary, ZstdDecompressDictionary decompressDictionary,
                                     String availableDictionary, String dictionaryId)
            throws Exception {
        run(size, "identity", http, dctx, dictionary, decompressDictionary,
                () -> HttpRequest.newBuilder(data).GET().build());
        run(size, "gzip", http, dctx, dictionary, decompressDictionary,
                () -> HttpRequest.newBuilder(data).header("Accept-Encoding", "gzip").GET().build());
        run(size, "zstd", http, dctx, dictionary, decompressDictionary,
                () -> HttpRequest.newBuilder(data).header("Accept-Encoding", "zstd").GET().build());
        run(size, "dcz", http, dctx, dictionary, decompressDictionary,
                () -> HttpRequest.newBuilder(data)
                        .header("Accept-Encoding", "dcz")
                        .header("Available-Dictionary", availableDictionary)
                        .header("Dictionary-ID", dictionaryId)
                        .GET().build());
    }

    private static void run(String size, String label, HttpClient http, ZstdDecompressContext dctx,
                             ZstdDictionary dictionary, ZstdDecompressDictionary decompressDictionary,
                             RequestFactory requestFactory) throws Exception {
        // Warm up: JIT compilation and first-call native library loading skew
        // the first few requests badly (each dcz/zstd call otherwise pays it) —
        // discard them before measuring.
        for (int i = 0; i < WARMUP_REQUESTS; i++) {
            decode(http.send(requestFactory.create(), HttpResponse.BodyHandlers.ofByteArray()), dctx, dictionary,
                    decompressDictionary);
        }

        long totalBytes = 0;
        long[] latenciesNanos = new long[MEASURED_REQUESTS];
        long start = System.nanoTime();
        for (int i = 0; i < MEASURED_REQUESTS; i++) {
            long requestStart = System.nanoTime();
            HttpResponse<byte[]> response = http.send(requestFactory.create(), HttpResponse.BodyHandlers.ofByteArray());
            totalBytes += response.body().length;
            decode(response, dctx, dictionary, decompressDictionary); // pay the real decode cost, same as a real client would
            latenciesNanos[i] = System.nanoTime() - requestStart;
        }
        double elapsedSeconds = (System.nanoTime() - start) / 1_000_000_000.0;

        Arrays.sort(latenciesNanos);
        System.out.printf("%-6s %-10s %10.1f %14.1f %9.1f %9.1f %9.1f %9.1f %9.1f %12d%n",
                size, label,
                MEASURED_REQUESTS / elapsedSeconds,
                (double) totalBytes / MEASURED_REQUESTS,
                percentileMicros(latenciesNanos, 50),
                percentileMicros(latenciesNanos, 90),
                percentileMicros(latenciesNanos, 95),
                percentileMicros(latenciesNanos, 99),
                latenciesNanos[latenciesNanos.length - 1] / 1000.0,
                totalBytes);
        printHistogram(latenciesNanos);
    }

    /// A 10-bucket ASCII bar chart of `sortedNanos` (already sorted
    /// ascending) — the percentile columns alone hide shape (bimodal? a long
    /// thin tail? one dominant cluster?) that a histogram shows at a glance.
    private static void printHistogram(long[] sortedNanos) {
        int bucketCount = 10;
        int barWidth = 40;
        long min = sortedNanos[0];
        long max = sortedNanos[sortedNanos.length - 1];
        long range = Math.max(1, max - min);

        int[] counts = new int[bucketCount];
        for (long nanos : sortedNanos) {
            int bucket = (int) Math.min(bucketCount - 1, (nanos - min) * bucketCount / range);
            counts[bucket]++;
        }
        int maxCount = Arrays.stream(counts).max().orElse(1);

        for (int i = 0; i < bucketCount; i++) {
            long bucketStartUs = (min + range * i / bucketCount) / 1000;
            long bucketEndUs = (min + range * (i + 1) / bucketCount) / 1000;
            int barLength = maxCount == 0 ? 0 : counts[i] * barWidth / maxCount;
            System.out.printf("           %6d-%-6d us | %-" + barWidth + "s %d%n",
                    bucketStartUs, bucketEndUs, "#".repeat(barLength), counts[i]);
        }
    }

    /// The `p`-th percentile of `sortedNanos` (already sorted ascending), in
    /// microseconds. Nearest-rank method: good enough at this sample count, no
    /// need for a proper interpolating estimator or a streaming histogram.
    private static double percentileMicros(long[] sortedNanos, double p) {
        int rank = (int) Math.ceil(p / 100.0 * sortedNanos.length) - 1;
        rank = Math.clamp(rank, 0, sortedNanos.length - 1);
        return sortedNanos[rank] / 1000.0;
    }

    private static byte[] decode(HttpResponse<byte[]> response, ZstdDecompressContext dctx, ZstdDictionary dictionary,
                                  ZstdDecompressDictionary decompressDictionary) throws Exception {
        String contentEncoding = response.headers().firstValue("Content-Encoding").orElse("identity");
        return switch (contentEncoding) {
            case "dcz" -> {
                // unwrap still needs the raw dictionary: it verifies the dcz header's
                // SHA-256 hash against dictionary.toByteArray(), not the digested form.
                byte[] frame = Rfc9842Frame.unwrap(response.body(), dictionary);
                ZstdByteSize size = ZstdFrame.decompressedSize(frame);
                yield dctx.decompress(frame, size, decompressDictionary);
            }
            case "zstd" -> dctx.decompress(response.body());
            case "gzip" -> {
                try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(response.body()))) {
                    yield gzip.readAllBytes();
                }
            }
            default -> response.body();
        };
    }
}
