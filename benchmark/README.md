# zstd-ffm benchmarks

JMH microbenchmarks comparing **zstd-ffm** against the two common JVM zstd
options:

| Contestant | Binding | Paths benchmarked |
|------------|---------|-------------------|
| **zstd-ffm** (this project) | FFM (no JNI) | `byte[]`, zero-copy `MemorySegment`, `ZstdOutputStream`, and the `mmap` + segment streaming path |
| **zstd-jni** (`com.github.luben`) | JNI | `byte[]`, direct `ByteBuffer` (`compressDirectByteBuffer` / `decompressDirectByteBuffer`), and its `ZstdOutputStream` |
| **aircompressor** (`io.airlift:aircompressor-v3`) | pure Java | `byte[]` |

The `MemorySegment` path is the one we expect to win: input and output are
already off-heap, so there is no `byte[]` copy in or out of native code.

## Suites

| Suite | What it measures | Parameters |
|---|---|---|
| `CompressBenchmark` / `DecompressBenchmark` | one-shot throughput on synthetic payloads: `byte[]` and `MemorySegment`, each with a reused and a fresh context, vs zstd-jni `byte[]` and aircompressor | `size` 1 KiB / 64 KiB / 1 MiB / 64 MiB, level 3 |
| `MultiThreadCompressBenchmark` | multithreaded compression, zero-copy `MemorySegment` path only | 1 MiB / 64 MiB × `nbWorkers` 0 / 2 / 4, level 3 |
| `GoldenCorpusBenchmark` | per-call overhead on zstd's own vendored golden corpus (`third_party/zstd/tests/golden-compression`, 143 B to 256 KiB): `byte[]` and `MemorySegment` vs zstd-jni `byte[]` and direct `ByteBuffer` | one `file` per corpus entry, level 3 |
| `DictionaryTransportBenchmark` | gzip vs plain zstd vs zstd with a pre-digested trained dictionary (the RFC 9842 `dcz` codec), compress and decompress, in process (no HTTP) | `size` 512 B to 64 KiB |
| `LargeFileBenchmark` | whole-file compression (`Mode.SingleShotTime`): `mmap` + `MemorySegment` with and without a `posix_madvise(WILLNEED)` hint vs this library's `ZstdOutputStream` vs zstd-jni's `ZstdOutputStream`, both over a buffered `FileInputStream` | `size` 4 MiB / 64 MiB / 2.25 GiB / 4 GiB / 10 GiB, level 3 |

The multithreading suite is kept separate so the single-threaded numbers stay
longitudinally comparable, and because MT is a no-op below zstd's 512 KiB job
minimum. Its 1 MiB legs measure MT overhead on a single-job input (no speedup
expected by design — at level 3 the default job size is ~8 MiB); the 64 MiB
legs span multiple jobs and show the scaling.

`LargeFileBenchmark` writes real multi-gigabyte files to the system temp
directory (cached per size and reused across runs) and takes many minutes; run
it deliberately, not as part of a quick pass.

Synthetic payloads (`BenchData`) are deterministic, ~3x-compressible text so the
ratios are realistic rather than all-zeros or random noise.

## Build

```bash
./mvnw -q -pl benchmark -am package -DskipTests
```

Produces a self-contained `benchmark/target/benchmarks.jar`. The host's native
`libzstd` JAR is pulled in automatically by the platform profile.

## Run

```bash
# everything (full warmup/measurement — takes a few minutes)
java -jar benchmark/target/benchmarks.jar

# one suite, one size
java -jar benchmark/target/benchmarks.jar CompressBenchmark -p size=1048576

# quick smoke run
java -jar benchmark/target/benchmarks.jar -f 1 -wi 1 -i 3 -p size=65536
```

`--enable-native-access=ALL-UNNAMED` is applied to forked JVMs via `@Fork`, so
no extra flags are needed.

## Reading results

Throughput is `ops/ms` (higher is better). Compare rows at the same `(size)`:

```
Benchmark                            (size)   Mode  Cnt   Score   Units
CompressBenchmark.zstdJavaSegment     65536  thrpt    5  ...      ops/ms
CompressBenchmark.zstdJavaBytes       65536  thrpt    5  ...      ops/ms
CompressBenchmark.zstdJni             65536  thrpt    5  ...      ops/ms
CompressBenchmark.aircompressor       65536  thrpt    5  ...      ops/ms
```

Microbenchmark numbers are machine-specific; rebuild and run on the target host.
Heed JMH's own caveat printed after each run.
