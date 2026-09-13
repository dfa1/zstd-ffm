import io.github.dfa1.zstd.ZstdByteSize;
import io.github.dfa1.zstd.ZstdDecompressContext;
import io.github.dfa1.zstd.ZstdDictionary;
import io.github.dfa1.zstd.ZstdFrame;
import io.github.dfa1.zstd.rfc9842.AvailableDictionary;
import io.github.dfa1.zstd.rfc9842.DictionaryId;
import io.github.dfa1.zstd.rfc9842.Rfc9842Frame;
import io.github.dfa1.zstd.rfc9842.UseAsDictionary;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/// RFC 9842 (Compression Dictionary Transport)-aware demo client — the
/// counterpart to `NaiveClient` in this directory, against the same
/// Server.java. Fetches the dictionary once, then offers it on every request
/// that applies, so the server can reply with a `dcz`-compressed body instead
/// of a plain one.
///
/// Run from the repository root (see README.md in this directory for the
/// one-time build step and the exact classpath), after starting Server.java:
/// {@snippet :
/// java --enable-native-access=ALL-UNNAMED \
///      --class-path "$(find . -path '*/target/classes' | tr '\n' ':')" \
///      docs/examples/rfc9842/Rfc9842Client.java
/// }
public class Rfc9842Client {

    private static final URI BASE = URI.create("http://localhost:9842");

    public static void main(String[] args) throws Exception {
        HttpClient http = HttpClient.newHttpClient();

        // Step 1: fetch the dictionary and learn where it applies.
        HttpRequest dictRequest = HttpRequest.newBuilder(BASE.resolve("/dictionary")).GET().build();
        System.out.println("[rfc9842-client] GET /dictionary request headers:  " + dictRequest.headers().map());
        HttpResponse<byte[]> dictResponse = http.send(dictRequest, HttpResponse.BodyHandlers.ofByteArray());
        System.out.println("[rfc9842-client] GET /dictionary response headers: " + dictResponse.headers().map());

        ZstdDictionary dictionary = ZstdDictionary.of(dictResponse.body());
        UseAsDictionary useAsDictionary = UseAsDictionary.parse(
                dictResponse.headers().firstValue("Use-As-Dictionary").orElseThrow());
        System.out.println("[rfc9842-client] stored dictionary (" + dictResponse.body().length + " bytes), applies to '"
                + useAsDictionary.match() + "', id=" + useAsDictionary.id());

        // Step 2 & 3: fetch data, advertising the dictionary when it applies.
        for (int i = 0; i < 3; i++) {
            fetchData(http, dictionary, useAsDictionary);
        }
    }

    private static void fetchData(HttpClient http, ZstdDictionary dictionary, UseAsDictionary useAsDictionary)
            throws Exception {
        String path = "/api/data";
        HttpRequest.Builder builder = HttpRequest.newBuilder(BASE.resolve(path)).GET();

        boolean offeringDictionary = useAsDictionary.matchesPath(path);
        if (offeringDictionary) {
            builder.header("Accept-Encoding", "dcz")
                    .header("Available-Dictionary", AvailableDictionary.of(dictionary).toHeaderValue())
                    .header("Dictionary-ID", new DictionaryId(useAsDictionary.id()).toHeaderValue());
        }

        HttpRequest request = builder.build();
        System.out.println("[rfc9842-client] GET " + path + " request headers:  " + request.headers().map());
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        System.out.println("[rfc9842-client] GET " + path + " response headers: " + response.headers().map());

        Optional<String> contentEncoding = response.headers().firstValue("Content-Encoding");
        int receivedBytes = response.body().length;

        byte[] payload;
        long start = System.nanoTime();
        if (contentEncoding.isPresent() && contentEncoding.get().equals("dcz")) {
            byte[] frame = Rfc9842Frame.unwrap(response.body(), dictionary);
            ZstdByteSize size = ZstdFrame.decompressedSize(frame);
            try (ZstdDecompressContext dctx = new ZstdDecompressContext()) {
                payload = dctx.decompress(frame, size, dictionary);
            }
        } else {
            payload = response.body();
        }
        double micros = (System.nanoTime() - start) / 1_000.0;

        System.out.printf("[rfc9842-client] %d bytes received, %.1f µs to parse/decompress -> %d bytes payload%n",
                receivedBytes, micros, payload.length);
        System.out.println("[rfc9842-client]   body: " + new String(payload, StandardCharsets.UTF_8).strip());
    }
}
