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
  requests/second, the full round-trip latency distribution (p50/p90/p99/max
  plus an ASCII histogram — an average alone hides shape), and total bytes
  transferred for each.

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
(leave it running in one terminal). Its response size is fixed at startup,
in bytes (default ~2.8 KB if omitted):

```bash
docs/examples/rfc9842/run.sh Server           # ~2.8 KB responses
docs/examples/rfc9842/run.sh Server 32768     # ~32 KB responses instead
```

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
docs/examples/rfc9842/run.sh PerfTest 32768
```

<details>
<summary>Running <code>java</code> directly, without <code>run.sh</code></summary>

```bash
CP="$(find . -path '*/target/classes' | tr '\n' ':')"

java --enable-native-access=ALL-UNNAMED --add-modules jdk.httpserver \
     --class-path "$CP" docs/examples/rfc9842/Server.java 32768

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
across response sizes from 512 B to 64 KB:

```
size   zstd req/s   dcz req/s   zstd p50    dcz p50   dcz vs zstd bytes
 512      13281.3     13466.8       74.7       73.5                -48%
1024      13189.6     13080.0       74.5       75.0                -39%
2048      12446.6     12450.7       79.1       79.5                -18%
4096      11406.8     11555.1       86.5       86.1                 -1%
8192      10252.7     10367.7       96.1       95.3                +17%
16384      8684.4      8663.6      114.0      114.2                +32%
32768      6605.3      6519.8      149.3      151.1                +42%
65536      4541.6      4506.8      216.9      219.5                 0%
```

(µs for the p50 columns; "dcz vs zstd bytes" is `dcz`'s response size
relative to plain `zstd`'s at the same payload size — negative is smaller,
positive is bigger.)

**Speed: `dcz` and plain `zstd` are a statistical tie at every size**, within
1-2% either way — not a coincidence, but the result of fixing two real bugs
found while chasing what first looked like a genuine `dcz` slowdown:

- `Server.java` originally re-digested the dictionary and recreated a native
  compress context on *every* request instead of once at startup.
- `PerfTest.java` and `Rfc9842Client.java` had the same bug on decode: passing
  the raw `ZstdDictionary` into `dctx.decompress(frame, size, dictionary)`
  re-digests it on every single call, instead of pre-digesting once into a
  `ZstdDecompressDictionary` — silently taxing `dcz`'s decode on every
  request.
- Separately, `PerfTest.java`'s original 20-request warmup wasn't enough for
  `dcz`'s larger code surface (dictionary reference, header unwrap) to fully
  JIT-warm relative to plain `zstd`'s simpler path, which looked like a
  reproducible slow "tail" in the latency histogram until raising warmup (and
  confirming with a JFR profile — no GC storm, no dcz-specific hot allocation
  site) showed it was a warmup artifact, not a real cost.

With all three fixed, HTTP/socket overhead (tens of microseconds) dominates
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
