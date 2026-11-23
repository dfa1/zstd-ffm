# Explanation

## Why FFM and Zig

The bindings use the **Foreign Function & Memory API** rather than JNI: no
hand-written C glue, no separate native compile step for the binding layer, and a
direct path from Java to zstd's addresses — which is what makes the zero-copy
`MemorySegment` API possible. See
[ADR 0001 — FFM over JNI](../adr/0001-ffm-over-jni.md).

The native library itself is built from vendored zstd source via **`zig cc`** as
a drop-in C compiler. zstd is pure C with no build-system dependencies, so the
sources are compiled directly — no autotools, no CMake. Zig bundles clang and
libc for every target, enabling hermetic cross-compilation without a sysroot:
any host can build all six platform artifacts. See
[ADR 0002 — zig cc native build](../adr/0002-zig-cc-native-build.md).

## Zero-copy with `MemorySegment`

zstd-ffm exposes two shapes of API:

- **`byte[]`** — convenient, for callers whose data is already on the heap.
- **`MemorySegment`** — zero-copy *at the call boundary*, for callers whose data
  is already off-heap.

This section explains why the segment shape exists and when it pays off. For the
recipes — sizing output, letting the codec allocate, `ByteBuffer` interop,
streaming, and pledging the size — see the [how-to guide](how-to.md).

### What "zero-copy" means here

It means **no copy at the Java↔native boundary** — the same sense as zero-copy
I/O, where bytes still move but not redundantly between buffers. Compression
itself always reads all input and writes all output; that is the work, not a
copy. "Zero-copy" is about the *boundary*, and applies only to the
`MemorySegment` path — the `byte[]` overloads copy twice (see the honest caveat).

### The core win: no copy at the call boundary

FFM downcalls need a *stable* native pointer. A heap `byte[]` can be relocated by
the GC, so it can't be handed to zstd directly: the `byte[]` overloads copy the
input into a short-lived native scratch arena, run zstd there, and copy the
result back into a fresh `byte[]`. **Two copies per call.**

A native `MemorySegment` already *is* a native address. You hand
`ZSTD_compress` / `ZSTD_decompress` the pointer directly. **No boundary copy.**

```text
byte[] path:   heap byte[] ──copy──▶ native scratch ──ZSTD──▶ native scratch ──copy──▶ heap byte[]
segment path:  native src ───────────────────────────ZSTD──▶ native dst        (no boundary copy)
```

### When it actually pays off (not always)

This only helps if the data is **already native** on both ends. The canonical
case is a memory-mapped reader (e.g. Vortex):

- **Compressed input** — the reader `mmap`s the file into one `MemorySegment`;
  the zstd frame is already a slice of it. A `byte[]` API forces
  `frame.toArray()` → `new byte[]` just to make the call. The segment API passes
  the mmap slice straight to `ZSTD_decompress`.
- **Decompressed output** — allocate the output in your arena
  (`arena.allocate(n)`) and let `ZSTD_decompress` write directly into it. That
  segment becomes the materialized backing buffer as-is — no temp `byte[]`, no
  `MemorySegment.copy`.

The decode path collapses from **mmap → byte[] → byte[] → arena** (three copies)
to **mmap-slice → arena** (no boundary copy).

### Secondary wins

- **Zero GC** — off-heap, no allocation churn in a scan hot loop.
- **No 2 GiB cap** — `byte[]` maxes at `Integer.MAX_VALUE`; segments are
  `long`-indexed.
- **Lifetime safety** — bounds-checked, tied to a confined `Arena`; the same
  ownership model as the rest of an FFM reader, cleaner than raw pointers.
- **Typed reads** — read `JAVA_LONG` / `JAVA_DOUBLE` straight off the
  decompressed segment with no re-wrap.

### The 2 GiB cap: capability vs. throughput

"No 2 GiB cap" is a **capability** claim, and it is unconditional:
`FileChannel.map(mode, pos, size, Arena)` is `long`-indexed and maps a file of
any size in one call. zstd-jni's zero-copy surface is `ByteBuffer`-based, and the
classic 3-arg `FileChannel.map` it depends on rejects anything past
`Integer.MAX_VALUE`, with no in-library workaround.

Whether the mapped path is also **faster** is a separate, measured question. On
one macOS machine, compressing whole files at level 3, the `mmap` +
`MemorySegment` path is close to 2x faster than zstd-jni's buffered stream at
4 MiB, where fixed per-call cost dominates, and within a few percent either way
from 64 MiB to 10 GiB. So for a one-shot large-file compress, `ZstdOutputStream`'s
simplicity is a reasonable default, and the segment path pays off when the data
is already native or the file is past 2 GiB. The tables, the `madvise` hint, the
capability test, and how to reproduce are in
[benchmarks.md — Large files](benchmarks.md#large-files-mmap-vs-streaming).

### Honest caveat

If the caller hands you a heap `byte[]` (the aircompressor fallback path, or
external input), there is no free lunch. The `MemorySegment` entry points accept
only native segments: a heap segment — `MemorySegment.ofArray(...)`, or the
`MemorySegment.ofBuffer(...)` wrap of a heap `ByteBuffer` — is rejected with an
`IllegalArgumentException` rather than silently copied. Heap data goes through the
`byte[]` overloads, which make the two copies explicitly. Only data that is
*already native* avoids the boundary copy. So the API is **segment-first for the
zero-copy fast path, with a thin `byte[]` overload** for the rare heap caller.

We deliberately do **not** add a parallel `ByteBuffer` API surface: FFM already
defines the conversions (`MemorySegment.ofBuffer` in, `segment.asByteBuffer()`
out), so a direct buffer reaches the same path with one wrapping call — see the
[how-to](how-to.md).

### Why a streamed frame can't be decoded zero-copy

The zero-copy decode path reads the frame's **decompressed-size** header field to
size the output arena in one shot. zstd writes that field only when the encoder
knows the total up front — trivially true for one-shot `ZSTD_compress`, but a
*streaming* encoder is fed incrementally and closes the frame without ever being
told the total. So a plain `ZstdOutputStream` frame omits the size, and a
consumer is forced back onto the bounded streaming decoder (allocate, decode a
chunk, grow, repeat) — the very heap-bounce the segment API exists to avoid.

The fix is to **pledge the size** before the first byte, which stamps the content
size into the header and lets a downstream reader size the arena exactly. This is
not a micro-optimization but a correctness gate: it is the difference between a
frame that participates in the zero-copy decode path and one that does not. The
recipe is in the [how-to](how-to.md#pledge-the-size-so-a-streamed-frame-decodes-in-one-shot).

## Dictionary ownership: load vs. reference

A context can hold a dictionary two ways, and they differ in who owns the
digested native copy:

- `loadDictionary` digests the dictionary *into the context*. The context owns
  that copy and frees it with itself — nothing else to close. This is the right
  choice for a single context.
- `refDictionary` attaches a `ZstdCompressDictionary` / `ZstdDecompressDictionary`
  that was digested once and is shared across many contexts. It is *not* tied to
  any one context's lifetime, so it is closed separately (its own
  try-with-resources). That is the price of sharing one digest across a pool.

Sharing is reference-counted. A context that attaches a digested dictionary takes
its own reference, so closing the dictionary while a context still uses it is
safe: the native dictionary is freed only once every referencing context has let
go too. The closed handle itself is unusable from then on. A loaded or referenced
dictionary stays until it is replaced, cleared with `null`, superseded by
`loadDictionary`/`refPrefix`, dropped by a parameter `reset`, or the context is
closed. A digested dictionary that is never closed is still a native-memory
leak, which is why a single context should just use `loadDictionary`.

## Why there is no buffer-less block API

zstd's buffer-less streaming API (`ZSTD_compressBegin` / `compressContinue` /
`compressEnd`) is deprecated upstream in favor of `ZSTD_compressStream2` /
`ZSTD_CCtx_setParameter`. `ZstdCompressStream` wraps exactly that modern pair,
over caller-owned native buffers, so it already gives the zero-copy,
zero-allocation control the old API was used for — see
[ADR 0013](../adr/0013-binding-coverage-scope.md) for what is deliberately left
unbound.

## Benchmarks

Throughput and allocation versus zstd-jni (JNI) and aircompressor (pure Java),
including an async-profiler breakdown: [benchmarks.md](benchmarks.md).

## Why RFC 9842 is sans-io

`zstd-rfc9842` only frames, parses, and hashes — it never opens a socket.
Baking in an HTTP client or server would force every caller onto that choice;
staying [sans-io](https://sans-io.readthedocs.io/) means the same header/frame
logic works whether the caller is the JDK's `HttpClient`, Jetty, Netty, or a
non-HTTP transport that just happens to reuse the `dcz` framing. The
[reference](reference.md#rfc-9842-compression-dictionary-transport) has the
type overview; a repository-wide ArchUnit rule keeps it true as the module
grows, not just documented.

One `AvailableDictionaryHeader` type serves both the `Available-Dictionary`
header value and the hash embedded in a `dcz` frame, rather than two
separately-typed wrappers around the same SHA-256 digest — RFC 9842 §2.2
defines them as the identical 32 bytes, so modeling them as two types would
just be two places to keep in sync for one fact.

## Architecture decisions

The reasoning above is distilled from the full set of Architecture Decision
Records — every foundational choice, its alternatives, and its trade-offs, one
file per decision: [adr/ADR.md](../adr/ADR.md).
