# ADR 0008: `size_t` maps to `JAVA_LONG`, guard sentinels

- **Status:** Accepted
- **Date:** 2026-06-27
- **Deciders:** project maintainer

## Context

zstd's C API uses `size_t` and `unsigned long long` pervasively, and encodes
errors as `size_t` values near `SIZE_MAX` (checked via `ZSTD_isError`). Java has
no unsigned types. The binding must map these consistently and not mistake an
error sentinel for a huge valid size.

## Decision

Model `size_t` and `unsigned long long` as `ValueLayout.JAVA_LONG` (LP64). All
native handles live in `Bindings`. Every public method that returns a
size-typed value routes through `NativeCall.checkReturnValue`, which calls
`ZSTD_isError` and throws `ZstdException` (carrying the decoded `ZstdErrorCode`)
before the value is interpreted as a length. Negative-interpreted sentinels are
guarded at the public boundary.

## Consequences

### Positive
- One place (`NativeCall`) owns the error convention; binding classes stay
  uniform.
- Sentinels become typed Java exceptions, not silent giant lengths.

### Negative
- Values above `Long.MAX_VALUE` are not representable — acceptable: such sizes
  exceed any realistic JVM allocation and zstd's own limits.

### Risks to manage
- A new binding that skips `checkReturnValue` would leak raw sentinels — the
  convention is documented and shared.

## Alternatives considered

- **`JAVA_INT` for sizes:** overflows on payloads >2 GB.
- **Per-call ad-hoc error checks:** duplicated, easy to forget.

## References

- [Bindings.java], [NativeCall.java], [ZstdException.java], [ZstdErrorCode.java]

[Bindings.java]: ../zstd/src/main/java/io/github/dfa1/zstd/Bindings.java
[NativeCall.java]: ../zstd/src/main/java/io/github/dfa1/zstd/NativeCall.java
[ZstdException.java]: ../zstd/src/main/java/io/github/dfa1/zstd/ZstdException.java
[ZstdErrorCode.java]: ../zstd/src/main/java/io/github/dfa1/zstd/ZstdErrorCode.java
