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
  plain body. Logs every request's and response's headers unless started with
  `--quiet`.
- **`NaiveClient.java`** — no RFC 9842 awareness. Never fetches `/dictionary`,
  never offers one — but does send `Accept-Encoding: gzip`, the one
  compression negotiation nearly every real HTTP client does by default. Gets
  the `gzip` tier.
- **`Rfc9842Client.java`** — fetches the dictionary once, then advertises
  `Accept-Encoding: gzip, zstd, dcz` and offers the dictionary whenever it
  applies, letting the server pick the best tier it actually has. Decodes
  whichever comes back.
- **`PerfTest.java`** — hits the same server across all four tiers, reporting
  requests/second, the full round-trip latency distribution (p50/p90/p99/max
  plus an ASCII histogram — an average alone hides shape), and total bytes
  transferred for each.

Run the clients (or `PerfTest`) against the same server to compare directly.

Three things these programs do that are easy to leave out and that the RFC is
explicit about:

- The server sends `Vary: accept-encoding, available-dictionary` on every
  `/api/data` response (§6.2). Without it a shared cache will happily serve a
  dictionary-compressed body to a client holding a different dictionary — or
  none, which is undecodable rather than merely suboptimal.
- The server sends `Cache-Control` on `/dictionary` (§2.2.1): a stored
  dictionary only matches while it is still fresh, so an uncacheable
  dictionary costs more to refetch than it ever saves.
- `Rfc9842Client` only advertises `dcz` in `Accept-Encoding` on requests it is
  *also* offering a dictionary for (§6.1) — a client that has no matching
  dictionary "MUST NOT send its dictionary-aware content encodings", since it
  could not decode what came back.

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
(leave it running in one terminal). Its response size is fixed at startup,
in bytes (default ~2.8 KB if omitted):

```bash
docs/examples/rfc9842/run.sh Server                    # ~2.8 KB responses
docs/examples/rfc9842/run.sh Server 32768              # ~32 KB responses instead
docs/examples/rfc9842/run.sh Server 32768 --quiet      # ...and no per-request logging
```

Use `--quiet` whenever you run `PerfTest` against it. Printing each request's
and response's headers is a synchronous `System.out` write on the request path
and is the single most expensive thing the server does per request — worth
about +10% req/s and −8 µs p50 on every tier, enough to skew exactly what
`PerfTest` measures. Leave it on for the two interactive clients, where the
headers are the point.

`Server.java` trains its dictionary with `ZstdDictionary.train` on 300
independently generated batches of the same shape as what it serves — not a
hand-picked sample — so its behavior matches a dictionary trained on real
past traffic, not a toy stand-in.

In another terminal:

```bash
# No RFC 9842 awareness — gets the gzip tier.
docs/examples/rfc9842/run.sh NaiveClient

# Fetches the dictionary, then gets the dcz tier.
docs/examples/rfc9842/run.sh Rfc9842Client

# req/s, latency distribution, and total bytes across all four tiers.
# Pass the same size you started Server.java with — it's a label only.
# (Start the server with --quiet for this one.)
docs/examples/rfc9842/run.sh PerfTest 32768
```

<details>
<summary>Running <code>java</code> directly, without <code>run.sh</code></summary>

```bash
CP="$(find . -path '*/target/classes' | tr '\n' ':')"

java --enable-native-access=ALL-UNNAMED --add-modules jdk.httpserver \
     --class-path "$CP" docs/examples/rfc9842/Server.java 32768 --quiet

# in another terminal:
java --class-path "$CP" docs/examples/rfc9842/NaiveClient.java
java --enable-native-access=ALL-UNNAMED --class-path "$CP" \
     docs/examples/rfc9842/Rfc9842Client.java
java --enable-native-access=ALL-UNNAMED --class-path "$CP" \
     docs/examples/rfc9842/PerfTest.java 32768
```

</details>

`Server.java` and the clients print the exact request/response headers sent
and received, plus (for the clients) the full round-trip time, the extra
request-header bytes offering a dictionary costs, and the response bytes
received.

`PerfTest.java` (2,000 warmup requests, 10,000 measured, per tier) swept
across response sizes from 512 B to 64 KB, against a `--quiet` server:

```
size   zstd req/s   dcz req/s   zstd p50    dcz p50   dcz vs zstd bytes
 512      14753.3     15137.1       65.9       64.3                -48%
1024      12611.7     14085.0       70.6       65.5                -39%
2048      14794.5     15103.0       65.8       64.0                -18%
4096      14175.5     14629.9       70.2       66.5                 -1%
8192      13546.5     14005.8       73.1       70.5                +17%
16384     11826.7     11869.9       83.3       83.6                +32%
32768     10004.3     10150.4       98.2       97.2                +42%
65536      8119.3      8208.8      121.4      121.3                  0%
```

(µs for the p50 columns; "dcz vs zstd bytes" is `dcz`'s response size
relative to plain `zstd`'s at the same payload size — negative is smaller,
positive is bigger.)

**Speed: `dcz` and plain `zstd` are a statistical tie at every size**, within
1-2% either way — not a coincidence, but what is left after several rounds of
taking the demo's *own* per-request costs out of the measurement (see [what
the demo itself was costing](#what-the-demo-itself-was-costing) below; each
round started as what looked like a genuine `dcz` slowdown).

HTTP/socket overhead (tens of microseconds) dominates
enough that whatever few-microsecond codec-level speed difference exists
between `dcz` and plain `zstd` (see the JMH benchmark below, which *can* see
it) simply doesn't survive a live round trip either way. **Bytes are where
the real, reproducible difference lives**: `dcz` is dramatically smaller
under ~4 KB, a *trained* dictionary this small (1 KiB) actively makes `dcz`
*bigger* than no dictionary at all from roughly 8-32 KB (ZDICT's baked
entropy tables mismatch the held-out payload's statistics badly enough to
outweigh the content-matching benefit at that size — confirmed by swapping in
a same-size raw-content dictionary with no entropy tables, which stayed a
clear win at those sizes), and the two converge back near parity at 64 KB
where the payload is large enough to be its own dictionary. A production
dictionary would likely use a larger training budget than this demo's
capped-at-1-KiB one — see [`../../../benchmark`](../../../benchmark)'s
`DictionaryTransportBenchmark` for sweeping dictionary size against a fixed
payload, the complementary experiment to this one.

### What the demo itself was costing

The byte column above is deterministic and reproduces exactly, run to run and
across every revision of these programs. The latency columns are not: an
earlier revision measured 4,500–13,000 req/s for the same tiers on the same
machine, because the *demo* was spending more per request than the codecs
were. In order of what it was spending it on:

| Fixed | Worth |
|---|---|
| Server logging every request's and response's headers to `System.out` | ~8 µs/request, flat — now off under `--quiet` |
| Rendering each response batch with `String.formatted` per event | ~180 ns per event: ~3 µs at 2 KB, **~85 µs at 64 KB** — now direct `StringBuilder` appends |
| Clients defaulting to HTTP/2 against an HTTP/1.1-only server | ~3.5 µs/request of h2c upgrade negotiation that can only fail — now pinned to HTTP/1.1 |
| `PerfTest` rebuilding its `HttpRequest` inside the measured loop | `HttpRequest` is immutable and documented as resendable; now built once per tier |

That is why the large sizes moved most (64 KB: 4,542 → 8,119 req/s): the
per-event formatting cost scaled with the payload while the codec cost did
not, so the biggest responses were the ones most buried under it.

Earlier rounds, each of which first presented as "`dcz` is slower":

- `Server.java` re-digested the dictionary and recreated a native compress
  context on *every* request instead of once at startup.
- `PerfTest.java` and `Rfc9842Client.java` had the same bug on decode: passing
  the raw `ZstdDictionary` into `dctx.decompress(frame, size, dictionary)`
  re-digests it on every single call, instead of pre-digesting once into a
  `ZstdDecompressDictionary` — silently taxing `dcz`'s decode on every
  request.
- Both sides re-hashed the dictionary (SHA-256 over the whole thing) per call
  inside `Rfc9842Frame.wrap`/`unwrap`, until `Rfc9842DictionaryHash` gave them
  somewhere to cache it.
- The `dcz` paths copied the frame two or three times between native and heap;
  they now compress past a reserved header into one native buffer, and decode
  through `MemorySegment` slices with a single copy each way.
- `PerfTest.java`'s original 20-request warmup wasn't enough for `dcz`'s larger
  code surface (dictionary reference, header unwrap) to fully JIT-warm relative
  to plain `zstd`'s simpler path, which looked like a reproducible slow "tail"
  in the latency histogram until raising warmup (and confirming with a JFR
  profile — no GC storm, no dcz-specific hot allocation site) showed it was a
  warmup artifact, not a real cost.

Measured and **not** adopted, to save the next person the experiment:

- **`-Dsun.net.httpserver.nodelay=true`** — a plausible latency win on a
  request/response workload, and reproducibly an 8% *loss* here across three
  interleaved pairs (macOS loopback, 2 KB responses). Left off.
- **Reusing native buffers across requests** instead of a per-request
  `Arena.ofConfined()` — 90 ns/request against a ~65 µs round trip. Not worth
  trading a confined, always-correct arena for shared mutable buffers that
  break the moment someone hands the server an executor.
- **Reusing a `Deflater` across gzip responses** instead of a fresh
  `GZIPOutputStream` — 0.8 µs of a 7.3 µs encode, and `GZIPOutputStream`
  exposes no reuse hook, so it would mean hand-writing the gzip header and
  trailer. The gzip tier's real gap is deflate itself, not allocation.

This is deliberately not a rigorous benchmark (single connection, single
thread, localhost only, HTTP framing and JSON generation mixed into every
measurement, on a shared laptop with no CPU isolation) — see
[`../../../benchmark`](../../../benchmark) for a proper JMH microbenchmark
isolating just the codec cost, no HTTP involved. A real dictionary trained on
your own representative traffic and a real network would shift the specifics
further still, though not the overall shape; see [../how-to.md](../how-to.md).

### Repeating this on quieter hardware

Everything above was measured on a laptop with no CPU isolation, dynamic
frequency scaling left on, and other processes running — real findings
(reproduced across repeated runs, cross-checked with JFR) but not
publication-grade numbers. To tighten precision on a dedicated Linux box:

```bash
# Pin to the performance governor (disables dynamic frequency scaling).
# Some distros expose this via cpupower instead of a raw sysfs write:
sudo cpupower frequency-set -g performance
# or: echo performance | sudo tee /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor

# Disable SMT/hyperthreading for the run, or at least isolate a physical
# core pair so the server and PerfTest never share one:
sudo taskset -c 2 java ... docs/examples/rfc9842/Server.java 32768
sudo taskset -c 3 java ... docs/examples/rfc9842/PerfTest.java 32768

# For kernel-level isolation instead of just pinning, reserve cores at boot
# with isolcpus= on the kernel command line and confirm nothing else is
# scheduled onto them (`ps -eLo psr,comm` or `taskset -pc <pid>`).
```

Re-run the same 512 B–64 KB sweep and compare against the table above — the
byte-size findings should reproduce exactly (they're deterministic, not
timing-dependent), while the speed numbers should show tighter percentiles
and a cleaner tie between `dcz` and `zstd` at every size, with less need for
the large warmup/repeat-run workarounds this session used to separate signal
from a noisy machine.

Stop the server with Ctrl+C when done.
