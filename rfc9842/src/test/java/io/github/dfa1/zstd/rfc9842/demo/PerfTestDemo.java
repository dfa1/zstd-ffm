package io.github.dfa1.zstd.rfc9842.demo;

import io.github.dfa1.zstd.ZstdByteSize;
import io.github.dfa1.zstd.ZstdDecompressContext;
import io.github.dfa1.zstd.ZstdDecompressDictionary;
import io.github.dfa1.zstd.ZstdFrame;
import io.github.dfa1.zstd.rfc9842.AvailableDictionaryHeader;
import io.github.dfa1.zstd.rfc9842.DictionaryIdHeader;
import io.github.dfa1.zstd.rfc9842.NegotiatedDictionary;
import io.github.dfa1.zstd.rfc9842.Rfc9842Frame;
import io.github.dfa1.zstd.rfc9842.UseAsDictionaryHeader;

import java.io.ByteArrayInputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/// Small, sequential perf comparison of [ServerDemo]'s four `Content-Encoding`
/// tiers — identity, gzip, plain zstd, and RFC 9842 `dcz` — against the same
/// live server, reporting requests/second, the full round-trip latency
/// distribution (p50/p90/p99/max, not just a mean — an average alone hides
/// how much the tail differs between tiers), and total bytes transferred for
/// each. Not a rigorous benchmark (single connection, single thread, no
/// warmup methodology beyond discarding the first few calls) — just enough to
/// see the shape of the trade-off. [ServerDemo]'s response size is fixed at
/// startup; pass the same value here purely as a label for the output table.
/// Pass `--http2` to run all four tiers over real HTTP/2 (h2c) instead of the
/// default HTTP/1.1 — [ServerDemo] speaks both on the same port.
///
/// Run from the repository root, after starting [ServerDemo] `--quiet`:
/// {@snippet :
/// mvn -q -pl rfc9842 exec:java -Dexec.mainClass=io.github.dfa1.zstd.rfc9842.PerfTestDemo \
///     -Dexec.classpathScope=test -Dexec.args="--enable-native-access=ALL-UNNAMED"
/// }
public final class PerfTestDemo {

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

    private PerfTestDemo() {
    }

    private static final String HTTP2_FLAG = "--http2";

    public static void main(String[] args) throws Exception {
        // Label only, purely informational: the server itself was started
        // with the response size fixed and this doesn't change what gets requested.
        String size = "default";
        boolean http2 = false;
        for (String arg : args) {
            if (HTTP2_FLAG.equals(arg)) {
                http2 = true;
            } else {
                size = arg;
            }
        }
        HttpClient.Version version = http2 ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1;
        System.out.println("[perftest] using " + version);

        // Explicit rather than the HttpClient default of HTTP/2: ServerDemo
        // speaks both HTTP/1.1 and h2c on the same port, so --http2 picks
        // which one this run measures instead of leaving it to negotiation —
        // and pinning HTTP/1.1 by default keeps every run comparable to
        // earlier numbers and the logged request headers free of the
        // `Connection: Upgrade`/`HTTP2-Settings` pair.
        //
        // Closed at the end (HttpClient is AutoCloseable since JDK 21), which
        // shuts down its selector and executor threads rather than leaving them
        // to keep the JVM alive.
        try (HttpClient http = HttpClient.newBuilder().version(version).build()) {
            HttpResponse<byte[]> dictResponse = http.send(
                    HttpRequest.newBuilder(BASE.resolve("/dictionary")).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            // NegotiatedDictionary.from derives everything from one hash — see its
            // own doc — rather than hashing the dictionary twice for the
            // Available-Dictionary header value and the dcz wire-format hash
            // separately.
            NegotiatedDictionary negotiated = NegotiatedDictionary.from(dictResponse.body(),
                    dictResponse.headers().firstValue(UseAsDictionaryHeader.HTTP_HEADER).orElseThrow());

            System.out.printf("%-6s %-10s %10s %14s %9s %9s %9s %9s %9s %12s%n",
                    "size", "encoding", "req/s", "avg bytes/req", "p50 µs", "p90 µs", "p95 µs", "p99 µs", "max µs",
                    "total bytes");
            // Pre-digested once, like ServerDemo's compressDictionary: dctx.decompress(byte[],
            // ZstdByteSize, ZstdDictionary) re-digests the dictionary from scratch on every
            // single call. Passing the raw ZstdDictionary there on every request was silently
            // taxing the dcz tier's decode cost here, the same bug fixed server-side earlier.
            try (ZstdDecompressContext dctx = new ZstdDecompressContext();
                 ZstdDecompressDictionary decompressDictionary = negotiated.dictionary().decompressDict()) {
                runAllTiers(size, DATA, http, dctx, decompressDictionary, negotiated);
            }
        }
    }

    private static void runAllTiers(String size, URI data, HttpClient http, ZstdDecompressContext dctx,
                                     ZstdDecompressDictionary decompressDictionary, NegotiatedDictionary negotiated)
            throws Exception {
        AvailableDictionaryHeader dictionaryHash = negotiated.hash();
        // Built once per tier, not per request: HttpRequest is immutable and
        // documented as sendable more than once, so rebuilding it 12,000 times
        // only re-runs header validation and re-allocates the header map inside
        // the measured loop — work that has nothing to do with what is being
        // measured.
        run(size, "identity", http, dctx, dictionaryHash, decompressDictionary,
                HttpRequest.newBuilder(data).GET().build());
        run(size, "gzip", http, dctx, dictionaryHash, decompressDictionary,
                HttpRequest.newBuilder(data).header("Accept-Encoding", "gzip").GET().build());
        run(size, "zstd", http, dctx, dictionaryHash, decompressDictionary,
                HttpRequest.newBuilder(data).header("Accept-Encoding", "zstd").GET().build());
        // RFC 9842 §6.1: `dcz` is only offered together with the dictionary it
        // needs — advertising it without an `Available-Dictionary` would ask for
        // an encoding this client could not decode. Dictionary-ID is optional
        // (§2.3): only echoed back when the server actually assigned one.
        HttpRequest.Builder dczRequest = HttpRequest.newBuilder(data)
                .header("Accept-Encoding", "dcz")
                .header(AvailableDictionaryHeader.HTTP_HEADER, dictionaryHash.toHeaderValue());
        negotiated.dictionaryId().ifPresent(id -> dczRequest.header(DictionaryIdHeader.HTTP_HEADER, id.toHeaderValue()));
        run(size, "dcz", http, dctx, dictionaryHash, decompressDictionary, dczRequest.GET().build());
    }

    private static void run(String size, String label, HttpClient http, ZstdDecompressContext dctx,
                             AvailableDictionaryHeader dictionaryHash, ZstdDecompressDictionary decompressDictionary,
                             HttpRequest request) throws Exception {
        // Warm up: JIT compilation and first-call native library loading skew
        // the first few requests badly (each dcz/zstd call otherwise pays it) —
        // discard them before measuring.
        for (int i = 0; i < WARMUP_REQUESTS; i++) {
            decode(http.send(request, HttpResponse.BodyHandlers.ofByteArray()), dctx, dictionaryHash,
                    decompressDictionary);
        }

        long totalBytes = 0;
        long[] latenciesNanos = new long[MEASURED_REQUESTS];
        long start = System.nanoTime();
        for (int i = 0; i < MEASURED_REQUESTS; i++) {
            long requestStart = System.nanoTime();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            totalBytes += response.body().length;
            decode(response, dctx, dictionaryHash, decompressDictionary); // pay the real decode cost, same as a real client would
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

    private static byte[] decode(HttpResponse<byte[]> response, ZstdDecompressContext dctx,
                                  AvailableDictionaryHeader dictionaryHash, ZstdDecompressDictionary decompressDictionary)
            throws Exception {
        String contentEncoding = response.headers().firstValue("Content-Encoding").orElse("identity");
        return switch (contentEncoding) {
            case "dcz" -> decodeDcz(response.body(), dctx, dictionaryHash, decompressDictionary);
            case "zstd" -> dctx.decompress(response.body());
            case "gzip" -> {
                try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(response.body()))) {
                    yield gzip.readAllBytes();
                }
            }
            default -> response.body();
        };
    }

    /// Verifies and decompresses a `dcz` response body, touching native
    /// memory once and the JVM heap once.
    ///
    /// `Rfc9842Frame.unwrap(byte[], hash)` followed by
    /// `dctx.decompress(frame, size, dict)` — what this method replaces —
    /// copies the compressed frame three times on the request hot path:
    /// `unwrap`'s `Arrays.copyOfRange` strips the header into a new array,
    /// `ZstdFrame.decompressedSize(byte[])` copies that whole array into
    /// native memory just to read a header field, and `decompress` copies it
    /// into native memory again to actually decode it. Copying the response
    /// into native memory once and working with `MemorySegment` slices from
    /// there collapses all three into the one copy that was always
    /// unavoidable given `HttpResponse.BodyHandlers.ofByteArray()`, plus the
    /// one copy back out to a `byte[]` that keeps this tier's cost
    /// comparable to the others' (they all materialize the payload too).
    private static byte[] decodeDcz(byte[] body, ZstdDecompressContext dctx, AvailableDictionaryHeader dictionaryHash,
                                     ZstdDecompressDictionary decompressDictionary) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dcz = arena.allocate(body.length);
            MemorySegment.copy(body, 0, dcz, JAVA_BYTE, 0, body.length);

            MemorySegment frame = Rfc9842Frame.unwrap(dcz, dictionaryHash);
            ZstdByteSize size = ZstdFrame.decompressedSize(frame);
            MemorySegment out = arena.allocate(size.value());
            long written = dctx.decompress(out, frame, decompressDictionary);

            byte[] payload = new byte[(int) written];
            MemorySegment.copy(out, JAVA_BYTE, 0, payload, 0, payload.length);
            return payload;
        }
    }
}
