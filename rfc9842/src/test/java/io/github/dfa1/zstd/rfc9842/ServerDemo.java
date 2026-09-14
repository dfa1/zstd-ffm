package io.github.dfa1.zstd.rfc9842;

import io.github.dfa1.zstd.ZstdCompressionLevel;

/// RFC 9842 (Compression Dictionary Transport) demo server, built on
/// embedded Jetty ([DczTestServer]) so it speaks both real HTTP/1.1 and real
/// HTTP/2 (h2c) on the same port — [Rfc9842ClientDemo]'s `--http2` flag
/// compares dcz negotiation over each.
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
/// Every request's and response's headers are logged, so [NaiveClientDemo],
/// [Rfc9842ClientDemo], and [PerfTestDemo] can be compared side by side
/// against the same server. Pass `--quiet` to turn that off: writing those
/// lines is by far the most expensive thing this server does per request, so
/// [PerfTestDemo] should always be run against a quiet server.
///
/// Run from the repository root, after `./mvnw -q -pl rfc9842 test-compile`:
/// {@snippet :
/// mvn -q -pl rfc9842 exec:java -Dexec.mainClass=io.github.dfa1.zstd.rfc9842.ServerDemo \
///     -Dexec.classpathScope=test -Dexec.args="--enable-native-access=ALL-UNNAMED"
/// }
public final class ServerDemo {

    private static final int PORT = 9842;
    private static final String QUIET_FLAG = "--quiet";
    private static final String DICTIONARY_FLAG = "--dict";
    private static final String LEVEL_FLAG = "--level";

    private ServerDemo() {
    }

    public static void main(String[] args) throws Exception {
        boolean quiet = false;
        int size = DczTestServer.DEFAULT_TARGET_BYTES;
        int dictKiB = DczTestServer.DEFAULT_DICTIONARY_KIB;
        int compressionLevel = ZstdCompressionLevel.DEFAULT.value();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case QUIET_FLAG -> quiet = true;
                case DICTIONARY_FLAG -> dictKiB = Integer.parseInt(args[++i]);
                case LEVEL_FLAG -> compressionLevel = Integer.parseInt(args[++i]);
                default -> size = Integer.parseInt(args[i]);
            }
        }
        ZstdCompressionLevel level = new ZstdCompressionLevel(compressionLevel);
        DczTestServer server = DczTestServer.start(true, PORT, size, dictKiB, level, !quiet);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));

        System.out.println("[server] listening on http://localhost:" + server.port() + " (HTTP/1.1 and h2c), "
                + size + "-byte responses, " + dictKiB + " KiB dictionary, level " + level.value() + ", "
                + (quiet ? "quiet" : "logging every request (pass " + QUIET_FLAG + " for PerfTestDemo runs)")
                + " (Ctrl+C to stop)");
    }
}
