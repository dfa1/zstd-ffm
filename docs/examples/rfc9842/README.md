# RFC 9842 (Compression Dictionary Transport) demo

Three standalone JDK single-file programs — no build, no third-party
dependency, only `zstd`/`zstd-rfc9842` and JDK-standard classes
(`com.sun.net.httpserver` for the server, `java.net.http` for the clients).

- **`Server.java`** — serves `/dictionary` (with `Use-As-Dictionary`) and
  `/api/data`. Compresses `/api/data` responses as a `dcz` frame
  (`Content-Encoding: dcz`) whenever a request offers a matching
  `Available-Dictionary`/`Dictionary-ID`; otherwise sends a plain body.
  Logs every request's and response's headers.
- **`NaiveClient.java`** — a plain HTTP client with no RFC 9842 awareness. It
  never fetches `/dictionary` and never offers one, so it always gets the
  plain fallback.
- **`Rfc9842Client.java`** — fetches the dictionary once, then offers it on
  every request that applies, so the server can reply with a `dcz`-compressed
  body. Verifies and decompresses it.

Run both clients against the same server to see the difference directly.

## One-time build

From the repository root:

```bash
./mvnw -q compile
```

This compiles every module (including all six native `zstd-native-*`
libraries, which `zig cc` cross-compiles regardless of your host platform) so
`target/classes` exists everywhere the classpath below needs it.

## Run

Start the server (leave it running in one terminal):

```bash
CP="$(find . -path '*/target/classes' | tr '\n' ':')"
java --enable-native-access=ALL-UNNAMED --add-modules jdk.httpserver \
     --class-path "$CP" docs/examples/rfc9842/Server.java
```

In another terminal, run either client (or both, to compare):

```bash
CP="$(find . -path '*/target/classes' | tr '\n' ':')"

# No RFC 9842 awareness — always gets the plain body.
java --class-path "$CP" docs/examples/rfc9842/NaiveClient.java

# Fetches the dictionary, then gets dcz-compressed responses.
java --enable-native-access=ALL-UNNAMED --class-path "$CP" \
     docs/examples/rfc9842/Rfc9842Client.java
```

Each program prints the exact request/response headers it sent and received,
plus (for the clients) bytes received and time to parse/decompress. The
payload here is small, so the size win is modest — the point is watching the
actual header negotiation and `dcz` unwrap happen, not a benchmark. A real
dictionary trained on your own representative traffic (`ZstdDictionary.train`)
would show a much bigger effect; see [../how-to.md](../how-to.md).

Stop the server with Ctrl+C when done.
