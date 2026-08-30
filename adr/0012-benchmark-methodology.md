# ADR 0012: Benchmark methodology and publishing

- **Status:** Proposed
- **Date:** 2026-06-27
- **Deciders:** project maintainer

## Context

Claims like "faster than JNI" need reproducible, honest measurement. Two failure
modes to avoid: cherry-picked numbers, and synthetic payloads that flatter or
punish a codec unfairly.

## Decision

Benchmarks live in the `benchmark/` module (JMH), comparing zstd-ffm
(`byte[]` and `MemorySegment`) against `zstd-jni` (JNI) and aircompressor
(pure Java), on both throughput and allocation (`gc.alloc.rate.norm`).

Two payload families:

- **Synthetic, semi-compressible** (`BenchData`, ~3x ratio) parameterized over
  *sizes* — exercises the bandwidth regime.
- **The vendored golden corpus** (`GoldenCorpusBenchmark`) parameterized over
  *real files* — exercises per-call native-boundary overhead, where FFM-vs-JNI
  fixed costs show up.

Published numbers in `docs/benchmarks.md` state the host, JDK, dependency
versions, and the JMH run parameters. Quick low-iteration runs are labeled
**directional, not publication-grade**; publication numbers use higher fork and
iteration counts.

## Consequences

### Positive
- Reproducible, self-describing results; allocation reported alongside speed
  (the real zero-copy win).
- Real-corpus benchmark catches what synthetic sizes miss.

### Negative
- Full publication runs are slow (multiple forks × iterations × files).

### Risks to manage
- Numbers are host-specific; always re-run on the target host before quoting.

## Alternatives considered

- **`System.nanoTime` micro-timing:** JIT/warmup artefacts; JMH exists to avoid
  this.
- **Synthetic payloads only:** miss the boundary-overhead regime.

## References

- [docs/benchmarks.md](../docs/benchmarks.md), [benchmark/](../benchmark)
