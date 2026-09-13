# RFC 9842 (Compression Dictionary Transport) demo

Four standalone JDK single-file programs — no build, no third-party
dependency, only `zstd`/`zstd-rfc9842` and JDK-standard classes
(`com.sun.net.httpserver` for the server, `java.net.http` for the clients,
`java.util.zip` for gzip).

- **`Server.java`** — serves `/dictionary` (with `Use-As-Dictionary`) and
  `/api/data`, negotiated via `Accept-Encoding` with a four-rung ladder, best
  first: `dcz` (zstd + dictionary, RFC 9842) if the request offers a matching
  `Available-Dictionary`/`Dictionary-ID`, else plain `zstd` (RFC 8878) if
  accepted, else `gzip` (the universal HTTP baseline) if accepted, else a
  plain body. Logs every request's and response's headers.
- **`NaiveClient.java`** — no RFC 9842 awareness. Never fetches `/dictionary`,
  never offers one — but does send `Accept-Encoding: gzip`, the one
  compression negotiation nearly every real HTTP client does by default. Gets
  the `gzip` tier.
- **`Rfc9842Client.java`** — fetches the dictionary once, then advertises
  `Accept-Encoding: gzip, zstd, dcz` and offers the dictionary whenever it
  applies, letting the server pick the best tier it actually has. Decodes
  whichever comes back.
- **`PerfTest.java`** — hits the same server across all four tiers, reporting
  requests/second and total bytes transferred for each.

Run the clients (or `PerfTest`) against the same server to compare directly.

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

In another terminal:

```bash
# No RFC 9842 awareness — gets the gzip tier.
docs/examples/rfc9842/run.sh NaiveClient

# Fetches the dictionary, then gets the dcz tier.
docs/examples/rfc9842/run.sh Rfc9842Client

# req/s and total bytes across all four tiers.
docs/examples/rfc9842/run.sh PerfTest
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
java --enable-native-access=ALL-UNNAMED --class-path "$CP" \
     docs/examples/rfc9842/PerfTest.java
```

</details>

`Server.java` and the clients print the exact request/response headers sent
and received, plus (for the clients) the full round-trip time, the extra
request-header bytes offering a dictionary costs, and the response bytes
received. `/api/data` returns a batch of ~20 JSON events (a realistic
small-API-response size, not a single record) — small enough that a
dictionary meaningfully compresses it, but big enough that the ~130 bytes of
`Available-Dictionary`/`Dictionary-ID` negotiation overhead is trivial next
to the savings.

`PerfTest.java` (500 measured requests per tier, 20 discarded as warmup) shows
a typical run:

```
encoding        req/s  avg bytes/req     avg µs/req  total bytes
identity       3024.8         2828.0          330.6      1414003
gzip           4135.8          361.4          241.8       180683
zstd           4782.8          377.0          209.1       188484
dcz            4973.6          328.5          201.1       164266
```

`dcz` wins on both KPIs here — fewer bytes *and* higher throughput, since on
localhost the cost of moving/parsing ~2.8 KB dominates over the CPU cost of
compression. Over a real network the gap would be larger still. This is
deliberately not a rigorous benchmark (single connection, single thread,
localhost only) — a real dictionary trained on your own representative
traffic (`ZstdDictionary.train`) would look somewhat different in the
specifics, though the same shape; see [../how-to.md](../how-to.md).

Stop the server with Ctrl+C when done.
