# ADR 0015: Enable ZSTD_MULTITHREAD (native `nbWorkers`)

- **Status:** Accepted
- **Date:** 2026-07-18
- **Deciders:** project maintainer
- **Supersedes:** [ADR 0014](0014-single-threaded-native-build.md)

## Context

ADR 0014 kept `ZSTD_MULTITHREAD` off on the grounds that pthread/winpthreads
would break the hermetic `zig cc` cross-build (ADR 0002). Issue #80 asked for a
decision on the merits, and investigation showed the premise does not hold:

- **Unix targets:** zig bundles pthreads with its libc for every target —
  that is the point of zig's hermetic toolchain. Nothing extra to bundle.
- **Windows:** with `ZSTD_MULTITHREAD` defined, zstd's `threading.{h,c}` wraps
  **native Win32 primitives** (`_beginthreadex`, `CRITICAL_SECTION`,
  `CONDITION_VARIABLE`) — winpthreads is never involved.
- All MT sources (`pool.c`, `threading.c`, `zstdmt_compress.c`) were already
  in the compiled source set as `#ifdef` no-ops; enabling is one macro.

Meanwhile the cost of staying single-threaded was real: `ZstdCompressParameter`
exposes `NB_WORKERS`, so callers could set it and silently get nothing — a
public-API lie — and zstd-jni ships MT-enabled by default.

## Decision

Compile every classifier with `-DZSTD_MULTITHREAD=1`. No new Java API: the
existing `ZstdCompressContext.parameter(NB_WORKERS, n)` path (plus `JOB_SIZE`
and `OVERLAP_LOG`) is the interface.

**No explicit `-pthread`/`-lpthread` flag is added** — zig resolves the
pthread references itself. Verified on the built artifacts:

- **Linux (both arches):** `pthread_*` stay undefined with pre-2.34 symbol
  versions (`GLIBC_2.2.5`/`GLIBC_2.3.2`), and zig records
  `DT_NEEDED libpthread.so.0`. That soname exists on every glibc distro back
  to RHEL 8 / Ubuntu 20.04 / Amazon Linux 2, so the glibc floor is unchanged.
  The musl smoke legs are unaffected: they are expected-fail until musl
  natives ship, independent of pthread linkage. Do not "fix" the `DT_NEEDED`
  entry by raising the glibc target to 2.34+ (where pthreads merged into
  libc) — that would drop the older-glibc distros.
- **macOS:** `pthread_*` bind to `libSystem`, no new load command.
- **Windows:** DLL imports stay UCRT api-sets + `KERNEL32` only — no
  `libwinpthread-1.dll`, confirming the native Win32 threading path.

### The lifecycle constraint (load-bearing)

`ZSTD_CCtx_reset()` — with **any** directive, including
`ZSTD_reset_session_and_parameters` — never frees the lazily-created
`cctx->mtctx` worker pool; only `ZSTD_freeCCtx` does (zstd
`zstd_compress.c`: `ZSTD_freeCCtxContent` vs `ZSTD_CCtx_reset`). A context
that has ever compressed with `nbWorkers > 0` therefore carries N live OS
threads — invisible in JVM thread dumps — plus large job buffers (tens of MB)
until `close()`.

Consequently the (still proposed) context pool of ADR 0010 **must ban
`NB_WORKERS` on pooled contexts**: one tainted borrower would silently saddle
every future borrower with a native thread pool, defeating the pool's own
resource-budget rationale. ADR 0010 is amended accordingly. Multithreaded
compression is only for dedicated, caller-owned contexts —
`ZstdMultithreadTest.workerPoolSurvivesReset()` pins this behavior.

## Consequences

### Positive
- `NB_WORKERS` does what it says; large-payload compression can parallelize.
- Parity with zstd-jni's default build; MT interop covered by tests.
- Binary growth is small (~40-60 KB per library).

### Negative
- MT output is a valid frame but **not byte-identical** to single-threaded
  output — no test or fixture may byte-compare MT frames.
- MT silently disengages at or below zstd's 512 KiB job-size minimum
  (`ZSTDMT_JOBSIZE_MIN`) for one-shot compression; small inputs stay
  single-threaded no matter what the caller sets.
- An MT context holds worker threads and job buffers until closed (see the
  lifecycle constraint above).
- Out-of-range `NB_WORKERS` values are clamped into bounds by zstd (like
  `COMPRESSION_LEVEL`), not rejected.

### Risks to manage
- PR CI compiles all six classifiers but executes tests only on linux-x86_64;
  thread spawning on Windows/macOS/musl is proven by the release-smoke matrix
  (`Smoke.multiThreadRoundTrip`), not by PR CI.
- ~~Streaming classes (`ZstdCompressStream`, `ZstdOutputStream`) have no
  parameter setter, so MT is one-shot-only today; tracked as a follow-up.~~
  Resolved by [issue #82](https://github.com/dfa1/zstd-ffm/issues/82): both
  classes now expose `parameter(ZstdCompressParameter, int)`, set once right
  after construction and before the first `compress`/`write` call — the same
  lifecycle constraint above applies, since the streams already own their
  context exclusively for their lifetime and free it on `close()`.

## Alternatives considered

- **Stay single-threaded:** rejected — `NB_WORKERS` in the public API as a
  silent no-op is a trap, and the original technical blocker was factually
  wrong.
- **Separate MT and ST artifacts:** rejected — doubles the classifier matrix
  for a flag that is off by default at runtime anyway (`nbWorkers = 0`).

## References

- [Issue #80](https://github.com/dfa1/zstd-ffm/issues/80)
- [ADR 0014 — single-threaded native build](0014-single-threaded-native-build.md) (superseded)
- [ADR 0010 — native-context pool](0010-native-context-pool.md) (amended)
- [scripts/build-zstd.sh](../scripts/build-zstd.sh)
