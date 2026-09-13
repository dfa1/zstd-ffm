import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.github.dfa1.zstd.ZstdCompressContext;
import io.github.dfa1.zstd.ZstdDictionary;
import io.github.dfa1.zstd.rfc9842.AvailableDictionary;
import io.github.dfa1.zstd.rfc9842.Rfc9842Frame;
import io.github.dfa1.zstd.rfc9842.UseAsDictionary;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/// RFC 9842 (Compression Dictionary Transport) demo server, built on
/// `com.sun.net.httpserver.HttpServer` — a public, JDK-bundled API
/// (`jdk.httpserver` module), no third-party HTTP framework.
///
/// Serves a dictionary at `/dictionary` (`Use-As-Dictionary: match="/api/*",
/// id="demo-v1"`) and data at `/api/data`. When a request carries a matching
/// `Available-Dictionary`/`Dictionary-ID`, the response is compressed against
/// the dictionary and sent as a `dcz` frame (`Content-Encoding: dcz`);
/// otherwise it falls back to a plain body. Every request's and response's
/// headers are logged, so `NaiveClient` and `Rfc9842Client` in this directory
/// can be compared side by side against the same server.
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
            byte[] payload = nextEvent().getBytes(StandardCharsets.UTF_8);
            String availableDictionaryHeader = exchange.getRequestHeaders().getFirst("Available-Dictionary");
            String dictionaryIdHeader = exchange.getRequestHeaders().getFirst("Dictionary-ID");

            boolean clientHasTheRightDictionary = availableDictionaryHeader != null && dictionaryIdHeader != null
                    && DICTIONARY_ID.equals(unquote(dictionaryIdHeader))
                    && expectedHash.equals(AvailableDictionary.parse(availableDictionaryHeader));

            if (clientHasTheRightDictionary) {
                byte[] frame;
                try (ZstdCompressContext cctx = new ZstdCompressContext()) {
                    frame = cctx.compress(payload, dictionary);
                }
                byte[] dcz = Rfc9842Frame.wrap(frame, dictionary);
                System.out.println("[server] dictionary recognized, sending dcz ("
                        + payload.length + " -> " + dcz.length + " bytes)");
                exchange.getResponseHeaders().add("Content-Encoding", "dcz");
                sendBody(exchange, 200, dcz);
            } else {
                System.out.println("[server] no matching dictionary offered, sending plain body ("
                        + payload.length + " bytes)");
                sendBody(exchange, 200, payload);
            }
        });

        server.start();
        System.out.println("[server] listening on http://localhost:9842 (Ctrl+C to stop)");
    }

    private static void logRequest(HttpExchange exchange) {
        System.out.println("[server] " + exchange.getRequestMethod() + " " + exchange.getRequestURI()
                + " request headers: " + exchange.getRequestHeaders());
    }

    private static String nextEvent() {
        int n = EVENT_COUNTER.incrementAndGet();
        return """
                {"event":"click","user":"eve","path":"/checkout","timestamp":170000%04d,"properties":{"referrer":"https://example.com","device":"desktop"}}"""
                .formatted(n);
    }

    private static String unquote(String sfvString) {
        return sfvString.length() >= 2 ? sfvString.substring(1, sfvString.length() - 1) : sfvString;
    }

    private static void sendBody(HttpExchange exchange, int status, byte[] body) throws IOException {
        System.out.println("[server] -> " + status + " response headers: " + exchange.getResponseHeaders());
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
