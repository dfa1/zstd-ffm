# Benchmarks

JMH microbenchmarks comparing **zstd-ffm** against the common JVM zstd options,
in both heap (`byte[]`) and zero-copy (`MemorySegment`) modes. Source lives in
the [`benchmark/`](../benchmark) module; see its
[README](../benchmark/README.md) to reproduce.

| Contestant | Binding | Modes |
|------------|---------|-------|
| **zstd-ffm** (this project) | FFM (no JNI) | `byte[]` and zero-copy `MemorySegment` |
| **zstd-jni** (`com.github.luben`) | JNI | `byte[]` **and** zero-copy direct `ByteBuffer` |
| **aircompressor** (`io.airlift:aircompressor-v3`) | pure Java | caller-buffer `byte[]` |

## TL;DR

- **Best vs best** (our `MemorySegment` vs zstd-jni's own zero-copy direct
  `ByteBuffer`, same zstd 1.5.7 both sides): **allocation is a tie** — both are
  ~0 B/op. The throughput edge is **per-call overhead**: clearest on small,
  call-overhead-dominated payloads (+10–23%) and **converging to a tie** when
  compute or bandwidth dominates (large decompress, 64 MiB). This is the honest
  FFM-vs-JNI shape — biggest where the payload is smallest. See
  [Golden corpus: best vs best](#golden-corpus-best-vs-best).
- **vs the allocating `byte[]` APIs:** the `MemorySegment` path is
  **allocation-free** (flat ~0 B/op at any size) while `byte[]` / JNI-`byte[]`
  allocate ~the output size **every call** (67–79 MB/op at 64 MiB). Real, but it
  compares our zero-copy path against their *heap* API — not their zero-copy one.
  The allocation win is over the convenient API, not over JNI per se.

The honest headline: against zstd-jni's *best* path we **match on allocation and
lead modestly on call overhead**; against the convenient `byte[]` APIs the
zero-copy path additionally eliminates per-op heap allocation.

## Environment

- Apple M5, 32 GB. P-core L2 16 MiB (Apple Silicon: shared SLC, no classic L3).
  The 64 MiB payload is the cache-busting case.
- JDK 25 (Azul). zstd-jni 1.5.7-11 (bundles zstd 1.5.7, matching our build),
  aircompressor-v3 3.6, JMH 1.37.
- Level 3 (zstd default).
- **Golden corpus** run: 3 forks × 3 warmup × 5 measurement, `-prof gc`, error
  bars are 99.9% CIs.
- **Synthetic** tables below: deterministic ~3x-compressible text, and a quick,
  low-iteration run (1 fork, 2 warmup, 3 measurement) — directional, not
  publication-grade; the 64 MiB rows have wide intervals. Rerun with JMH defaults
  before quoting those.

## Golden corpus: best vs best

The fairest comparison: **our best zero-copy path against zstd-jni's best
zero-copy path**, both reusing a context and off-heap buffers, neither allocating
per call — our `MemorySegment` (`compressJavaSegment` / `decompressJavaSegment`)
vs zstd-jni's direct-`ByteBuffer` API (`compressJniByteBuffer` /
`decompressJniByteBuffer`). Inputs are real fixtures from zstd's own
[golden corpus](../third_party/zstd/tests/golden-compression), not synthetic
text, so the small/structured cases exercise per-call boundary overhead — exactly
where FFM-vs-JNI differs. Both sides link **the same zstd 1.5.7**, so any gap is
binding overhead, not codec version.

This run is publication-grade for the cut shown (3 forks × 3 warmup × 5
measurement, `-prof gc`), on the environment below.

### Throughput (ops/ms, higher is better)

| file (size) | JavaSegment | JniByteBuffer | edge |
|-------------|------------:|--------------:|-----:|
| compress `http` (1.2 KiB) | **353.6** ±3.0 | 322.1 ±22.9 | +9.8% |
| compress `large-literal` (200 KiB) | **46.1** ±1.4 | 42.2 ±0.3 | +9.4% |
| decompress `http` | **922.7** ±5.9 | 750.8 ±0.9 | +22.9% |
| decompress `large-literal` | 56.1 ±0.7 | 55.6 ±0.4 | +0.9% (tie) |

### Allocation (`gc.alloc.rate.norm`, B/op)

| | JavaSegment | JniByteBuffer |
|-|------------:|--------------:|
| every case | ~0.00 | ~0.00 |

**Reading it:** we lead ~+9–10% on compress and +23% on small decompress (the
call-overhead-dominated cases), tie on large decompress (bandwidth-bound), and
**match exactly on allocation** — both genuinely zero-copy. Being
"allocation-free vs JNI" holds only against JNI's `byte[]` API; against its
zero-copy path the allocation advantage is gone, and the speed edge is the
expected FFM call-overhead margin, largest at the smallest payloads.

## Throughput (ops/ms, higher is better)

> The tables below use the **synthetic** payloads and compare against
> zstd-jni's *allocating* `byte[]` API (`zstdJni`), not its zero-copy path. They
> show the `MemorySegment`-vs-`byte[]` allocation story; for the fair
> zero-copy-vs-zero-copy comparison see
> [Golden corpus: best vs best](#golden-corpus-best-vs-best) above.

### Compress

| size | zstdJavaSegment | zstdJavaBytes | zstdJni | aircompressor |
|------|----------------:|--------------:|--------:|--------------:|
| 1 KiB | **287.7** | 278.2 | 238.3 | 148.3 |
| 64 KiB | **14.34** | 13.60 | 13.46 | 8.00 |
| 1 MiB | **0.927** | 0.889 | 0.906 | 0.562 |
| 64 MiB | 0.014 | 0.014 | 0.013 | 0.007 |

### Decompress

| size | zstdJavaSegment | zstdJavaBytes | zstdJni | aircompressor |
|------|----------------:|--------------:|--------:|--------------:|
| 1 KiB | **590.7** | 560.1 | 460.6 | 477.0 |
| 64 KiB | **32.3** | 29.7 | 26.6 | 26.8 |
| 1 MiB | **2.15** | 2.02 | 1.82 | 1.74 |
| 64 MiB | 0.029 | 0.029 | 0.028 | 0.016 |

At 64 MiB the segment/byte[]/jni columns are statistically indistinguishable —
all bound by memory bandwidth, not API overhead.

## Allocation (`gc.alloc.rate.norm`, bytes/op, lower is better)

The decisive chart. Note the segment column is flat near zero while the others
scale linearly with payload size.

### Compress

| size | zstdJavaSegment | zstdJavaBytes | zstdJni | aircompressor |
|------|----------------:|--------------:|--------:|--------------:|
| 1 KiB | **0.07** | 408 | 1,464 | 35,464 |
| 64 KiB | **6.8** | 11,694 | 77,473 | 724,034 |
| 1 MiB | **44** | 182,069 | 1,234,501 | 1,449,282 |
| 64 MiB | **1,296** | 11,621,915 | 78,992,794 | 2,971,072 |

### Decompress

| size | zstdJavaSegment | zstdJavaBytes | zstdJni | aircompressor |
|------|----------------:|--------------:|--------:|--------------:|
| 1 KiB | **0.03** | 1,136 | 1,072 | 48 |
| 64 KiB | **13** | 65,651 | 65,585 | 49 |
| 1 MiB | **16** | 1,048,785 | 1,048,634 | 58 |
| 64 MiB | **1,969** | 67,111,319 | 67,109,554 | 4,799 |

### Notes

- **Segment is allocation-free both directions** — the caller owns the off-heap
  source and destination, so nothing touches the heap.
- **byte[]/JNI allocate ~the output size every call** (their APIs return a fresh
  array). At 64 MiB that is 11-67 MB/op.
- **JNI compress allocates ~79 MB/op at 64 MiB** (~7x the others): its
  `compress(byte[], level)` allocates a worst-case `compressBound` buffer and then
  a trimmed copy — double buffering.
- **aircompressor decompress is also ~0 B/op** here, because the benchmark reuses
  a caller-supplied destination array. A caller-buffer API kills decode allocation
  regardless of binding; the segment path additionally avoids the off-heap↔heap copy.
  aircompressor *compress* still allocates internal hash tables per call.

## Where the work goes (async-profiler, 64 MiB)

Profiling the cache-busting 64 MiB case with async-profiler corroborates the
allocation counters from two angles — allocation flamegraph (what hits the heap)
and itimer/CPU flamegraph (what burns cycles).

**Allocation flamegraph** — dominant heap-allocation site per benchmark:

| benchmark | dominant alloc site |
|-----------|---------------------|
| Compress `zstdJavaSegment` | **none — no heap allocation sampled** |
| Compress `zstdJavaBytes` | `byte[]` in `Zstd.copyOut` (the returned frame array) |
| Compress `zstdJni` | `byte[]` inside `luben…Zstd.compress` |
| Compress `aircompressor` | `byte[]` + `BlockCompressionState.<init>` (internal tables) |
| Decompress `zstdJavaSegment` | **none — no heap allocation sampled** |
| Decompress `zstdJavaBytes` / `zstdJni` | `byte[]` (the returned output array) |

**CPU (itimer) flamegraph** — the heap paths additionally pay a memcpy bounce and
GC work that the segment path does not:

- `zstdJavaBytes`: `MemorySegment.copy` → `ScopedMemoryAccess.copyMemory` →
  `Unsafe.copyMemory` (heap↔native in/out), **plus** `G1ParCopyClosure::do_oop_work`
  (GC triggered by the output allocation).
- `zstdJni`: `byte_disjoint_arraycopy` + `Arrays.copyOfRange` (the JNI copy and the
  trim copy).
- `zstdJavaSegment`: **neither** — no copy frames, no GC frames. Only codec work
  (`ZSTD_*`, `FSE_buildCTable_wksp`, `encodeSequences`, `FSE_readNCount`).

So the segment API does strictly less work: no per-op heap allocation (hence no GC)
and no memcpy bounce. At 64 MiB this overhead is a small fraction of total codec
time — which is why throughput ties — but it is pure waste the zero-copy path
removes entirely, and it dominates under sustained, allocation-sensitive load.

## Large files: mmap vs. streaming

The "no 2 GiB cap" line in the [explanation](explanation.md#secondary-wins) is a **capability** claim, and it is unconditional:
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
`WILLNEED` hint's payoff **flips sign with size**:

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
  *slowest* (~4% behind zstd-jni, ranges separated from both): mmap loses
  ground at very large scale unless hinted, but only by **4–5%**.

The headline is the magnitude: past 4 MiB, the throughput differences between
the three paths are a few percent, not multiples. Single timing samples
(`System.nanoTime` loops with no fork isolation) overstate effects like these
by an order of magnitude, which is why every number here comes from
[`Mode#SingleShotTime`](https://javadoc.io/doc/org.openjdk.jmh/jmh-core/latest/org/openjdk/jmh/annotations/Mode.html)
with warmup, repeated measurement, and a dedicated fork.

Where the two approaches do differ clearly is **allocation**, which backs the
"[zero GC](explanation.md#secondary-wins)" claim with numbers: at every
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
one — it is not tested directly (e.g. by isolating I/O with compression
removed), so treat it as the shape the data suggests, not a settled
explanation.

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
weak **on this single-machine, `n=3` data** — a few percent either way — so `ZstdOutputStream`'s
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

```bash
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

```bash
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

## Reproduce

```bash
./mvnw -q -pl benchmark -am package -DskipTests

# golden corpus, best vs best (our MemorySegment vs zstd-jni direct ByteBuffer)
java --enable-native-access=ALL-UNNAMED -jar benchmark/target/benchmarks.jar \
  "GoldenCorpusBenchmark.*(Segment|JniByteBuffer)" \
  -p file=http,large-literal-and-match-lengths -f 3 -wi 3 -i 5 -prof gc

# synthetic throughput + allocation, all sizes
java -jar benchmark/target/benchmarks.jar CompressBenchmark DecompressBenchmark -prof gc

# single size
java -jar benchmark/target/benchmarks.jar -prof gc -p size=67108864

# async-profiler flamegraphs for the 64 MiB case (macOS: itimer; Linux: cpu)
LIB=/opt/homebrew/lib/libasyncProfiler.dylib
java -jar benchmark/target/benchmarks.jar -p size=67108864 \
  -prof "async:libPath=$LIB;output=flamegraph;event=alloc;dir=benchmark/target/async-alloc"
java -jar benchmark/target/benchmarks.jar -p size=67108864 \
  -prof "async:libPath=$LIB;output=flamegraph;event=itimer;dir=benchmark/target/async-cpu"
```
