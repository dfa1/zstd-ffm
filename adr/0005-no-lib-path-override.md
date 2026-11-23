# ADR 0005: No `-Dzstd.lib.path` override

- **Status:** Accepted
- **Date:** 2026-06-27
- **Deciders:** project maintainer

## Context

A common convenience in native-binding libraries is a system property pointing
at an alternative shared library (`-Dzstd.lib.path=/path/to/libzstd`). It is
handy for testing a self-built library — but loading a caller-supplied native
library is arbitrary native-code execution in the JVM process.

## Decision

Provide **no** path override. `NativeLibrary` loads only the bundled artifact
from the classpath, extracts it into a private owner-only (`0700`) temp
directory created atomically, and `dlopen`s that. To run a self-built
`libzstd`, the user repackages the native resource jar — documented in
`docs/how-to.md`.

## Consequences

### Positive
- No code path turns an attacker-controlled string into `dlopen` of an
  arbitrary library.
- Owner-only temp dir prevents a local user swapping the library between
  extraction and load (TOCTOU).

### Negative
- Less convenient for ad-hoc library swaps; requires repackaging.

### Risks to manage
- Temp-dir permissions are POSIX-only; on Windows the per-user temp root
  provides the equivalent isolation.

## Alternatives considered

- **`-Dzstd.lib.path` override:** convenient, but an RCE-shaped foot-gun.
- **Allow override only when a flag is set:** still ships the dangerous path.

## References

- [NativeLibrary.java], [docs/how-to.md](../docs/how-to.md)
- [ADR 0004 — per-classifier native jars](0004-per-classifier-native-jars.md)
- Implemented in `9e505c6` / `8d0ea6a` (#8, bundled-only loader), `b2ed28b`
  (owner-only temp dir, Sonar S5443), `c9929b9` (#20, follow-up Sonar fixes).

[NativeLibrary.java]: ../zstd/src/main/java/io/github/dfa1/zstd/NativeLibrary.java
