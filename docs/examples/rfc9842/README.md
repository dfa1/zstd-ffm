# RFC 9842 (Compression Dictionary Transport) demo

Four standalone JDK single-file programs — no build, no third-party
dependency, only `zstd`/`zstd-rfc9842` and JDK-standard classes
(`com.sun.net.httpserver` for the server, `java.net.http` for the clients,
`java.util.zip` for gzip).

- **`Server.java`** — serves `/dictionary` (with `Use-As-Dictionary`) and
  `/api/data`, negotiated via `Accept-Encoding` with a four-rung ladder, best
  first: `dcz` (zstd + dictionary, RFC 9842) if the request offers a matching
  `Available-Dictionary`/`Dictionary-ID`, else plain `zstd` (RFC 8878) if
  accepted, else `gzip` if accepted, else a plain body.
- **`NaiveClient.java`** — no RFC 9842 awareness. Sends `Accept-Encoding:
  gzip`, the one negotiation nearly every HTTP client does by default, and
  gets the `gzip` tier.
- **`Rfc9842Client.java`** — fetches the dictionary once, offers it on every
  request it applies to, and decodes whatever comes back. Reports what the
  negotiation costs in request-header bytes.
- **`PerfTest.java`** — hits all four tiers, reporting requests/second, the
  latency distribution (p50/p90/p95/p99/max plus a histogram) and bytes
  transferred for each.

## Build

Once, from the repository root:

```bash
./mvnw -q compile
```

## Run

`run.sh` computes the classpath and JVM flags. Start the server in one
terminal:

```bash
docs/examples/rfc9842/run.sh Server                            # defaults
docs/examples/rfc9842/run.sh Server 8192 --dict 4 --level 6    # all three knobs
```

| Server argument | Default | What it does |
|---|---|---|
| *(bare number)* | 2800 | response size in bytes |
| `--dict <KiB>` | 4 | dictionary size cap for `ZstdDictionary.train` |
| `--level <n>` | 3 | zstd compression level for the `zstd` and `dcz` tiers |
| `--quiet` | off | stop logging every request — **required for `PerfTest`** |

Logging each request's and response's headers is a synchronous `System.out`
write on the request path and is the most expensive thing the server does per
request (~8 µs, more than the compression). Leave it on for the interactive
clients, where the headers are the point; pass `--quiet` whenever you measure.

Then, in another terminal:

```bash
docs/examples/rfc9842/run.sh NaiveClient      # gets the gzip tier
docs/examples/rfc9842/run.sh Rfc9842Client    # gets the dcz tier
docs/examples/rfc9842/run.sh PerfTest 8192    # all four tiers; size is a label only
```

<details>
<summary>Running <code>java</code> directly, without <code>run.sh</code></summary>

```bash
CP="$(find . -path '*/target/classes' | tr '\n' ':')"

java --enable-native-access=ALL-UNNAMED --add-modules jdk.httpserver \
     --class-path "$CP" docs/examples/rfc9842/Server.java 8192 --quiet

# in another terminal:
java --class-path "$CP" docs/examples/rfc9842/NaiveClient.java
java --enable-native-access=ALL-UNNAMED --class-path "$CP" \
     docs/examples/rfc9842/Rfc9842Client.java
java --enable-native-access=ALL-UNNAMED --class-path "$CP" \
     docs/examples/rfc9842/PerfTest.java 8192
```

</details>

## Should you use `dcz`?

Short answer: **only on HTTP/2 or HTTP/3, and only if you will size and
retrain the dictionary.** On HTTP/1.1 the negotiation headers cost about as
much as the dictionary saves.

Everything below is measured with these programs on one laptop, averaged over
10,000 responses per cell. The byte columns are deterministic and reproduce
exactly; treat the throughput columns as indicative.

### 1. Size the dictionary to the payload

This is the knob that decides everything, and the one most likely to be left
at a value nobody chose. `dcz` response bytes against the same payload
compressed with plain `zstd`, both at the default `--level 3`:

| payload | plain `zstd` | `--dict 1` | `--dict 4` | `--dict 16` |
|---|---|---|---|---|
| 512 B | 208 B | 108 B (**−48%**) | 108 B (−48%) | 111 B (−47%) |
| 2 KB | 334 B | 274 B (−18%) | 214 B (**−36%**) | 220 B (−34%) |
| 8 KB | 691 B | 811 B (**+17%**) | 621 B (**−10%**) | 634 B (−8%) |
| 32 KB | 2061 B | 2936 B (**+42%**) | 2997 B (+45%) | 1982 B (**−4%**) |

A dictionary much smaller than the payload does not merely under-deliver — it
makes responses **bigger than using no dictionary at all**, by up to 45% here.
A dictionary is not a free win you switch on; it is a sized, trained,
versioned artifact. Sizing it larger than needed is cheap (16 KiB costs the
same latency as 1 KiB), so err upward.

### 2. Count the negotiation headers

`Available-Dictionary` + `Dictionary-ID` + the `, dcz` in `Accept-Encoding`
cost **101 bytes on every request** (as `Rfc9842Client` reports). On HTTP/1.1
that is paid in full, every time. Under HTTP/2 and HTTP/3 these are constant
headers that HPACK/QPACK index to a couple of bytes after the first request.

Net wire bytes per request versus plain `zstd`, best dictionary per size:

| payload | response saving | net on HTTP/1.1 | net on HTTP/2+ |
|---|---|---|---|
| 512 B | 100 B | ±0 (break-even) | **−96 B** |
| 2 KB | 121 B | −20 B | **−117 B** |
| 8 KB | 70 B | **+31 B (worse)** | −66 B |
| 32 KB | 79 B | **+23 B (worse)** | −75 B |

### 3. Verdict

**Use `dcz` when** responses are roughly 0.5–16 KB, you are on HTTP/2 or
HTTP/3, and you can size the dictionary to the payload and retrain it as your
data drifts. That is a 35–48% cut against plain zstd and more against gzip,
for no measurable latency cost — `dcz` and plain `zstd` are within 1–2% of
each other on round-trip time at every size tested.

**Skip it when** any of these hold, because the dictionary lifecycle (fetch,
cache, version, invalidate, keep `Vary` correct) buys nothing:

- **You are stuck on HTTP/1.1.** The 101-byte tax cancels the win at every
  size measured here except a narrow band around 2 KB.
- **Responses are ≥32 KB.** The payload is large enough to be its own
  dictionary; `dcz` lands within a few percent of plain `zstd`.
- **You cannot commit to sizing and retraining.** A mis-sized or stale
  dictionary is worse than no dictionary, not neutral.

## Pick a compression level before reaching for a dictionary

zstd's default level 3 is tuned for speed. At large payloads it ships *more*
bytes than the gzip it is replacing, which no dictionary will fix. 64 KB
responses, `--dict 16`, against `gzip -6` at 2753 B / 2480 req/s:

| `--level` | `zstd` bytes | vs `gzip -6` | req/s |
|---|---|---|---|
| 1 | 3720 B | +35% | 7900 |
| 3 *(default)* | 3545 B | **+29%** | 8280 |
| 6 | 1506 B | **−45%** | 5250 |
| 9 | 1483 B | −46% | 4300 |
| 12 | 1483 B | −46% | 5090 |

Raising the level is a server-CPU-for-bytes trade with **no client cost**:
zstd decompression speed is flat across levels (and slightly *faster* at high
ones), so you pay once per response and every client decodes at the same rate.
Levels above ~12 are not a per-request option — level 19 takes ~21 ms for a
64 KB payload, fine for something compressed once and served a million times,
useless for dynamic content.

Two caveats. This demo's payload is synthetic and unusually regular (cycling
field values, monotonic timestamps), which exaggerates the level 3 → 6 cliff;
the same sweep against random held-out data showed a more modest ~20% gain.
And the level 9/12 tie is an artifact of this data, not a general rule. The
robust finding is the direction: **measure your own level before concluding
zstd beats gzip on size.**

Level and dictionary interact, so tune them together rather than in sequence:
the 8 KB / `--dict 4` cell above is −10% against plain zstd at level 3, but
−25% at `--level 6`.

## Three things RFC 9842 requires that are easy to miss

- **`Vary: accept-encoding, available-dictionary`** on every negotiated
  response (§6.2). Without it a shared cache will hand a dictionary-compressed
  body to a client holding a different dictionary — or none, which is
  undecodable rather than merely suboptimal.
- **`Cache-Control` on the dictionary** (§2.2.1): a stored dictionary only
  matches while it is still fresh, so an uncacheable dictionary costs more to
  refetch than it ever saves.
- **Do not advertise `dcz` without offering a dictionary** (§6.1): a client
  with no matching dictionary "MUST NOT send its dictionary-aware content
  encodings", since it could not decode the response. `Rfc9842Client` makes
  `dcz` conditional on `Use-As-Dictionary`'s `match` pattern for this reason.

## See also

- [`../../../benchmark`](../../../benchmark)'s `DictionaryTransportBenchmark`
  — a JMH microbenchmark isolating codec cost with no HTTP involved, which can
  see the few-microsecond differences a live round trip buries.
- [../../how-to.md](../../how-to.md) — training a dictionary on your own data.
- [../../zero-copy.md](../../zero-copy.md) — the `MemorySegment` path both the
  server's and clients' `dcz` code takes.

This is deliberately not a rigorous benchmark: single connection, single
thread, localhost only, HTTP framing and payload generation mixed into every
measurement, on a laptop with no CPU isolation. Stop the server with Ctrl+C
when done.
