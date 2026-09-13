import io.github.dfa1.zstd.ZstdByteSize;
import io.github.dfa1.zstd.ZstdDecompressContext;
import io.github.dfa1.zstd.ZstdDecompressDictionary;
import io.github.dfa1.zstd.ZstdDictionary;
import io.github.dfa1.zstd.ZstdFrame;
import io.github.dfa1.zstd.rfc9842.AvailableDictionary;
import io.github.dfa1.zstd.rfc9842.DictionaryId;
import io.github.dfa1.zstd.rfc9842.Rfc9842DictionaryHash;
import io.github.dfa1.zstd.rfc9842.Rfc9842Frame;
import io.github.dfa1.zstd.rfc9842.UseAsDictionary;

import java.io.ByteArrayInputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/// RFC 9842 (Compression Dictionary Transport)-aware demo client — the
/// counterpart to `NaiveClient` in this directory, against the same
/// Server.java. Fetches the dictionary once, then advertises everything it can
/// decode on every request — `gzip, zstd`, plus `dcz` and the dictionary
/// itself on requests the dictionary applies to — letting the server pick the
/// best encoding it actually has available for that request.
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
    private static final String DATA_PATH = "/api/data";

    /// The prepared `/api/data` request plus the extra request-header bytes
    /// offering a dictionary costs, so the report below can show what the
    /// negotiation itself is worth.
    private record DataRequest(HttpRequest request, int extraHeaderBytes) {
    }

    public static void main(String[] args) throws Exception {
        // Pinned to HTTP/1.1 (the JDK HttpServer speaks nothing else, so the
        // default HTTP/2 client only spends an h2c upgrade attempt finding
        // that out), and closed at the end — HttpClient is AutoCloseable since
        // JDK 21, and closing shuts down its selector and executor threads.
        try (HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            // Step 1: fetch the dictionary and learn where it applies.
            HttpRequest dictRequest = HttpRequest.newBuilder(BASE.resolve("/dictionary")).GET().build();
            System.out.println("[rfc9842-client] GET /dictionary request headers:  " + dictRequest.headers().map());
            HttpResponse<byte[]> dictResponse = http.send(dictRequest, HttpResponse.BodyHandlers.ofByteArray());
            System.out.println("[rfc9842-client] GET /dictionary response headers: " + dictResponse.headers().map());

            ZstdDictionary dictionary = ZstdDictionary.of(dictResponse.body());
            UseAsDictionary useAsDictionary = UseAsDictionary.parse(
                    dictResponse.headers().firstValue("Use-As-Dictionary").orElseThrow());
            System.out.println("[rfc9842-client] stored dictionary (" + dictResponse.body().length
                    + " bytes), applies to '" + useAsDictionary.match() + "', id=" + useAsDictionary.id());

            // Everything the request needs, built once up front rather than per
            // call: the hashes (AvailableDictionary.of/Rfc9842DictionaryHash.of
            // both re-hash the whole dictionary on every call) and the request
            // itself, which is immutable and documented as sendable repeatedly.
            String availableDictionary = AvailableDictionary.of(dictionary).toHeaderValue();
            Rfc9842DictionaryHash dictionaryHash = Rfc9842DictionaryHash.of(dictionary);
            DataRequest dataRequest = dataRequest(availableDictionary, useAsDictionary);
            System.out.println("[rfc9842-client] GET " + DATA_PATH + " request headers:  "
                    + dataRequest.request().headers().map());

            // Step 2 & 3: fetch data, advertising the dictionary when it applies.
            // Digested once and reused, like Server.java's compressDictionary: creating a
            // fresh ZstdDecompressContext and re-digesting the dictionary on every single
            // call — as an earlier version of this client did — pays real native setup
            // cost per request instead of once.
            try (ZstdDecompressContext dctx = new ZstdDecompressContext();
                 ZstdDecompressDictionary decompressDictionary = dictionary.decompressDict()) {
                for (int i = 0; i < 3; i++) {
                    fetchData(http, dataRequest, dctx, dictionaryHash, decompressDictionary);
                }
            }
        }
    }

    /// Builds the one `/api/data` request this client sends over and over,
    /// reporting the extra header bytes offering a dictionary costs.
    ///
    /// RFC 9842 §6.1 is the reason `dcz` is conditional rather than always
    /// advertised: a client that has no dictionary matching the request "MUST
    /// NOT send its dictionary-aware content encodings in the `Accept-Encoding`
    /// request header" — asking for an encoding it could not then decode.
    private static DataRequest dataRequest(String availableDictionary, UseAsDictionary useAsDictionary) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(BASE.resolve(DATA_PATH)).GET();
        boolean offeringDictionary = useAsDictionary.matchesPath(DATA_PATH);
        String acceptEncoding = offeringDictionary ? "gzip, zstd, dcz" : "gzip, zstd";
        builder.header("Accept-Encoding", acceptEncoding);
        int requestHeaderBytes = headerBytes("Accept-Encoding", acceptEncoding);

        if (offeringDictionary) {
            String dictionaryId = new DictionaryId(useAsDictionary.id()).toHeaderValue();
            builder.header("Available-Dictionary", availableDictionary)
                    .header("Dictionary-ID", dictionaryId);
            requestHeaderBytes += headerBytes("Available-Dictionary", availableDictionary)
                    + headerBytes("Dictionary-ID", dictionaryId);
        }
        return new DataRequest(builder.build(), requestHeaderBytes);
    }

    private static void fetchData(HttpClient http, DataRequest dataRequest, ZstdDecompressContext dctx,
                                   Rfc9842DictionaryHash dictionaryHash,
                                   ZstdDecompressDictionary decompressDictionary) throws Exception {
        // Round trip starts here: send, receive, and (below) verify/decompress
        // are all part of what this request actually costs the caller.
        long start = System.nanoTime();
        HttpResponse<byte[]> response = http.send(dataRequest.request(), HttpResponse.BodyHandlers.ofByteArray());
        System.out.println("[rfc9842-client] GET " + DATA_PATH + " response headers: " + response.headers().map());

        String contentEncoding = response.headers().firstValue("Content-Encoding").orElse("identity");
        int receivedBytes = response.body().length;

        byte[] payload = switch (contentEncoding) {
            case "dcz" -> decodeDcz(response.body(), dctx, dictionaryHash, decompressDictionary);
            case "zstd" -> dctx.decompress(response.body());
            case "gzip" -> {
                try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(response.body()))) {
                    yield gzip.readAllBytes();
                }
            }
            default -> response.body();
        };
        double roundTripMicros = (System.nanoTime() - start) / 1_000.0;

        System.out.printf("[rfc9842-client] round trip: %.1f µs, %d extra request header bytes, "
                        + "%d response bytes (%s) -> %d payload bytes%n",
                roundTripMicros, dataRequest.extraHeaderBytes(), receivedBytes, contentEncoding, payload.length);
        System.out.println("[rfc9842-client]   body: " + new String(payload, StandardCharsets.UTF_8).strip());
    }

    /// Approximate wire size of one request header line, for reporting the
    /// extra cost of offering a dictionary.
    private static int headerBytes(String name, String value) {
        return (name + ": " + value + "\r\n").getBytes(StandardCharsets.UTF_8).length;
    }

    /// Verifies and decompresses a `dcz` response body, touching native
    /// memory once and the JVM heap once — see `PerfTest.decodeDcz`, which
    /// this mirrors, for why the straightforward `unwrap`-then-`decompress`
    /// byte[] path it replaces copies the frame three times instead.
    private static byte[] decodeDcz(byte[] body, ZstdDecompressContext dctx, Rfc9842DictionaryHash dictionaryHash,
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
