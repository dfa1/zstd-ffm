import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.github.dfa1.zstd.Zstd;
import io.github.dfa1.zstd.ZstdByteSize;
import io.github.dfa1.zstd.ZstdCompressContext;
import io.github.dfa1.zstd.ZstdCompressDictionary;
import io.github.dfa1.zstd.ZstdDictionary;
import io.github.dfa1.zstd.rfc9842.AvailableDictionary;
import io.github.dfa1.zstd.rfc9842.Rfc9842DictionaryHash;
import io.github.dfa1.zstd.rfc9842.Rfc9842Exception;
import io.github.dfa1.zstd.rfc9842.Rfc9842Frame;
import io.github.dfa1.zstd.rfc9842.UseAsDictionary;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

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
/// side against the same server. Pass `--quiet` to turn that off: writing
/// those lines is by far the most expensive thing this server does per
/// request, so `PerfTest` should always be run against a quiet server (see
/// [#QUIET_FLAG]).
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

    /// Comfortably above the longest event line [#appendEvent] can render: a
    /// batch stops at the first event that takes it past `targetBytes`, so
    /// `targetBytes + MAX_EVENT_BYTES` is an upper bound on one, and the batch
    /// builder can be sized once to never regrow.
    private static final int MAX_EVENT_BYTES = 512;

    /// Turns off the per-request header logging that is otherwise this demo's
    /// whole point — pass it when running `PerfTest`.
    ///
    /// Printing each request's and response's headers costs roughly 8 µs of
    /// synchronous `System.out` per request: measured as this server's single
    /// largest per-request cost, ahead of compression itself, and enough to
    /// skew exactly what `PerfTest` is trying to measure. Quiet is worth about
    /// +10% req/s and −8 µs p50 on every tier.
    private static final String QUIET_FLAG = "--quiet";

    private static final AtomicInteger EVENT_COUNTER = new AtomicInteger();

    public static void main(String[] args) throws IOException {
        boolean quiet = false;
        int size = DEFAULT_TARGET_BYTES;
        for (String arg : args) {
            if (QUIET_FLAG.equals(arg)) {
                quiet = true;
            } else {
                size = Integer.parseInt(arg);
            }
        }
        // Effectively final from here on, so the handlers below can close over them.
        boolean verbose = !quiet;
        int targetBytes = size;

        // One builder, reused for the whole training corpus and then for every
        // served batch: rendering a batch is the one piece of per-request work
        // that isn't HTTP or compression, so it stays off the allocator.
        StringBuilder batchBuilder = new StringBuilder(targetBytes + MAX_EVENT_BYTES);

        List<byte[]> trainingSamples = new ArrayList<>(TRAINING_SAMPLE_COUNT);
        Random trainingRandom = new Random(TRAINING_SEED);
        for (int i = 0; i < TRAINING_SAMPLE_COUNT; i++) {
            trainingSamples.add(nextBatch(batchBuilder, targetBytes, trainingRandom));
        }
        ZstdDictionary dictionary = ZstdDictionary.train(trainingSamples, ZstdByteSize.ofKiB(1));
        byte[] dictionaryBytes = dictionary.toByteArray();
        AvailableDictionary expectedHash = AvailableDictionary.of(dictionary);
        // The exact header value a conforming client sends for this dictionary.
        // `Available-Dictionary` is a Structured Field Byte Sequence, whose
        // base64 serialization is canonical, so comparing the raw strings
        // decides the common case without running the SFV parser (which
        // base64-decodes into a fresh array and clones it into a record) —
        // falling back to a real parse only when the strings differ.
        String expectedAvailableDictionary = expectedHash.toHeaderValue();
        // Precomputed so the per-request path never builds it: the value is
        // fixed for the lifetime of this server.
        String useAsDictionary = new UseAsDictionary("/api/*", DICTIONARY_ID).toHeaderValue();
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
            logRequest(exchange, verbose);
            exchange.getResponseHeaders().add("Use-As-Dictionary", useAsDictionary);
            // RFC 9842 §2.2.1: a stored dictionary only matches a later request
            // while it is still fresh (or explicitly stale-servable), so an
            // uncacheable dictionary is worthless — the client would refetch
            // more bytes than the dictionary ever saves it.
            exchange.getResponseHeaders().add("Cache-Control", "max-age=604800");
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            if (verbose) {
                System.out.println("[server] serving dictionary (" + dictionaryBytes.length + " bytes)");
            }
            sendBody(exchange, 200, dictionaryBytes, verbose);
        });

        server.createContext("/api/data", exchange -> {
            logRequest(exchange, verbose);
            byte[] payload = nextBatch(batchBuilder, targetBytes, EVENT_COUNTER);

            String acceptEncoding = exchange.getRequestHeaders().getFirst("Accept-Encoding");

            byte[] body;
            String contentEncoding = null;
            // Short-circuit order matters: `dcz` is the only tier that needs the
            // dictionary headers, so the other three never pay to parse them.
            if (accepts(acceptEncoding, "dcz")
                    && hasMatchingDictionary(exchange, expectedAvailableDictionary, expectedHash)) {
                body = compressDcz(payload, cctx, compressDictionary, dictionaryHash);
                contentEncoding = "dcz";
            } else if (accepts(acceptEncoding, "zstd")) {
                body = cctx.compress(payload);
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
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            // RFC 9842 §6.2: a cacheable response negotiated on
            // `Available-Dictionary` MUST vary on it, or a shared cache will
            // hand a dictionary-compressed body to a client holding a different
            // dictionary — or none at all, which is undecodable rather than
            // merely suboptimal.
            exchange.getResponseHeaders().add("Vary", "accept-encoding, available-dictionary");
            if (contentEncoding != null) {
                exchange.getResponseHeaders().add("Content-Encoding", contentEncoding);
            }
            sendBody(exchange, 200, body, verbose);
        });

        server.start();
        System.out.println("[server] listening on http://localhost:9842, " + targetBytes + "-byte responses, "
                + (verbose ? "logging every request (pass " + QUIET_FLAG + " for PerfTest runs)" : "quiet")
                + " (Ctrl+C to stop)");
    }

    private static void logRequest(HttpExchange exchange, boolean verbose) {
        if (!verbose) {
            return;
        }
        System.out.println("[server] " + exchange.getRequestMethod() + " " + exchange.getRequestURI()
                + " request headers: " + exchange.getRequestHeaders());
    }

    /// Whether this request offers exactly the dictionary this server compresses
    /// against: the right `Dictionary-ID`, and an `Available-Dictionary` hash
    /// equal to ours.
    ///
    /// `Available-Dictionary` is a Structured Field Byte Sequence, and its
    /// base64 form is canonical, so the string comparison decides every
    /// conforming client's request outright. `AvailableDictionary.parse` runs
    /// only when the strings differ — a non-canonical-but-equal encoding, or a
    /// genuinely different dictionary — instead of base64-decoding and cloning
    /// a 32-byte array on every request.
    private static boolean hasMatchingDictionary(HttpExchange exchange, String expectedAvailableDictionary,
                                                  AvailableDictionary expectedHash) {
        String availableDictionary = exchange.getRequestHeaders().getFirst("Available-Dictionary");
        String dictionaryId = exchange.getRequestHeaders().getFirst("Dictionary-ID");
        if (availableDictionary == null || dictionaryId == null || !DICTIONARY_ID.equals(unquote(dictionaryId))) {
            return false;
        }
        if (expectedAvailableDictionary.equals(availableDictionary)) {
            return true;
        }
        try {
            return expectedHash.equals(AvailableDictionary.parse(availableDictionary));
        } catch (Rfc9842Exception e) {
            // A header value that isn't a valid byte sequence is a client that
            // doesn't have our dictionary, not a server error: fall through the
            // ladder to plain zstd rather than failing the request with a 500.
            return false;
        }
    }

    /// Whether `acceptEncoding` names `token` as one of its encodings, ignoring
    /// any `;q=` weighting (this demo's ladder is best-first, not
    /// weight-driven) and matching case-insensitively, as HTTP tokens are.
    ///
    /// Scans the header in place rather than `split(",")` into a `Set`: the
    /// same three questions get asked of a short, fixed header on every single
    /// request, and the split version allocated an array, a substring per
    /// token, a lowercased copy of each, and a set to hold them.
    private static boolean accepts(String acceptEncoding, String token) {
        if (acceptEncoding == null) {
            return false;
        }
        for (int start = 0; start <= acceptEncoding.length(); ) {
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

    /// A batch of realistic, varied events totaling at least `targetBytes` —
    /// a client analytics/event API endpoint's actual response shape, not a
    /// single toy record. Driven by an ever-incrementing counter so every
    /// served response is distinct real "traffic".
    ///
    /// @param batch       a builder to render into, reset on entry and reused across calls
    /// @param targetBytes the batch's minimum size
    /// @param counter     the live traffic counter driving the event values
    /// @return the rendered batch, UTF-8 encoded
    private static byte[] nextBatch(StringBuilder batch, int targetBytes, AtomicInteger counter) {
        batch.setLength(0);
        while (batch.length() < targetBytes) {
            int n = counter.incrementAndGet();
            appendEvent(batch,
                    EVENT_TYPES[n % EVENT_TYPES.length],
                    USERS[n % USERS.length],
                    PATHS[n % PATHS.length],
                    1_700_000_000L + n,
                    DEVICES[n % DEVICES.length]);
        }
        return batch.toString().getBytes(StandardCharsets.UTF_8);
    }

    /// Same shape as [#nextBatch(StringBuilder, int, AtomicInteger)], but
    /// driven by a caller-supplied `Random` instead of the live traffic
    /// counter — used only to build the training corpus, which must stay
    /// disjoint from anything actually served.
    private static byte[] nextBatch(StringBuilder batch, int targetBytes, Random random) {
        batch.setLength(0);
        while (batch.length() < targetBytes) {
            appendEvent(batch,
                    EVENT_TYPES[random.nextInt(EVENT_TYPES.length)],
                    USERS[random.nextInt(USERS.length)],
                    PATHS[random.nextInt(PATHS.length)],
                    1_700_000_000L + random.nextInt(1_000_000),
                    DEVICES[random.nextInt(DEVICES.length)]);
        }
        return batch.toString().getBytes(StandardCharsets.UTF_8);
    }

    /// Renders one event line — byte for byte what the `"""…""".formatted(…)`
    /// template this replaces produced, appended straight into `batch` rather
    /// than through a per-event `String`.
    ///
    /// `String.formatted` parses its format string and spins up a `Formatter`
    /// on every call, which at ~17 events per 2 KB batch measured ~3.2 µs per
    /// request against ~0.8 µs for these appends — small next to the ~70 µs
    /// round trip, but it is pure per-request tax on a path that exists only
    /// to produce bytes for the compressors to work on.
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

    /// Compresses `payload` against `dictionary` and prepends the RFC 9842
    /// `dcz` header, touching native memory once and the JVM heap once.
    ///
    /// The straightforward `cctx.compress(payload, dictionary)` followed by
    /// `Rfc9842Frame.wrap(frame, hash)` — what an earlier version of this
    /// method did — allocates and copies the compressed frame twice on the
    /// request hot path: once out of native memory into a `frame` array,
    /// then again into a second, header-prefixed array. Reserving the header
    /// bytes up front and compressing directly past them collapses that to a
    /// single native buffer and a single final copy out to `body`.
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
        // Presized to a plausible compressed-size guess and a larger Deflater
        // buffer than the 512-byte default: an unsized ByteArrayOutputStream
        // would otherwise double (and copy) repeatedly to hold a large payload.
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 3));
        try (GZIPOutputStream gzip = new GZIPOutputStream(out, Math.min(65536, Math.max(512, data.length)))) {
            gzip.write(data);
        }
        return out.toByteArray();
    }

    private static void sendBody(HttpExchange exchange, int status, byte[] body, boolean verbose) throws IOException {
        if (verbose) {
            System.out.println("[server] -> " + status + " response headers: " + exchange.getResponseHeaders());
        }
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
