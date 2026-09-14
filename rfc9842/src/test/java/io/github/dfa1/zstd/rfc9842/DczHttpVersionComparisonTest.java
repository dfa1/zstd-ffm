package io.github.dfa1.zstd.rfc9842;

import io.github.dfa1.zstd.ZstdDictionary;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/// Measures, rather than calculates, `docs/examples/rfc9842/README.md`'s claim
/// that HTTP/2's HPACK indexing erases the repeated dcz negotiation headers'
/// per-request cost that HTTP/1.1 pays in full on every request. Same
/// dictionary, same request sequence, same server logic ([DczTestServer], a
/// Jetty port of that demo's `Server.java`) — the only variable is HTTP/1.1
/// versus HTTP/2 (h2c) on the connector.
class DczHttpVersionComparisonTest {

    private static final int WARMED_UP_REQUESTS = 20;

    @Test
    void http2CarriesRepeatedDczNegotiationInFewerWireBytesThanHttp1() throws Exception {
        // Given two identically configured dcz origins, HTTP/1.1-only and h2c
        long http1Bytes;
        try (DczTestServer server = DczTestServer.start(false)) {
            http1Bytes = sendRepeatedDataRequests(server, HttpClient.Version.HTTP_1_1);
        }
        long http2Bytes;
        try (DczTestServer server = DczTestServer.start(true)) {
            http2Bytes = sendRepeatedDataRequests(server, HttpClient.Version.HTTP_2);
        }

        System.out.printf("[dcz-http-comparison] HTTP/1.1: %d bytes / %d requests (%.1f B/req)%n",
                http1Bytes, WARMED_UP_REQUESTS, http1Bytes / (double) WARMED_UP_REQUESTS);
        System.out.printf("[dcz-http-comparison] HTTP/2:   %d bytes / %d requests (%.1f B/req)%n",
                http2Bytes, WARMED_UP_REQUESTS, http2Bytes / (double) WARMED_UP_REQUESTS);

        // Then — HTTP/2's HPACK table indexes the repeated Available-Dictionary/
        // Dictionary-ID/Accept-Encoding headers after the first request; HTTP/1.1
        // re-sends them as literal text on every single one.
        assertThat(http2Bytes).isLessThan(http1Bytes);
    }

    /// Fetches the dictionary once, then sends [#WARMED_UP_REQUESTS] identical
    /// `/api/data` requests offering it, returning the wire bytes (both
    /// directions) those repeated requests cost — the dictionary fetch and the
    /// connection's one-time HTTP/2 upgrade handshake are excluded by taking
    /// the byte-count delta around the loop, not the connector's lifetime total.
    private long sendRepeatedDataRequests(DczTestServer server, HttpClient.Version version) throws Exception {
        try (HttpClient http = HttpClient.newBuilder().version(version).build()) {
            HttpRequest dictionaryRequest = HttpRequest.newBuilder(server.dictionaryUri()).GET().build();
            HttpResponse<byte[]> dictionaryResponse =
                    http.send(dictionaryRequest, HttpResponse.BodyHandlers.ofByteArray());
            ZstdDictionary dictionary = ZstdDictionary.of(dictionaryResponse.body());
            UseAsDictionary useAsDictionary = UseAsDictionary.parse(
                    dictionaryResponse.headers().firstValue("Use-As-Dictionary").orElseThrow());
            String availableDictionary = AvailableDictionary.of(dictionary).toHeaderValue();
            String dictionaryId = new DictionaryId(useAsDictionary.id()).toHeaderValue();

            HttpRequest dataRequest = HttpRequest.newBuilder(server.dataUri()).GET()
                    .header("Accept-Encoding", "gzip, zstd, dcz")
                    .header("Available-Dictionary", availableDictionary)
                    .header("Dictionary-ID", dictionaryId)
                    .build();

            long before = server.wireBytes();
            for (int i = 0; i < WARMED_UP_REQUESTS; i++) {
                HttpResponse<byte[]> response = http.send(dataRequest, HttpResponse.BodyHandlers.ofByteArray());
                assertThat(response.headers().firstValue("Content-Encoding")).contains("dcz");
            }
            long after = server.wireBytes();
            return after - before;
        }
    }
}
