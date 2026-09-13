import io.github.dfa1.zstd.ZstdByteSize;
import io.github.dfa1.zstd.ZstdDecompressContext;
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
import java.util.zip.GZIPInputStream;

/// Small, sequential perf comparison of Server.java's four `Content-Encoding`
/// tiers — identity, gzip, plain zstd, and RFC 9842 `dcz` — against the same
/// live server, reporting requests/second and total bytes transferred for
/// each. Not a rigorous benchmark (single connection, single thread, no
/// warmup methodology beyond discarding the first few calls) — just enough to
/// see the shape of the trade-off.
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
    private static final int WARMUP_REQUESTS = 20;
    private static final int MEASURED_REQUESTS = 500;

    private interface RequestFactory {
        HttpRequest create();
    }

    public static void main(String[] args) throws Exception {
        HttpClient http = HttpClient.newHttpClient();

        HttpResponse<byte[]> dictResponse = http.send(
                HttpRequest.newBuilder(BASE.resolve("/dictionary")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        ZstdDictionary dictionary = ZstdDictionary.of(dictResponse.body());
        UseAsDictionary useAsDictionary = UseAsDictionary.parse(
                dictResponse.headers().firstValue("Use-As-Dictionary").orElseThrow());
        String availableDictionary = AvailableDictionary.of(dictionary).toHeaderValue();
        String dictionaryId = new DictionaryId(useAsDictionary.id()).toHeaderValue();

        System.out.printf("%-10s %10s %14s %14s %12s%n",
                "encoding", "req/s", "avg bytes/req", "avg µs/req", "total bytes");
        try (ZstdDecompressContext dctx = new ZstdDecompressContext()) {
            run("identity", http, dctx, dictionary,
                    () -> HttpRequest.newBuilder(DATA).GET().build());
            run("gzip", http, dctx, dictionary,
                    () -> HttpRequest.newBuilder(DATA).header("Accept-Encoding", "gzip").GET().build());
            run("zstd", http, dctx, dictionary,
                    () -> HttpRequest.newBuilder(DATA).header("Accept-Encoding", "zstd").GET().build());
            run("dcz", http, dctx, dictionary,
                    () -> HttpRequest.newBuilder(DATA)
                            .header("Accept-Encoding", "dcz")
                            .header("Available-Dictionary", availableDictionary)
                            .header("Dictionary-ID", dictionaryId)
                            .GET().build());
        }
    }

    private static void run(String label, HttpClient http, ZstdDecompressContext dctx, ZstdDictionary dictionary,
                             RequestFactory requestFactory) throws Exception {
        // Warm up: JIT compilation and first-call native library loading skew
        // the first few requests badly (each dcz/zstd call otherwise pays it) —
        // discard them before measuring.
        for (int i = 0; i < WARMUP_REQUESTS; i++) {
            decode(http.send(requestFactory.create(), HttpResponse.BodyHandlers.ofByteArray()), dctx, dictionary);
        }

        long totalBytes = 0;
        long start = System.nanoTime();
        for (int i = 0; i < MEASURED_REQUESTS; i++) {
            HttpResponse<byte[]> response = http.send(requestFactory.create(), HttpResponse.BodyHandlers.ofByteArray());
            totalBytes += response.body().length;
            decode(response, dctx, dictionary); // pay the real decode cost, same as a real client would
        }
        double elapsedSeconds = (System.nanoTime() - start) / 1_000_000_000.0;

        System.out.printf("%-10s %10.1f %14.1f %14.1f %12d%n",
                label,
                MEASURED_REQUESTS / elapsedSeconds,
                (double) totalBytes / MEASURED_REQUESTS,
                elapsedSeconds * 1_000_000 / MEASURED_REQUESTS,
                totalBytes);
    }

    private static byte[] decode(HttpResponse<byte[]> response, ZstdDecompressContext dctx, ZstdDictionary dictionary)
            throws Exception {
        String contentEncoding = response.headers().firstValue("Content-Encoding").orElse("identity");
        return switch (contentEncoding) {
            case "dcz" -> {
                byte[] frame = Rfc9842Frame.unwrap(response.body(), dictionary);
                ZstdByteSize size = ZstdFrame.decompressedSize(frame);
                yield dctx.decompress(frame, size, dictionary);
            }
            case "zstd" -> dctx.decompress(response.body());
            case "gzip" -> new GZIPInputStream(new ByteArrayInputStream(response.body())).readAllBytes();
            default -> response.body();
        };
    }
}
