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

`run.sh` computes the classpath and JVM flags for you — start the server
(leave it running in one terminal):

```bash
docs/examples/rfc9842/run.sh Server
```

In another terminal, run either client (or both, to compare):

```bash
# No RFC 9842 awareness — always gets the plain body.
docs/examples/rfc9842/run.sh NaiveClient

# Fetches the dictionary, then gets dcz-compressed responses.
docs/examples/rfc9842/run.sh Rfc9842Client
```

<details>
<summary>Running <code>java</code> directly, without <code>run.sh</code></summary>

```bash
CP="$(find . -path '*/target/classes' | tr '\n' ':')"

java --enable-native-access=ALL-UNNAMED --add-modules jdk.httpserver \
     --class-path "$CP" docs/examples/rfc9842/Server.java

# in another terminal:
java --class-path "$CP" docs/examples/rfc9842/NaiveClient.java
java --enable-native-access=ALL-UNNAMED --class-path "$CP" \
     docs/examples/rfc9842/Rfc9842Client.java
```

</details>

Each program prints the exact request/response headers it sent and received,
plus (for the clients) the full round-trip time, the extra request-header
bytes offering a dictionary costs, and the response bytes received. `/api/data`
returns a batch of ~20 JSON events (a realistic small-API-response size, not a
single record) — small enough that a dictionary meaningfully compresses it, but
big enough that the ~120 bytes of `Available-Dictionary`/`Dictionary-ID`
negotiation overhead is trivial next to the savings (in practice: roughly
2.8 KB down to ~330 bytes). This is deliberately not a benchmark — a real
dictionary trained on your own representative traffic (`ZstdDictionary.train`)
would look different; see [../how-to.md](../how-to.md).

Stop the server with Ctrl+C when done.
