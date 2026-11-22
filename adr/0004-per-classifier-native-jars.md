# ADR 0004: Per-classifier native JARs

- **Status:** Accepted
- **Date:** 2026-06-27
- **Deciders:** project maintainer

## Context

The bundled `libzstd` must reach the JVM at runtime. A single fat jar carrying
all six platform libraries bloats every download ~6x. The library also has to
load correctly both on the classpath and as a named JPMS module (ADR 0011).

## Decision

Ship one `zstd-native-<classifier>` artifact per platform, each packaging only
its own `libzstd.{so,dylib,dll}` under `native/<classifier>/`. At runtime
`NativeLibrary` computes the classifier from `os.name`/`os.arch`, loads the
resource via the class loader, extracts it to an owner-only temp dir, and
`dlopen`s it once at class-init. Callers depend on the library jar plus the one
native jar for their platform (or via the BOM).

## Consequences

### Positive
- A consumer downloads ~one platform's library, not six.
- The classifier directory name contains a dash, so it is not a valid package
  and the resource escapes module encapsulation — readable across modules and
  on the classpath alike.

### Negative
- Consumers must select the right native artifact (eased by the BOM).
- Six native modules to build and publish.

### Risks to manage
- New CPU arch silently mis-resolving: `classifier()` throws a clear
  `UnsatisfiedLinkError` for unsupported arches rather than guessing.

## Alternatives considered

- **Fat jar (all platforms):** simplest for consumers, 6x bloat for everyone.
- **System-installed libzstd:** version drift, no hermetic guarantee.

## References

- [NativeLibrary.java], [bom/](../bom)
- [ADR 0005 — no lib-path override](0005-no-lib-path-override.md)

[NativeLibrary.java]: ../zstd/src/main/java/io/github/dfa1/zstd/NativeLibrary.java
