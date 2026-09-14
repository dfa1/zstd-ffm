package io.github.dfa1.zstd.rfc9842;

import io.github.dfa1.zstd.Zstd;
import io.github.dfa1.zstd.ZstdByteSize;
import io.github.dfa1.zstd.ZstdCompressContext;
import io.github.dfa1.zstd.ZstdCompressDictionary;
import io.github.dfa1.zstd.ZstdCompressionLevel;
import io.github.dfa1.zstd.ZstdDictionary;

import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/// An embedded-Jetty port of `Server.java`'s dcz negotiation logic (same
/// four-rung `Accept-Encoding` ladder: `dcz`, `zstd`, `gzip`, identity), so
/// both the automated tests in this package and [ServerDemo] can put real
/// HTTP/1.1 and real HTTP/2 traffic on the wire instead of reasoning about
/// HPACK indexing analytically.
///
/// Two connector shapes only: `http2 = false` wires plain
/// `HttpConnectionFactory` (HTTP/1.1 only); `true` adds
/// `HTTP2CServerConnectionFactory` alongside it, so `java.net.http.HttpClient`
/// requesting `Version.HTTP_2` over `http://` completes the RFC 7540 §3.2
/// cleartext upgrade and every request after the first is real HTTP/2 framing.
///
/// Requests are handled strictly sequentially by every caller in this test
/// suite (one blocking `HttpClient.send` at a time), so — like the demo
/// server it mirrors — the shared `StringBuilder` and `ZstdCompressContext`
/// below are safe without synchronization only because nothing overlaps them.
final class DczTestServer implements AutoCloseable {

    static final String DICTIONARY_ID = "test-v1";
    static final String DICTIONARY_PATH = "/dictionary";
    static final String DATA_PATH = "/api/data";

    static final int DEFAULT_TARGET_BYTES = 2_800;
    static final int DEFAULT_DICTIONARY_KIB = 4;

    private static final int TRAINING_SAMPLE_COUNT = 300;
    private static final long TRAINING_SEED = 0x5EED;
    private static final int MAX_EVENT_BYTES = 512;
    private static final String[] EVENT_TYPES = {"page_view", "click", "scroll"};
    private static final String[] USERS = {"eve", "frank", "grace", "heidi", "ivan"};
    private static final String[] PATHS = {"/checkout", "/cart", "/product/42", "/search"};
    private static final String[] DEVICES = {"desktop", "mobile", "tablet"};

    private final Server server;
    private final ServerConnector connector;
    private final WireByteCounter byteCounter;
    private final ZstdCompressContext cctx;
    private final ZstdCompressDictionary compressDictionary;

    /// @param http2      also wire `HTTP2CServerConnectionFactory` (h2c) alongside HTTP/1.1
    /// @param port       the port to bind, or `0` for an ephemeral one ([#dictionaryUri()]/[#dataUri()] resolve it)
    /// @param targetBytes minimum size of each served `/api/data` batch
    /// @param dictKiB    dictionary size cap handed to `ZstdDictionary.train`
    /// @param level      zstd compression level for the `zstd` and `dcz` tiers
    /// @param verbose    log every request's and response's headers, like `Server.java` did
    private DczTestServer(boolean http2, int port, int targetBytes, int dictKiB, ZstdCompressionLevel level,
                           boolean verbose) throws Exception {
        StringBuilder batchBuilder = new StringBuilder(targetBytes + MAX_EVENT_BYTES);
        List<byte[]> trainingSamples = new ArrayList<>(TRAINING_SAMPLE_COUNT);
        Random trainingRandom = new Random(TRAINING_SEED);
        for (int i = 0; i < TRAINING_SAMPLE_COUNT; i++) {
            trainingSamples.add(nextBatch(batchBuilder, targetBytes, trainingRandom));
        }
        ZstdDictionary dictionary = ZstdDictionary.train(trainingSamples, ZstdByteSize.ofKiB(dictKiB));
        byte[] dictionaryBytes = dictionary.toByteArray();
        AvailableDictionary expectedHash = AvailableDictionary.of(dictionary);
        String expectedAvailableDictionary = expectedHash.toHeaderValue();
        String useAsDictionary = new UseAsDictionary("/api/*", DICTIONARY_ID).toHeaderValue();
        Rfc9842DictionaryHash dictionaryHash = Rfc9842DictionaryHash.of(dictionary);

        this.compressDictionary = dictionary.compressDict(level);
        this.cctx = new ZstdCompressContext().level(level);
        ZstdCompressContext cctxRef = this.cctx;
        ZstdCompressDictionary compressDictionaryRef = this.compressDictionary;

        AtomicInteger eventCounter = new AtomicInteger();

        HttpConfiguration httpConfig = new HttpConfiguration();
        server = new Server();
        connector = http2
                ? new ServerConnector(server, new HttpConnectionFactory(httpConfig),
                        new HTTP2CServerConnectionFactory(httpConfig))
                : new ServerConnector(server, new HttpConnectionFactory(httpConfig));
        byteCounter = new WireByteCounter();
        connector.addBean(byteCounter);
        connector.setPort(port);
        server.addConnector(connector);

        server.setHandler(new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception {
                String path = request.getHttpURI().getPath();
                if (verbose) {
                    System.out.println("[server] " + request.getMethod() + " " + path
                            + " request headers: " + request.getHeaders());
                }
                if (DICTIONARY_PATH.equals(path)) {
                    response.getHeaders().put("Use-As-Dictionary", useAsDictionary);
                    response.getHeaders().put("Cache-Control", "max-age=604800");
                    response.getHeaders().put("Content-Type", "application/octet-stream");
                    if (verbose) {
                        System.out.println("[server] serving dictionary (" + dictionaryBytes.length + " bytes)");
                    }
                    write(response, callback, dictionaryBytes, verbose);
                    return true;
                }
                if (DATA_PATH.equals(path)) {
                    byte[] payload = nextBatch(batchBuilder, targetBytes, eventCounter);
                    String acceptEncoding = request.getHeaders().get("Accept-Encoding");

                    byte[] body;
                    String contentEncoding = null;
                    if (accepts(acceptEncoding, "dcz")
                            && hasMatchingDictionary(request, expectedAvailableDictionary, expectedHash)) {
                        body = compressDcz(payload, cctxRef, compressDictionaryRef, dictionaryHash);
                        contentEncoding = "dcz";
                    } else if (accepts(acceptEncoding, "zstd")) {
                        body = cctxRef.compress(payload);
                        contentEncoding = "zstd";
                    } else if (accepts(acceptEncoding, "gzip")) {
                        body = gzip(payload);
                        contentEncoding = "gzip";
                    } else {
                        body = payload;
                    }

                    if (verbose) {
                        System.out.println("[server] " + (contentEncoding == null ? "identity" : contentEncoding)
                                + ": " + payload.length + " -> " + body.length + " bytes");
                    }
                    response.getHeaders().put("Content-Type", "application/json");
                    response.getHeaders().put("Vary", "accept-encoding, available-dictionary");
                    if (contentEncoding != null) {
                        response.getHeaders().put("Content-Encoding", contentEncoding);
                    }
                    write(response, callback, body, verbose);
                    return true;
                }
                response.setStatus(404);
                write(response, callback, new byte[0], verbose);
                return true;
            }
        });
        server.start();
    }

    private static void write(Response response, Callback callback, byte[] body, boolean verbose) {
        if (verbose) {
            System.out.println("[server] -> " + response.getStatus() + " response headers: " + response.getHeaders());
        }
        response.write(true, ByteBuffer.wrap(body), callback);
    }

    /// A quiet server on an ephemeral port, [#DEFAULT_TARGET_BYTES]/
    /// [#DEFAULT_DICTIONARY_KIB]/the default compression level — everything
    /// the automated tests in this package need.
    static DczTestServer start(boolean http2) throws Exception {
        return new DczTestServer(http2, 0, DEFAULT_TARGET_BYTES, DEFAULT_DICTIONARY_KIB,
                ZstdCompressionLevel.DEFAULT, false);
    }

    /// Full control — what [ServerDemo]'s CLI flags need.
    static DczTestServer start(boolean http2, int port, int targetBytes, int dictKiB, ZstdCompressionLevel level,
                                boolean verbose) throws Exception {
        return new DczTestServer(http2, port, targetBytes, dictKiB, level, verbose);
    }

    int port() {
        return connector.getLocalPort();
    }

    URI dictionaryUri() {
        return URI.create("http://localhost:" + port() + DICTIONARY_PATH);
    }

    URI dataUri() {
        return URI.create("http://localhost:" + port() + DATA_PATH);
    }

    /// Total bytes (both directions) seen so far on every connection this
    /// connector has ever opened.
    ///
    /// Not Jetty's own `ConnectionStatistics`: that bean only rolls a
    /// connection's counters into its totals when the connection *closes*,
    /// and `java.net.http.HttpClient` keeps connections open and pooled — its
    /// totals stayed at zero for the whole lifetime of a reused connection.
    /// `Connection.getBytesIn()`/`getBytesOut()` update live, so summing them
    /// per tracked connection needs no close.
    long wireBytes() {
        return byteCounter.totalBytes();
    }

    @Override
    public void close() {
        try {
            server.stop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            throw new IllegalStateException("failed to stop DczTestServer", e);
        }
        cctx.close();
        compressDictionary.close();
    }

    private static boolean hasMatchingDictionary(Request request, String expectedAvailableDictionary,
                                                  AvailableDictionary expectedHash) {
        String availableDictionary = request.getHeaders().get("Available-Dictionary");
        String dictionaryId = request.getHeaders().get("Dictionary-ID");
        if (availableDictionary == null || dictionaryId == null || !DICTIONARY_ID.equals(unquote(dictionaryId))) {
            return false;
        }
        if (expectedAvailableDictionary.equals(availableDictionary)) {
            return true;
        }
        try {
            return expectedHash.equals(AvailableDictionary.parse(availableDictionary));
        } catch (Rfc9842Exception e) {
            return false;
        }
    }

    private static boolean accepts(String acceptEncoding, String token) {
        if (acceptEncoding == null) {
            return false;
        }
        for (int start = 0; start <= acceptEncoding.length();) {
            int comma = acceptEncoding.indexOf(',', start);
            int end = comma < 0 ? acceptEncoding.length() : comma;
            int semicolon = acceptEncoding.indexOf(';', start);
            int tokenEnd = semicolon >= 0 && semicolon < end ? semicolon : end;
            while (start < tokenEnd && acceptEncoding.charAt(start) == ' ') {
                start++;
            }
            while (tokenEnd > start && acceptEncoding.charAt(tokenEnd - 1) == ' ') {
                tokenEnd--;
            }
            if (tokenEnd - start == token.length()
                    && acceptEncoding.regionMatches(true, start, token, 0, token.length())) {
                return true;
            }
            if (comma < 0) {
                return false;
            }
            start = comma + 1;
        }
        return false;
    }

    private static byte[] nextBatch(StringBuilder batch, int targetBytes, AtomicInteger counter) {
        batch.setLength(0);
        while (batch.length() < targetBytes) {
            int n = counter.incrementAndGet();
            appendEvent(batch, EVENT_TYPES[n % EVENT_TYPES.length], USERS[n % USERS.length],
                    PATHS[n % PATHS.length], 1_700_000_000L + n, DEVICES[n % DEVICES.length]);
        }
        return batch.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] nextBatch(StringBuilder batch, int targetBytes, Random random) {
        batch.setLength(0);
        while (batch.length() < targetBytes) {
            appendEvent(batch, EVENT_TYPES[random.nextInt(EVENT_TYPES.length)], USERS[random.nextInt(USERS.length)],
                    PATHS[random.nextInt(PATHS.length)], 1_700_000_000L + random.nextInt(1_000_000),
                    DEVICES[random.nextInt(DEVICES.length)]);
        }
        return batch.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendEvent(StringBuilder batch, String event, String user, String path,
                                     long timestamp, String device) {
        batch.append("{\"event\":\"").append(event)
                .append("\",\"user\":\"").append(user)
                .append("\",\"path\":\"").append(path)
                .append("\",\"timestamp\":").append(timestamp)
                .append(",\"properties\":{\"referrer\":\"https://example.com\",\"device\":\"").append(device)
                .append("\"}}\n");
    }

    private static String unquote(String sfvString) {
        return sfvString.length() >= 2 ? sfvString.substring(1, sfvString.length() - 1) : sfvString;
    }

    private static byte[] compressDcz(byte[] payload, ZstdCompressContext cctx, ZstdCompressDictionary dictionary,
                                       Rfc9842DictionaryHash dictionaryHash) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = arena.allocate(payload.length);
            MemorySegment.copy(payload, 0, in, JAVA_BYTE, 0, payload.length);

            ZstdByteSize bound = Zstd.compressBound(new ZstdByteSize(payload.length));
            MemorySegment dst = arena.allocate(Rfc9842Frame.HEADER_SIZE + bound.value());
            MemorySegment frameOut = dst.asSlice(Rfc9842Frame.HEADER_SIZE, bound.value());
            long written = cctx.compress(frameOut, in, dictionary);
            long total = Rfc9842Frame.wrap(dst, frameOut.asSlice(0, written), dictionaryHash);

            byte[] body = new byte[(int) total];
            MemorySegment.copy(dst, JAVA_BYTE, 0, body, 0, body.length);
            return body;
        }
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 3));
        try (GZIPOutputStream gzip = new GZIPOutputStream(out, Math.min(65536, Math.max(512, data.length)))) {
            gzip.write(data);
        }
        return out.toByteArray();
    }

    /// Tracks every connection this connector opens and sums their live
    /// `getBytesIn()`/`getBytesOut()` on demand — see [#wireBytes()] for why
    /// this exists instead of Jetty's own `ConnectionStatistics`.
    private static final class WireByteCounter implements Connection.Listener {

        private final List<Connection> connections = new CopyOnWriteArrayList<>();

        @Override
        public void onOpened(Connection connection) {
            connections.add(connection);
        }

        @Override
        public void onClosed(Connection connection) {
            // Counters stay readable after close; nothing to do here.
        }

        long totalBytes() {
            long total = 0;
            for (Connection connection : connections) {
                total += connection.getBytesIn() + connection.getBytesOut();
            }
            return total;
        }
    }
}
