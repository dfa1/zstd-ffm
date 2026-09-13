import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.github.dfa1.zstd.ZstdByteSize;
import io.github.dfa1.zstd.ZstdCompressContext;
import io.github.dfa1.zstd.ZstdCompressDictionary;
import io.github.dfa1.zstd.ZstdDictionary;
import io.github.dfa1.zstd.rfc9842.AvailableDictionary;
import io.github.dfa1.zstd.rfc9842.Rfc9842DictionaryHash;
import io.github.dfa1.zstd.rfc9842.Rfc9842Frame;
import io.github.dfa1.zstd.rfc9842.UseAsDictionary;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

/// RFC 9842 (Compression Dictionary Transport) demo server, built on
/// `com.sun.net.httpserver.HttpServer` — a public, JDK-bundled API
/// (`jdk.httpserver` module), no third-party HTTP framework.
///
/// Serves a dictionary at `/dictionary` (`Use-As-Dictionary: match="/api/*",
/// id="demo-v1"`) and data at `/api/data`, negotiated via `Accept-Encoding`
/// with a four-rung ladder, best first:
///
/// 1. `dcz` — zstd compressed against the dictionary, if the request offers a
///    matching `Available-Dictionary`/`Dictionary-ID` (RFC 9842).
/// 2. `zstd` — plain zstd, no dictionary (RFC 8878), if accepted.
/// 3. `gzip` — the universal HTTP baseline (`java.util.zip`), if accepted.
/// 4. identity — a plain body, if nothing else was accepted or offered.
///
/// Every request's and response's headers are logged, so `NaiveClient`,
/// `Rfc9842Client`, and `PerfTest` in this directory can be compared side by
/// side against the same server.
///
/// Run from the repository root (see README.md in this directory for the
/// one-time build step and the exact classpath):
/// {@snippet :
/// java --enable-native-access=ALL-UNNAMED --add-modules jdk.httpserver \
///      --class-path "$(find . -path '*/target/classes' | tr '\n' ':')" \
///      docs/examples/rfc9842/Server.java
/// }
public class Server {

    private static final String DICTIONARY_ID = "demo-v1";

    // Trained (via ZstdDictionary.train), not a hand-picked toy sample: see
    // benchmark/.../DictionaryTransportBenchmark, which found a dictionary
    // that merely "looks like" the payload teaches nothing about how ZDICT
    // actually behaves on this response size. 300 independently generated
    // batches of the same shape as what /api/data serves, disjoint from any
    // batch actually served, standing in for a corpus of real past traffic.
    private static final int TRAINING_SAMPLE_COUNT = 300;
    private static final long TRAINING_SEED = 0x5EED;

    // A batch, not a single record: at millions of requests/day, a per-request
    // saving too small to beat the negotiation headers' own byte cost (see
    // README.md) isn't worth it. Configurable via args[0] (bytes) so the same
    // server can be pointed at any response size, to see how the four tiers
    // and the dictionary's own benefit shift with it — see
    // benchmark/.../DictionaryTransportBenchmark for the equivalent in-process
    // sweep.
    private static final int DEFAULT_TARGET_BYTES = 2_800;
    private static final String[] EVENT_TYPES = {"page_view", "click", "scroll"};
    private static final String[] USERS = {"eve", "frank", "grace", "heidi", "ivan"};
    private static final String[] PATHS = {"/checkout", "/cart", "/product/42", "/search"};
    private static final String[] DEVICES = {"desktop", "mobile", "tablet"};

    private static final AtomicInteger EVENT_COUNTER = new AtomicInteger();

    public static void main(String[] args) throws IOException {
        int targetBytes = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_TARGET_BYTES;

        List<byte[]> trainingSamples = new ArrayList<>(TRAINING_SAMPLE_COUNT);
        Random trainingRandom = new Random(TRAINING_SEED);
        for (int i = 0; i < TRAINING_SAMPLE_COUNT; i++) {
            trainingSamples.add(nextBatch(targetBytes, trainingRandom).getBytes(StandardCharsets.UTF_8));
        }
        ZstdDictionary dictionary = ZstdDictionary.train(trainingSamples, ZstdByteSize.ofKiB(1));
        byte[] dictionaryBytes = dictionary.toByteArray();
        AvailableDictionary expectedHash = AvailableDictionary.of(dictionary);
        // Precomputed once: Rfc9842Frame.wrap(byte[], ZstdDictionary) hashes the
        // dictionary fresh on every call, the same per-request tax the compress
        // context/dictionary digestion below was fixed for earlier.
        Rfc9842DictionaryHash dictionaryHash = Rfc9842DictionaryHash.of(dictionary);

        // Digested once, not on every request: ZstdCompressContext.compress(byte[],
        // ZstdDictionary) — what an earlier version of this demo used — re-digests
        // the dictionary from scratch on every single call. ZstdCompressDictionary
        // exists specifically to pay that cost once and reuse it; skipping it here
        // was silently taxing the dcz tier on every request.
        //
        // One ZstdCompressContext is likewise reused for both the zstd and dcz
        // tiers below, instead of creating/destroying native state per request —
        // safe here because compress(byte[], ZstdCompressDictionary) takes the
        // digested dictionary as an explicit argument each call (it is not
        // "sticky" context state), and because HttpServer with no executor set
        // dispatches exchanges sequentially on a single thread. A context is
        // "not thread-safe: confine to one thread or pool it" per its own docs —
        // pool one context per worker thread instead if this server is ever given
        // a concurrent executor.
        ZstdCompressDictionary compressDictionary = dictionary.compressDict();
        ZstdCompressContext cctx = new ZstdCompressContext();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            cctx.close();
            compressDictionary.close();
        }));

        HttpServer server = HttpServer.create(new InetSocketAddress(9842), 0);

        server.createContext("/dictionary", exchange -> {
            logRequest(exchange);
            exchange.getResponseHeaders().add("Use-As-Dictionary",
                    new UseAsDictionary("/api/*", DICTIONARY_ID).toHeaderValue());
            System.out.println("[server] serving dictionary (" + dictionaryBytes.length + " bytes)");
            sendBody(exchange, 200, dictionaryBytes);
        });

        server.createContext("/api/data", exchange -> {
            logRequest(exchange);
            byte[] payload = nextBatch(targetBytes, EVENT_COUNTER).getBytes(StandardCharsets.UTF_8);

            Set<String> accepted = parseAcceptEncoding(exchange.getRequestHeaders().getFirst("Accept-Encoding"));
            String availableDictionaryHeader = exchange.getRequestHeaders().getFirst("Available-Dictionary");
            String dictionaryIdHeader = exchange.getRequestHeaders().getFirst("Dictionary-ID");
            boolean clientHasTheRightDictionary = availableDictionaryHeader != null && dictionaryIdHeader != null
                    && DICTIONARY_ID.equals(unquote(dictionaryIdHeader))
                    && expectedHash.equals(AvailableDictionary.parse(availableDictionaryHeader));

            byte[] body;
            String contentEncoding = null;
            if (accepted.contains("dcz") && clientHasTheRightDictionary) {
                byte[] frame = cctx.compress(payload, compressDictionary);
                body = Rfc9842Frame.wrap(frame, dictionaryHash);
                contentEncoding = "dcz";
            } else if (accepted.contains("zstd")) {
                body = cctx.compress(payload);
                contentEncoding = "zstd";
            } else if (accepted.contains("gzip")) {
                body = gzip(payload);
                contentEncoding = "gzip";
            } else {
                body = payload;
            }

            System.out.println("[server] " + (contentEncoding == null ? "identity" : contentEncoding)
                    + ": " + payload.length + " -> " + body.length + " bytes");
            if (contentEncoding != null) {
                exchange.getResponseHeaders().add("Content-Encoding", contentEncoding);
            }
            sendBody(exchange, 200, body);
        });

        server.start();
        System.out.println("[server] listening on http://localhost:9842 (Ctrl+C to stop)");
    }

    private static void logRequest(HttpExchange exchange) {
        System.out.println("[server] " + exchange.getRequestMethod() + " " + exchange.getRequestURI()
                + " request headers: " + exchange.getRequestHeaders());
    }

    /// A batch of realistic, varied events totaling at least `targetBytes` —
    /// a client analytics/event API endpoint's actual response shape, not a
    /// single toy record. Driven by an ever-incrementing counter so every
    /// served response is distinct real "traffic".
    private static String nextBatch(int targetBytes, AtomicInteger counter) {
        StringBuilder batch = new StringBuilder();
        while (batch.length() < targetBytes) {
            int n = counter.incrementAndGet();
            batch.append("""
                    {"event":"%s","user":"%s","path":"%s","timestamp":%d,"properties":{"referrer":"https://example.com","device":"%s"}}
                    """.formatted(
                    EVENT_TYPES[n % EVENT_TYPES.length],
                    USERS[n % USERS.length],
                    PATHS[n % PATHS.length],
                    1_700_000_000L + n,
                    DEVICES[n % DEVICES.length]));
        }
        return batch.toString();
    }

    /// Same shape as [#nextBatch(int, AtomicInteger)], but driven by a
    /// caller-supplied `Random` instead of the live traffic counter — used
    /// only to build the training corpus, which must stay disjoint from
    /// anything actually served.
    private static String nextBatch(int targetBytes, Random random) {
        StringBuilder batch = new StringBuilder();
        while (batch.length() < targetBytes) {
            batch.append("""
                    {"event":"%s","user":"%s","path":"%s","timestamp":%d,"properties":{"referrer":"https://example.com","device":"%s"}}
                    """.formatted(
                    EVENT_TYPES[random.nextInt(EVENT_TYPES.length)],
                    USERS[random.nextInt(USERS.length)],
                    PATHS[random.nextInt(PATHS.length)],
                    1_700_000_000L + random.nextInt(1_000_000),
                    DEVICES[random.nextInt(DEVICES.length)]));
        }
        return batch.toString();
    }

    private static String unquote(String sfvString) {
        return sfvString.length() >= 2 ? sfvString.substring(1, sfvString.length() - 1) : sfvString;
    }

    /// Parses an `Accept-Encoding` header into the set of encoding tokens it
    /// names, ignoring any `;q=` weighting (not needed for this demo's
    /// simple best-first ladder).
    private static Set<String> parseAcceptEncoding(String header) {
        if (header == null) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String part : header.split(",")) {
            String token = part.split(";")[0].trim().toLowerCase(Locale.ROOT);
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static byte[] gzip(byte[] data) throws IOException {
        // Presized to a plausible compressed-size guess and a larger Deflater
        // buffer than the 512-byte default: an unsized ByteArrayOutputStream
        // would otherwise double (and copy) repeatedly to hold a large payload.
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 3));
        try (GZIPOutputStream gzip = new GZIPOutputStream(out, Math.min(65536, Math.max(512, data.length)))) {
            gzip.write(data);
        }
        return out.toByteArray();
    }

    private static void sendBody(HttpExchange exchange, int status, byte[] body) throws IOException {
        System.out.println("[server] -> " + status + " response headers: " + exchange.getResponseHeaders());
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
