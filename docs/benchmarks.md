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
**match exactly on allocation** — both genuinely zero-copy. The earlier
"allocation-free vs JNI" claim only held against JNI's `byte[]` API; against its
zero-copy path the allocation advantage is gone, and the speed edge is the
expected FFM call-overhead margin, largest at the smallest payloads.

## Throughput (ops/ms, higher is better)

> The tables below use the original **synthetic** payloads and compare against
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
