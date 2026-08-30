# zstd-ffm benchmarks

JMH microbenchmarks comparing **zstd-ffm** against the two common JVM zstd
options:

| Contestant | Binding | Modes benchmarked |
|------------|---------|-------------------|
| **zstd-ffm** (this project) | FFM (no JNI) | `byte[]` and zero-copy `MemorySegment` |
| **zstd-jni** (`com.github.luben`) | JNI | `byte[]` |
| **aircompressor** (`io.airlift:aircompressor-v3`) | pure Java | `byte[]` |

The `MemorySegment` path is the one we expect to win: input and output are
already off-heap, so there is no `byte[]` copy in or out of native code.

Two suites, each across payload sizes 1 KiB / 64 KiB / 1 MiB at level 3:

- `CompressBenchmark`
- `DecompressBenchmark`

Plus a multithreading suite (kept separate so the single-threaded numbers stay
longitudinally comparable, and because MT is a no-op below zstd's 512 KiB job
minimum):

- `MultiThreadCompressBenchmark` — 1 MiB / 64 MiB × `nbWorkers` 0 / 2 / 4 at level 3,
  zero-copy `MemorySegment` path only. The 1 MiB legs measure MT overhead on a
  single-job input (no speedup expected by design — at level 3 the default job
  size is ~8 MiB); the 64 MiB legs span multiple jobs and show the scaling.

Payloads (`BenchData`) are deterministic, ~3x-compressible text so the ratios
are realistic rather than all-zeros or random noise.

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
