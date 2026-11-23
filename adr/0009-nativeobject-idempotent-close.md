# ADR 0009: `NativeObject` — AutoCloseable, idempotent close

- **Status:** Accepted
- **Date:** 2026-06-27
- **Deciders:** project maintainer

## Context

Native contexts and dictionaries (CCtx, DCtx, CDict, DDict) own off-heap memory
that must be freed via their zstd `ZSTD_free*` calls. Java's GC does not free
native memory; double-free is undefined behavior. The lifecycle model must be
deterministic and safe to call twice.

## Decision

All native-pointer holders extend `NativeObject`, which is `AutoCloseable` with
an **idempotent** `close()` — the second and later calls are no-ops. Callers use
try-with-resources. The wrapped pointer is freed exactly once.

> **Amended (2026-11-22, [#143](https://github.com/dfa1/zstd-ffm/issues/143)):**
> the digested dictionaries (`ZstdCompressDictionary`, `ZstdDecompressDictionary`)
> extend [NativePointerWithRefCount.java] instead of `NativeObject`. It keeps this
> decision's contract — `AutoCloseable`, idempotent `close()`, pointer freed
> exactly once — but frees only when the last reference is released, because a
> context that attaches a dictionary with `refDictionary` holds its own reference.
> The closed handle itself throws on use. Contexts and segment streams still
> extend `NativeObject`.

## Consequences

### Positive
- Deterministic release via try-with-resources; no reliance on GC/finalizers.
- Idempotent close makes double-close (e.g. explicit `close()` then
  try-with-resources unwind) safe.

### Negative
- Caller must manage lifetime; a leaked object leaks native memory until
  process exit.

### Risks to manage
- This idempotent-close contract is the foundation any future pooling
  (ADR 0010) must respect — the pool owns close, borrowers must not free.

## Alternatives considered

- **Cleaner/finalizer-based release:** non-deterministic, GC-timing-dependent
  native frees.
- **Manual free method (non-AutoCloseable):** loses try-with-resources.

## References

- [NativeObject.java], [NativePointerWithRefCount.java]
- [ADR 0010 — native-context pool](0010-native-context-pool.md)

[NativeObject.java]: ../zstd/src/main/java/io/github/dfa1/zstd/NativeObject.java
[NativePointerWithRefCount.java]: ../zstd/src/main/java/io/github/dfa1/zstd/NativePointerWithRefCount.java
