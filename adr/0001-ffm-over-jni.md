# ADR 0001: FFM bindings over JNI

- **Status:** Accepted
- **Date:** 2026-06-27
- **Deciders:** project maintainer

## Context

Calling the zstd C library from Java needs a native bridge. The established
option is JNI (what `zstd-jni` uses): hand-written or generated C glue compiled
per platform. JDK 25 ships the stable Foreign Function & Memory API
(`java.lang.foreign`), which calls C directly from Java with no C glue.

## Decision

Use the FFM API exclusively. No JNI, no hand-written C, no generated stubs.
Native symbols bind to `MethodHandle`s in `Bindings`; the library targets JDK
25+ where `java.lang.foreign` is stable.

## Consequences

### Positive
- Zero C source to maintain, review, or compile for the *bindings* (only zstd
  itself is compiled — see ADR 0002).
- Enables the zero-copy `MemorySegment` path (ADR 0003) — JNI must copy across
  the boundary; FFM can pass off-heap addresses directly.
- Pure-Java artifact; native code is only the bundled `libzstd`.

### Negative
- Hard floor at JDK 25. No JDK 17/21 support.
- FFM downcalls are a restricted operation: callers must pass
  `--enable-native-access`.

### Risks to manage
- FFM is young; signatures/restrictions may evolve. Centralized in `Bindings`
  and `NativeCall` so changes are localized.

## Alternatives considered

- **JNI (zstd-jni):** mature, but ships hand-written C and copies at the
  boundary, foreclosing zero-copy.
- **JNA / JNR:** reflection-based FFI, slower per call, also pre-FFM.

## References

- [Bindings.java], [NativeCall.java]
- [ADR 0003 — segment-first API](0003-segment-first-api.md)

[Bindings.java]: ../zstd/src/main/java/io/github/dfa1/zstd/Bindings.java
[NativeCall.java]: ../zstd/src/main/java/io/github/dfa1/zstd/NativeCall.java
