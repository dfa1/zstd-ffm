import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/// An HTTP client with **no** RFC 9842 (Compression Dictionary Transport)
/// awareness — the counterpart to `Rfc9842Client` in this directory, against
/// the same Server.java. It never fetches `/dictionary` and never sends
/// `Available-Dictionary`/`Dictionary-ID`, so the server never sends it a
/// `dcz` or plain-`zstd` response. It does send `Accept-Encoding: gzip` —
/// the one compression negotiation nearly every real HTTP client does by
/// default — so it still gets HTTP's universal baseline.
///
/// Run from the repository root (see README.md in this directory for the
/// one-time build step and the exact classpath), after starting Server.java:
/// {@snippet :
/// java --class-path "$(find . -path '*/target/classes' | tr '\n' ':')" \
///      docs/examples/rfc9842/NaiveClient.java
/// }
public class NaiveClient {

    private static final URI BASE = URI.create("http://localhost:9842");

    public static void main(String[] args) throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        for (int i = 0; i < 3; i++) {
            fetchData(http);
        }
    }

    private static void fetchData(HttpClient http) throws Exception {
        String path = "/api/data";
        HttpRequest request = HttpRequest.newBuilder(BASE.resolve(path))
                .header("Accept-Encoding", "gzip")
                .GET().build();
        System.out.println("[naive-client] GET " + path + " request headers:  " + request.headers().map());

        // Round trip starts here: send, receive, and (below) decode are all
        // part of what this request actually costs the caller.
        long start = System.nanoTime();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        System.out.println("[naive-client] GET " + path + " response headers: " + response.headers().map());

        int receivedBytes = response.body().length;
        Optional<String> contentEncoding = response.headers().firstValue("Content-Encoding");
        byte[] payloadBytes = contentEncoding.filter("gzip"::equals).isPresent()
                ? new GZIPInputStream(new ByteArrayInputStream(response.body())).readAllBytes()
                : response.body();
        double roundTripMicros = (System.nanoTime() - start) / 1_000.0;

        System.out.printf("[naive-client] round trip: %.1f µs, 0 extra request header bytes, "
                        + "%d response bytes -> %d payload bytes%n",
                roundTripMicros, receivedBytes, payloadBytes.length);
        System.out.println("[naive-client]   body: " + new String(payloadBytes, StandardCharsets.UTF_8).strip());
    }
}
