# Zero-copy with `MemorySegment`

zstd-ffm exposes two shapes of API:

- **`byte[]`** — convenient, for callers whose data is already on the heap.
- **`MemorySegment`** — zero-copy *at the call boundary*, for callers whose data
  is already off-heap.

This note explains why the segment shape exists and when it pays off. For the
recipes — sizing output, letting the codec allocate, `ByteBuffer` interop,
streaming, and pledging the size — see the [how-to guide](how-to.md).

## What "zero-copy" means here

It means **no copy at the Java↔native boundary** — the same sense as zero-copy
I/O, where bytes still move but not redundantly between buffers. Compression
itself always reads all input and writes all output; that is the work, not a
copy. "Zero-copy" is about the *boundary*, and applies only to the
`MemorySegment` path — the `byte[]` overloads copy twice (see the honest caveat).

## The core win: no copy at the call boundary

FFM downcalls need a *stable* native pointer. A heap `byte[]` can be relocated by
the GC, so the FFM runtime copies it into native memory for the duration of the
call — and copies the result back. **Two copies per call.**

A native `MemorySegment` already *is* a native address. You hand
`ZSTD_compress` / `ZSTD_decompress` the pointer directly. **No boundary copy.**

```text
byte[] path:   heap byte[] ──copy──▶ native scratch ──ZSTD──▶ native scratch ──copy──▶ heap byte[]
segment path:  native src ───────────────────────────ZSTD──▶ native dst        (no boundary copy)
```

## When it actually pays off (not always)

This only helps if the data is **already native** on both ends. The canonical
case is a memory-mapped reader (e.g. Vortex):

- **Compressed input** — the reader `mmap`s the file into one `MemorySegment`;
  the zstd frame is already a slice of it. A `byte[]` API forces
  `frame.toArray()` → `new byte[]` just to make the call. The segment API passes
  the mmap slice straight to `ZSTD_decompress`.
- **Decompressed output** — allocate the output in your arena
  (`arena.allocate(n)`) and let `ZSTD_decompress` write directly into it. That
  segment becomes the materialized backing buffer as-is — no temp `byte[]`, no
  `MemorySegment.copy`.

The decode path collapses from **mmap → byte[] → byte[] → arena** (three copies)
to **mmap-slice → arena** (no boundary copy).

## Secondary wins

- **Zero GC** — off-heap, no allocation churn in a scan hot loop.
- **No 2 GiB cap** — `byte[]` maxes at `Integer.MAX_VALUE`; segments are
  `long`-indexed.
- **Lifetime safety** — bounds-checked, tied to a confined `Arena`; the same
  ownership model as the rest of an FFM reader, cleaner than raw pointers.
- **Typed reads** — read `JAVA_LONG` / `JAVA_DOUBLE` straight off the
  decompressed segment with no re-wrap.

## The 2 GiB cap: capability vs. throughput

The "no 2 GiB cap" line above is a **capability** claim, and it is unconditional:
`FileChannel.map(mode, pos, size, Arena)` is `long`-indexed and maps a file of
any size in one call, on every OS this library supports. The reference
`zstd-jni` binding cannot do this at all — its entire zero-copy surface is
`java.nio.ByteBuffer`-based, and the classic 3-arg `FileChannel.map(mode, pos,
size)` it depends on throws `IllegalArgumentException` past
`Integer.MAX_VALUE`. There is no workaround inside zstd-jni; the caller would
have to hand-roll mapping the file in sub-2 GiB windows. This is proven by a
runnable test — see [below](#proof-largememorymappedfiletest).

Whether that mapping is also **faster** than zstd-jni's fallback (a classic
`ZstdOutputStream` over a buffered `FileInputStream`, copying through a heap
`byte[]`) is a separate question. Unlike the capability claim, this one needs
a real measurement — see [`LargeFileBenchmark`](#proof-largefilebenchmark),
a JMH benchmark (not a timing loop) comparing three paths at zstd's default
level (3), single-threaded, on the same synthetic, ~3x-compressible file:
this library's `mmap` + `MemorySegment` path, that same path with a
`posix_madvise(WILLNEED)` readahead hint right after mapping, and zstd-jni's
classic stream.

| size | mmap (no hint) | mmap + `WILLNEED` | zstd-jni |
|---|---|---|---|
| 4 MiB | 5.2 ms `[4.8–5.7]` | 5.2 ms `[4.7–5.8]` | 10.0 ms `[8.3–12.0]` |
| 64 MiB | 81.8 ms `[81.4–82.5]` | 76.8 ms `[76.6–77.1]` | 81.7 ms `[80.6–83.5]` |
| 2.25 GiB | 2843 ms `[2840–2845]` | 2875 ms `[2872–2880]` | 2895 ms `[2887–2906]` |
| 4 GiB | 5042 ms `[5022–5056]` | 5124 ms `[5120–5130]` | 5160 ms `[5140–5173]` |
| 10 GiB | 13631 ms `[13605–13673]` | 12897 ms `[12880–12914]` | 13094 ms `[13027–13153]` |

(mean `[min–max]` across `n=3` measurement iterations after 1 warmup, single
fork — see [reproduce](#reproduce-it) for what that buys and doesn't. JMH also
reports a 99.9% confidence interval per cell; at `n=3` — 2 degrees of freedom —
that interval is wide enough to make every row above look like a tie on paper,
even where the raw min–max ranges below don't overlap at all. The ranges are
the more honest read of this data, so that's what's tabulated.)

Reading the ranges size by size, a real (if modest) pattern emerges — the
`WILLNEED` hint's payoff **flips sign with size**, not something the earlier
ad hoc numbers showed:

- **4 MiB**: both mmap variants clearly beat zstd-jni (ranges don't overlap —
  every mmap iteration faster than every zstd-jni one), by close to 2x. The
  hint makes no difference between the two mmap variants — expected, since a
  4 MiB file just written is already fully page-cached, so there's no
  readahead to hint about. This is **fixed per-call overhead**, not
  throughput: opening a JNI stream and its buffers costs more than an FFM
  downcall plus an `mmap()` syscall, and at 4 MiB that fixed cost is most of
  the total.
- **64 MiB**: `WILLNEED` is the clear winner here (ranges separated from both
  others, ~6% faster than zstd-jni); unadvised mmap and zstd-jni are
  genuinely tied (ranges overlap: 81.4–82.5 vs. 80.6–83.5).
- **2.25 GiB and 4 GiB**: unadvised mmap is fastest at both (ranges separated
  from both others, ~2% ahead of zstd-jni); `WILLNEED` is *slower than
  unadvised mmap* at these two sizes, though still ahead of zstd-jni by a
  smaller margin (~0.7%). The hint has a real cost — the `posix_madvise` call
  itself, plus whatever readahead it triggers — that isn't worth paying yet
  at these sizes, where compression compute still dominates over I/O.
- **10 GiB**: the pattern flips. `WILLNEED` becomes the fastest of the three
  (~1.5% ahead of zstd-jni, ranges separated). Unadvised mmap becomes the
  *slowest* (~4% behind zstd-jni, ranges separated from both). This is the
  same direction as the original ad hoc finding — mmap loses ground at very
  large scale unless hinted — just far smaller in magnitude: **4–5% swings
  here, not the 24–45% the ad hoc timing loops reported**.

That correction in magnitude is the headline finding, not the direction: the
previous version of this page, measured with `System.nanoTime` timing loops
(no fork isolation, one sample per cell), reported mmap beating zstd-jni by
~23% at 2.25 GiB on macOS and losing to it by ~45% at 10 GiB without the
`madvise` hint. This JMH data confirms the *shape* of that story (mmap ahead
at medium sizes, behind at 10 GiB unless hinted) but not its *size* — the real
effect is an order of magnitude smaller than the ad hoc measurement suggested.
A single untreated timing sample is exactly what
[`Mode#SingleShotTime`](https://javadoc.io/doc/org.openjdk.jmh/jmh-core/latest/org/openjdk/jmh/annotations/Mode.html)
with warmup, repeated measurement, and a dedicated fork exists to correct for,
and this page no longer carries a claim it doesn't back with that.

Where the two approaches do differ clearly is **allocation**, confirming the
"[zero GC](#secondary-wins)" claim quantitatively for the first time: at every
size, `-prof gc` shows the mmap paths allocating on the order of 10–60 KB per
run (JVM/JMH/Arena bookkeeping, not data), while zstd-jni's stream path
allocates a constant ~8.1 MB per run regardless of file size — the reused
read buffer and `BufferedInputStream`'s internal buffer, once per invocation.
Neither figure scales with file size, since both reuse their buffers across
the read loop, but the mmap path's is roughly two orders of magnitude smaller.

### The `madvise` hint

`FileChannel.map()` — both the `ByteBuffer` and `MemorySegment` overloads —
just calls `mmap()`/`MapViewOfFile` under the hood and nothing else. It never
tells the OS how the mapping will be accessed, so the kernel falls back to its
default readahead heuristic. For a large, purely-sequential scan (exactly what
a one-shot compress of an mmap'd file is), telling it explicitly —
`posix_madvise(addr, len, POSIX_MADV_WILLNEED)`, a raw FFM downcall to libc
the benchmark makes directly, the same mechanism this library already uses
for `libzstd` itself — turns out to help at some sizes and hurt at others,
per the ranges in the table above: a clear win at 64 MiB and at 10 GiB (~6%
and ~1.5–5% respectively, depending whether you compare it to unadvised mmap
or to zstd-jni), but a small, equally real *loss* relative to unadvised mmap
at 2.25 GiB and 4 GiB. A plausible read: the `madvise` syscall and whatever
readahead it triggers cost something up front, which pays for itself once I/O
is enough of the total to matter (small files, where every syscall counts
proportionally more; or 10 GiB, where the OS's default readahead heuristic
falls behind) but not in between, where compute still dominates and the hint
is close to pure overhead. This is a plausible mechanism, not a confirmed
one — it wasn't tested directly (e.g. isolating I/O with compression removed,
the way the very first version of this investigation did informally), so
treat it as the shape the data suggests, not a settled explanation.

A prior ad hoc (non-JMH) pass on this same machine had suggested a much
larger effect at 10 GiB specifically (mmap losing to zstd-jni by ~45%
unadvised, beating it by ~24% advised); this benchmark reproduces the
*direction* but at roughly a tenth the magnitude, which is exactly why it
replaced the ad hoc numbers.

This remains untested on Linux (same POSIX API, plausible either way) and has
no direct equivalent on Windows (the closest primitive,
`PrefetchVirtualMemory`, is structurally different and untried). This library
does not currently call `madvise` anywhere; doing so — and deciding whether it
belongs as an internal default, an opt-in flag, or just a documented recipe
for callers — is open follow-up work, not a shipped feature. Given the hint
helps at some sizes and costs at others on this data, "always call it" isn't
obviously the right default even if it were implemented.

### Is the extra code worth it?

The zero-copy path is real code, not a one-liner: [`LargeFileBenchmark`'s
mmap path](../benchmark/src/main/java/io/github/dfa1/zstd/bench/LargeFileBenchmark.java)
— open two `FileChannel`s, map the source, optionally advise it, allocate a
native destination buffer, wrap it in a `ByteBuffer` view, and drive
`ZstdCompressStream` in a loop draining that view into the output channel —
is roughly 4x the line count of the equivalent one-shot call through this
library's own [`ZstdOutputStream`](how-to.md) over `Files.newInputStream`/
`newOutputStream`. That is the real trade-off, independent of zstd-jni: more
code, more to get wrong (buffer sizing, loop termination, resource cleanup
across four `try`-with-resources, getting the `madvise` hint's sign right for
your size), for a throughput edge that — per the data above — is decisive
only at small files (4 MiB), and elsewhere tops out at single-digit
percentages that depend on file size and whether you bothered with the hint.

What doesn't shrink is the **capability** claim: past 2 GiB, `ZstdOutputStream`
over a classic `InputStream` still works today (zstd-jni's own fallback proves
that path scales fine), but zstd-jni's zero-copy `ByteBuffer` surface simply
cannot address the file at all, with no in-library workaround. So the honest
recommendation is size-dependent: for small files, or when you're already
holding native memory (an existing arena, an mmap'd reader), the segment path
pays for its complexity outright. For a one-shot large-file compress where you
don't otherwise need off-heap data, the throughput case for the extra code is
weak **on this single-machine, `n=3` data** — a few percent either way, not the
multiples the earlier ad hoc numbers implied — so `ZstdOutputStream`'s
simplicity is a reasonable default *for this data set*, not a settled
conclusion, unless you've measured your own workload and size and found
otherwise. The segment path's unconditional win is addressing past the 2 GiB
`ByteBuffer`/`byte[]` boundary at all, which `ZstdOutputStream` doesn't need to
do since it never holds more than a chunk in memory at once.

### Proof: `LargeMemoryMappedFileTest`

`integration-tests/.../LargeMemoryMappedFileTest` is a runnable, disabled-by-
default test that proves the *capability* claim: it writes a file just over
the `Integer.MAX_VALUE`-byte boundary, shows the classic `ByteBuffer` mapping
call rejects it, shows the FFM `MemorySegment` mapping call succeeds and
addresses bytes past the boundary correctly, and round-trips the whole file
through this library's zero-copy streaming compressor — plus a companion test
showing zstd-jni's stream API can still compress a file this large (just not
zero-copy map it) and that the frame it produces decodes correctly through
this library. It is gated behind a system property, since it writes several
gigabytes of real files to disk and is too slow/disk-hungry for a plain `mvn
test`/`mvn verify` or CI:

```
./mvnw -pl integration-tests test -Dzstd.test.large=true -Dtest=LargeMemoryMappedFileTest
```

### Proof: `LargeFileBenchmark`

[`benchmark/.../LargeFileBenchmark`](../benchmark/src/main/java/io/github/dfa1/zstd/bench/LargeFileBenchmark.java)
is the JMH benchmark behind the throughput table above — `Mode.SingleShotTime`
(each `@Benchmark` invocation is one full-file compress, not a tight
throughput loop), one fork, one warmup iteration, three measurement
iterations, at `size ∈ {4 MiB, 64 MiB, 2.25 GiB, 4 GiB, 10 GiB}`. Like
`LargeMemoryMappedFileTest`, it writes real multi-gigabyte files to a temp
directory and is not part of `mvn test`/`mvn verify` or CI.

#### Reproduce it

```
./mvnw -pl benchmark -am package -DskipTests
java -jar benchmark/target/benchmarks.jar LargeFileBenchmark -prof gc
```

The numbers above are from one run on one machine (Apple M5, 10 cores, 32 GiB
RAM, macOS 26.5.2, internal SSD, Zulu JDK 25.0.2) — `n=3` per size, not the
larger sample a load-bearing perf claim would usually want, because a proper
sweep at the 10 GiB end of this benchmark is itself a multi-hundred-gigabyte,
many-minute exercise per run. Treat the "statistical tie" calls above as
"no effect was distinguishable in this data," not "there is provably no
effect" — if throughput at your specific size and OS matters for a decision,
run it yourself, ideally with more iterations at the sizes you care about;
the reproduce command above is the whole ask. Only macOS has been measured;
Linux and Windows are open follow-up (same caveat as the `madvise` hint).

## Honest caveat

If the caller hands you a heap `byte[]` (the aircompressor fallback path, or
external input), wrapping it with `MemorySegment.ofArray(...)` still triggers the
copy for the downcall — no free lunch. A heap `ByteBuffer` is the same: its
`MemorySegment.ofBuffer(...)` wrap is a heap segment and still copies. Only data
that is *already native* avoids the boundary copy. So the API is **segment-first
for the zero-copy fast path, with a thin `byte[]` overload** for the rare heap
caller.

We deliberately do **not** add a parallel `ByteBuffer` API surface: FFM already
defines the conversions (`MemorySegment.ofBuffer` in, `segment.asByteBuffer()`
out), so a direct buffer reaches the same path with one wrapping call — see the
[how-to](how-to.md).

## Why a streamed frame can't be decoded zero-copy

The zero-copy decode path reads the frame's **decompressed-size** header field to
size the output arena in one shot. zstd writes that field only when the encoder
knows the total up front — trivially true for one-shot `ZSTD_compress`, but a
*streaming* encoder is fed incrementally and closes the frame without ever being
told the total. So a plain `ZstdOutputStream` frame omits the size, and a
consumer is forced back onto the bounded streaming decoder (allocate, decode a
chunk, grow, repeat) — the very heap-bounce the segment API exists to avoid.

The fix is to **pledge the size** before the first byte, which stamps the content
size into the header and lets a downstream reader size the arena exactly. This is
not a micro-optimization but a correctness gate: it is the difference between a
frame that participates in the zero-copy decode path and one that does not. The
recipe is in the [how-to](how-to.md#pledge-the-size-so-a-streamed-frame-decodes-in-one-shot).
