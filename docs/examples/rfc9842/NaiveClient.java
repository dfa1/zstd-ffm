import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/// A plain HTTP client with **no** RFC 9842 (Compression Dictionary
/// Transport) awareness — the counterpart to `Rfc9842Client` in this
/// directory, against the same Server.java. It never fetches `/dictionary`
/// and never sends `Available-Dictionary`/`Dictionary-ID`, so the server
/// always falls back to a plain, uncompressed body for it.
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
        HttpRequest request = HttpRequest.newBuilder(BASE.resolve(path)).GET().build();
        System.out.println("[naive-client] GET " + path + " request headers:  " + request.headers().map());

        // Round trip starts here: send, receive, and (below) decode are all
        // part of what this request actually costs the caller.
        long start = System.nanoTime();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        System.out.println("[naive-client] GET " + path + " response headers: " + response.headers().map());

        int receivedBytes = response.body().length;
        // Nothing to decompress or verify: this client only ever gets a plain
        // body back, since it never offered a dictionary the server could use.
        String payload = new String(response.body(), StandardCharsets.UTF_8);
        double roundTripMicros = (System.nanoTime() - start) / 1_000.0;

        System.out.printf("[naive-client] round trip: %.1f µs, 0 extra request header bytes, "
                        + "%d response bytes -> %d payload bytes%n",
                roundTripMicros, receivedBytes, payload.getBytes(StandardCharsets.UTF_8).length);
        System.out.println("[naive-client]   body: " + payload.strip());
    }
}
