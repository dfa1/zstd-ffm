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
- **`PerfTest.java`** — hits the same server across all four tiers, at both
  response sizes (`small`, the default; `large`, `?size=large`), reporting
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
received. `/api/data` returns a batch of ~20 JSON events by default (~2.8 KB,
a realistic small-API-response size, not a single record) — small enough that
a dictionary meaningfully compresses it, but big enough that the ~130 bytes of
`Available-Dictionary`/`Dictionary-ID` negotiation overhead is trivial next to
the savings. `?size=large` returns a ~50 KB batch instead, to see how the
trade-off shifts with response size.

`PerfTest.java` (500 measured requests per tier, 20 discarded as warmup) shows
a typical run:

```
size   encoding        req/s  avg bytes/req     avg µs/req  total bytes
small  identity       2726.8         2828.0          366.7      1413999
small  gzip           3690.6          363.8          271.0       181911
small  zstd           4402.9          384.7          227.1       192348
small  dcz            4493.3          328.6          222.6       164282
large  identity       3639.9        50055.6          274.7     25027800
large  gzip           2261.1         2173.9          442.3      1086971
large  zstd           3979.7         2946.0          251.3      1473013
large  dcz            3773.4         2874.9          265.0      1437470
```

At **small** size, `dcz` wins outright — fewer bytes *and* the highest
throughput, since on localhost the cost of moving/parsing ~2.8 KB dominates
over the CPU cost of compression.

At **large** size the picture is more interesting, and more honest about when
dictionaries actually help. Two findings hold up consistently across repeated
runs: **identity beats gzip** (raw bytes cost less than gzip's CPU time on
localhost) and **`dcz`'s byte savings over plain `zstd` shrink to a couple of
percent** (2874.9 vs. 2946.0 bytes above, ~2.4%) — once a payload has enough
internal repetition for zstd to reference on its own, a dictionary has little
left to add. Whether plain `zstd` or `dcz` comes out faster, though, varies
run to run (this is a single-threaded, single-connection, localhost
measurement, not a controlled benchmark) — they're close enough in practice
that the dictionary's wrap/unwrap overhead and its marginal compression gain
roughly cancel out at this size. This is the real shape of when dictionary
compression is worth it: small,
self-similar messages that don't have enough redundancy of their own — not
large payloads, which become their own dictionary.

This is deliberately not a rigorous benchmark (single connection, single
thread, localhost only) — a real dictionary trained on your own
representative traffic (`ZstdDictionary.train`) and a real network would
shift the specifics, though not this overall shape; see
[../how-to.md](../how-to.md).

Stop the server with Ctrl+C when done.
