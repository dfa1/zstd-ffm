# ADR 0003: MemorySegment-first API with thin `byte[]` overloads

- **Status:** Accepted
- **Date:** 2026-06-27
- **Deciders:** project maintainer

## Context

Callers fall into two camps: heap callers holding `byte[]`, and zero-copy
callers whose bytes are already off-heap (an mmap slice in, an arena buffer
out). A `byte[]`-only API forces the second camp to copy on and off heap on
every call — the dominant per-op cost for native compression.

## Decision

Make the `MemorySegment` path primary and add **thin** `byte[]` overloads for
heap callers. The segment path never allocates a `byte[]` for decode output.
`NativeCall.requireNative` guards zero-copy entry points so a stray heap
segment fails fast with a clear message.

## Consequences

### Positive
- Zero-copy callers get an allocation-free path (~0 B/op in benchmarks) —
  the project's differentiator over `byte[]`-only bindings.
- Heap callers keep an ergonomic `byte[]` API.

### Negative
- Two overload families to document and test.
- Segment correctness rules (native vs heap, capacity) are on the caller.

### Risks to manage
- Misuse: passing a heap segment to a zero-copy method. Mitigated by
  `requireNative`.

## Alternatives considered

- **`byte[]`-only (aircompressor-style):** simplest, but forecloses zero-copy —
  the reason this library exists.
- **`ByteBuffer`-first:** weaker lifetime/ownership model than `Arena` +
  `MemorySegment`.

## References

- [docs/explanation.md](../docs/explanation.md#zero-copy-with-memorysegment), [docs/benchmarks.md](../docs/benchmarks.md)
- [ADR 0001 — FFM over JNI](0001-ffm-over-jni.md)
