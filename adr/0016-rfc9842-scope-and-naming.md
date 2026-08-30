# ADR 0016: RFC 9842 support — module split and naming

- **Status:** Proposed
- **Date:** 2026-07-20
- **Deciders:** project maintainer
- **Supersedes:** —
- **Superseded by:** —

## Context

[RFC 9842](https://datatracker.ietf.org/doc/rfc9842/) (Compression Dictionary
Transport) defines dictionary-based HTTP compression. It splits cleanly into
two independent layers, tracked in
[#91](https://github.com/dfa1/zstd-ffm/issues/91) and
[#92](https://github.com/dfa1/zstd-ffm/issues/92):

1. **Wire format** — `dcz` (and Brotli's `dcb`, out of scope here): a 40-byte
   fixed header (an 8-byte Zstandard skippable-frame magic + a 32-byte SHA-256
   digest of the dictionary) followed by a standard Zstandard frame. The
   in-frame hash lets a decoder self-verify/select the dictionary without any
   HTTP context — it is equally useful for at-rest storage (e.g. a NAS or
   archive with files compressed against a ZDICT-trained dictionary that gets
   rotated over time) as it is for HTTP.
2. **HTTP semantics** — the `Use-As-Dictionary` / `Available-Dictionary` /
   `Dictionary-ID` headers, URL pattern matching (WHATWG URL Pattern) to
   decide dictionary applicability, and dictionary cache/storage lifecycle.
   This layer has no relation to the native FFM bindings and pulls in its own
   parsing concerns (RFC 8941 Structured Field Values, URL Pattern matching).

Without a recorded decision, module boundaries and naming for this area would
likely be re-litigated on each pass — several names were considered and
rejected in discussion (`zstd-http`, `rfc9842`, `zstd-transport`) before
converging here.

## Decision

- **`dcz` layer** stays inside the existing `zstd` module, as package
  `io.github.dfa1.zstd.dcz`. It adds no new dependency (SHA-256 from the JDK
  standard library plus the existing dictionary compress/decompress APIs), so
  there is no packaging reason to split it out. Named after the RFC's own
  wire-format token rather than a description of purpose, since "purpose"
  spans both HTTP negotiation (#92) and non-HTTP storage — a functional name
  would be wrong for one of those or force a rename later.
- **HTTP semantics layer** becomes a new module/artifact,
  `io.github.dfa1.zstd:dictionary-transport`. It has no relation to native FFM
  bindings and needs no native libraries, so it is packaged separately from
  `zstd`. Named after the RFC's own title ("Compression Dictionary
  Transport"), not after the RFC number.

## Consequences

### Positive

- Core `zstd` module stays dependency-free and HTTP-agnostic; `dcz` is
  reusable standalone (e.g. NAS/archival dictionary-tagged storage).
- `dictionary-transport` can version/release independently and doesn't pull
  native libraries into consumers that only need header/URL-pattern parsing.
- Naming and module-boundary reasoning is recorded once instead of
  rediscovered cold on a future pass.

### Negative

- Two coordinated issues/modules instead of one; contributors need to know
  the split exists and why.

### Risks to manage

- If RFC 9842 is revised or superseded, `dictionary-transport`'s *content*
  may need updates, but the name stays valid since it describes purpose, not
  a specific document revision.

## Alternatives considered

- **Single module named `zstd-http`:** rejected — implies coupling to an HTTP
  framework/client, which this explicitly avoids (framework-agnostic, no
  Spring/Quarkus/Micronaut/Netty types), and is simply wrong for `dcz`'s
  non-HTTP (NAS/archival) use case.
- **Module named `rfc9842`:** rejected — poor discoverability (the number
  conveys nothing without a lookup) and ages badly if the spec gets errata or
  a successor document.
- **`zstd-transport` for the header/URL-pattern layer:** rejected as
  ambiguous in a dependency list — reads as generic zstd data transport
  rather than specifically HTTP dictionary negotiation.
- **Fold `dcz` into the same new module as the header layer:** rejected —
  `dcz` has zero new dependencies and a broader, non-HTTP applicability; no
  reason to move it out of the core module.

## References

- [RFC 9842 — Compression Dictionary Transport](https://datatracker.ietf.org/doc/rfc9842/)
- [#91 — `dcz` wire format](https://github.com/dfa1/zstd-ffm/issues/91)
- [#92 — Framework-agnostic model layer](https://github.com/dfa1/zstd-ffm/issues/92)
