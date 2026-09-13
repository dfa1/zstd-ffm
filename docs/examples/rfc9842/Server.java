import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.github.dfa1.zstd.ZstdCompressContext;
import io.github.dfa1.zstd.ZstdDictionary;
import io.github.dfa1.zstd.rfc9842.AvailableDictionary;
import io.github.dfa1.zstd.rfc9842.Rfc9842Frame;
import io.github.dfa1.zstd.rfc9842.UseAsDictionary;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
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

    // A toy "dictionary": a handful of sample events sharing the same JSON
    // shape as the data /api/data actually serves. A real dictionary would be
    // trained with ZstdDictionary.train(...) on a representative sample set;
    // a literal shared prefix like this works too — see ZstdDictionary.of.
    private static final byte[] DICTIONARY_BYTES = ("""
            {"event":"page_view","user":"alice","path":"/home","timestamp":1699999001,"properties":{"referrer":"https://example.com","device":"desktop"}}
            {"event":"page_view","user":"bob","path":"/pricing","timestamp":1699999002,"properties":{"referrer":"https://example.com","device":"mobile"}}
            {"event":"click","user":"carol","path":"/signup","timestamp":1699999003,"properties":{"referrer":"https://example.com","device":"desktop"}}
            {"event":"page_view","user":"dave","path":"/docs","timestamp":1699999004,"properties":{"referrer":"https://example.com","device":"mobile"}}
            """).getBytes(StandardCharsets.UTF_8);

    // A batch, not a single record: at millions of requests/day, a per-request
    // saving too small to beat the negotiation headers' own byte cost (see
    // README.md) isn't worth it. A realistic small batch is.
    private static final int BATCH_SIZE = 20;
    private static final String[] EVENT_TYPES = {"page_view", "click", "scroll"};
    private static final String[] USERS = {"eve", "frank", "grace", "heidi", "ivan"};
    private static final String[] PATHS = {"/checkout", "/cart", "/product/42", "/search"};
    private static final String[] DEVICES = {"desktop", "mobile", "tablet"};

    private static final AtomicInteger EVENT_COUNTER = new AtomicInteger();

    public static void main(String[] args) throws IOException {
        ZstdDictionary dictionary = ZstdDictionary.of(DICTIONARY_BYTES);
        AvailableDictionary expectedHash = AvailableDictionary.of(dictionary);

        HttpServer server = HttpServer.create(new InetSocketAddress(9842), 0);

        server.createContext("/dictionary", exchange -> {
            logRequest(exchange);
            exchange.getResponseHeaders().add("Use-As-Dictionary",
                    new UseAsDictionary("/api/*", DICTIONARY_ID).toHeaderValue());
            System.out.println("[server] serving dictionary (" + DICTIONARY_BYTES.length + " bytes)");
            sendBody(exchange, 200, DICTIONARY_BYTES);
        });

        server.createContext("/api/data", exchange -> {
            logRequest(exchange);
            byte[] payload = nextBatch(BATCH_SIZE).getBytes(StandardCharsets.UTF_8);

            Set<String> accepted = parseAcceptEncoding(exchange.getRequestHeaders().getFirst("Accept-Encoding"));
            String availableDictionaryHeader = exchange.getRequestHeaders().getFirst("Available-Dictionary");
            String dictionaryIdHeader = exchange.getRequestHeaders().getFirst("Dictionary-ID");
            boolean clientHasTheRightDictionary = availableDictionaryHeader != null && dictionaryIdHeader != null
                    && DICTIONARY_ID.equals(unquote(dictionaryIdHeader))
                    && expectedHash.equals(AvailableDictionary.parse(availableDictionaryHeader));

            byte[] body;
            String contentEncoding = null;
            if (accepted.contains("dcz") && clientHasTheRightDictionary) {
                byte[] frame;
                try (ZstdCompressContext cctx = new ZstdCompressContext()) {
                    frame = cctx.compress(payload, dictionary);
                }
                body = Rfc9842Frame.wrap(frame, dictionary);
                contentEncoding = "dcz";
            } else if (accepted.contains("zstd")) {
                try (ZstdCompressContext cctx = new ZstdCompressContext()) {
                    body = cctx.compress(payload);
                }
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

    /// A batch of `count` realistic, varied events — a client analytics/event
    /// API endpoint's actual response shape, not a single toy record.
    private static String nextBatch(int count) {
        StringBuilder batch = new StringBuilder();
        for (int i = 0; i < count; i++) {
            int n = EVENT_COUNTER.incrementAndGet();
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
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
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
