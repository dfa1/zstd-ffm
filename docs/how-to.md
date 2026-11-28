# How-to guides

Task-focused recipes. Each assumes you have the library on the classpath (see the
[tutorial](tutorial.md)).

## Compress on a hot path

Reuse a context to amortize native allocation across many calls:

```java
try (ZstdCompressContext cctx = Zstd.newCompressContext();
     ZstdDecompressContext dctx = Zstd.newDecompressContext()) {
    cctx.level(ZstdCompressionLevel.of(19)); // configure inside the try, so a failing setter can't leak cctx
    byte[] packed   = cctx.compress(message);
    byte[] restored = dctx.decompress(packed, ZstdByteSize.lengthOf(message));
}
```

Pick the level explicitly with `ZstdCompressionLevel.MAX` /
`ZstdCompressionLevel.FASTEST` when you need the extreme ends.

## Reset a context to recycle it

A context is already reusable across whole `compress` / `decompress` calls. Reset
goes further: it recycles the *native state* of one context — for pooled contexts,
or to abort a half-written frame and start clean — without freeing and recreating
it. Pick what to clear with `ZstdResetDirective`:

```java
try (ZstdCompressContext cctx = Zstd.newCompressContext()) {
    cctx.level(ZstdCompressionLevel.of(19));
    byte[] a = cctx.compress(first);

    // Cheap: drop any unflushed frame state, keep the level and parameters.
    cctx.reset(ZstdResetDirective.SESSION_ONLY);
    byte[] b = cctx.compress(second);

    // Full wipe: parameters back to default, dictionary cleared, level reset to
    // ZstdCompressionLevel.DEFAULT. Only valid between frames, not mid-frame.
    cctx.reset(ZstdResetDirective.SESSION_AND_PARAMETERS);
}
```

`ZstdDecompressContext.reset(...)` works the same way. Reuse alone amortizes
allocation; reset lets a long-lived or pooled context return to a known state
without churning native memory.

## Compress with a dictionary *and* advanced parameters

The per-call `compress(src, dict)` overloads take the legacy dictionary path,
which ignores the advanced parameters (checksum, window log, long-distance
matching) set on the context. To combine the two, make the dictionary *sticky*
with `loadDictionary` — then the normal `compress` path honors both:

```java
try (ZstdCompressContext cctx = Zstd.newCompressContext()) {
    cctx.level(ZstdCompressionLevel.of(19)).checksum(true);
    cctx.loadDictionary(dict);          // ZstdDictionary, or a native MemorySegment
    byte[] frame = cctx.compress(record); // dictionary + checksum, together
}
```

For a dictionary reused across a pool of contexts, digest it once and attach it
by reference — no per-call digesting, no copy. It pairs with `reset` for a
pooled, recycled context:

```java
try (ZstdCompressDictionary cdict = Zstd.newCompressDictionary(dict, ZstdCompressionLevel.of(19))) {
    // one cctx per pooled worker, all sharing the one digested dictionary
    try (ZstdCompressContext cctx = Zstd.newCompressContext()) {
        cctx.refDictionary(cdict);          // shared; cdict has its own lifetime
        byte[] a = cctx.compress(first);
        cctx.reset(ZstdResetDirective.SESSION_ONLY); // recycle, keep the dictionary
        byte[] b = cctx.compress(second);
    }
}
```

`cdict` has its own lifetime, so close it separately; with just **one** context,
use `loadDictionary` instead. `ZstdDecompressContext` mirrors all of this. Why
sharing works this way, and when a dictionary is released:
[explanation — dictionary ownership](explanation.md#dictionary-ownership-load-vs-reference).

## Compress many small payloads with a dictionary

For many small, similar payloads (log lines, JSON records, protobufs), a
dictionary compresses each one far smaller than it could be alone. Train one on
representative samples:

```java
ZstdDictionary dict = ZstdDictionary.train(sampleRecords, ZstdByteSize.ofKiB(16));

try (ZstdCompressContext cctx = Zstd.newCompressContext();
     ZstdDecompressContext dctx = Zstd.newDecompressContext()) {
    byte[] packed   = cctx.compress(record, dict);
    byte[] restored = dctx.decompress(packed, ZstdByteSize.lengthOf(record), dict);
}

byte[] persisted = dict.toByteArray();               // store / ship the dictionary
ZstdDictionary reloaded = ZstdDictionary.of(persisted);
```

On a hot path, digest the dictionary once to skip per-call setup:

```java
try (ZstdCompressDictionary cdict = Zstd.newCompressDictionary(dict, ZstdCompressionLevel.of(19));
     ZstdDecompressDictionary ddict = Zstd.newDecompressDictionary(dict);
     ZstdCompressContext cctx = Zstd.newCompressContext();
     ZstdDecompressContext dctx = Zstd.newDecompressContext()) {
    byte[] packed   = cctx.compress(record, cdict);
    byte[] restored = dctx.decompress(packed, ZstdByteSize.lengthOf(record), ddict);
}
```

## Avoid heap copies with `MemorySegment`

When your data is already off-heap — an `mmap` slice in, an arena buffer out —
use the `MemorySegment` overloads to skip the heap `byte[]` bounce entirely. FFM
hands zstd the segment address directly: no copy in, no copy out, no GC churn.

```java
try (Arena arena = Arena.ofConfined();
     ZstdDecompressContext dctx = Zstd.newDecompressContext()) {
    MemorySegment frame = reader.mmapSlice();           // already native
    ZstdByteSize n = Zstd.decompressedSize(frame);      // read header, no copy
    MemorySegment out = arena.allocate(n.value());      // becomes the backing buffer
    dctx.decompress(out, frame);                        // native → native
}
```

There are matching `compress(dst, src)` / `decompress(dst, src)` overloads (plus
dictionary variants) returning the number of bytes written. For *why and when*
this pays off, see the [explanation](explanation.md#zero-copy-with-memorysegment).

The segment-API map:

| Operation              | byte[] (convenience)                            | MemorySegment (boundary zero-copy)                 |
|------------------------|-------------------------------------------------|----------------------------------------------------|
| compress               | `ZstdCompressContext.compress(byte[])`              | `ZstdCompressContext.compress(dst, src)`               |
| compress + dict        | `ZstdCompressContext.compress(byte[], ZstdCompressDictionary)` | `ZstdCompressContext.compress(dst, src, ZstdCompressDictionary)` |
| decompress             | `ZstdDecompressContext.decompress(byte[], ZstdByteSize)`     | `ZstdDecompressContext.decompress(dst, src)`           |
| decompress + dict      | `ZstdDecompressContext.decompress(byte[], ZstdByteSize, ZstdDecompressDictionary)` | `ZstdDecompressContext.decompress(dst, src, ZstdDecompressDictionary)` |
| size output (no copy)  | frame header via `Zstd.decompress(byte[])`      | `Zstd.decompressedSize(MemorySegment)`             |

Size `dst` with `Zstd.compressBound(ZstdByteSize.of(srcSize))` for compression, or
`Zstd.decompressedSize(frame)` for decompression.

## Let the codec size and allocate the output

If you don't want to size `dst` yourself, pass an `Arena`: the codec sizes,
allocates in *your* arena, and writes the output directly into it (still no
boundary copy). The returned segment is owned by that arena.

```java
// cctx / dctx: open contexts from Zstd.newCompressContext() / newDecompressContext();
// arena: your own Arena, which owns both returned segments; src: a native segment
MemorySegment frame   = cctx.compress(arena, src);     // bound-sized, trimmed to frame length
MemorySegment decoded = dctx.decompress(arena, frame); // header-sized, exact length
```

| Operation   | explicit dst (you size)              | arena (codec sizes)                         |
|-------------|--------------------------------------|---------------------------------------------|
| compress    | `compress(dst, src)` → bytes written | `compress(arena, src)` → frame segment      |
| decompress  | `decompress(dst, src)` → bytes written | `decompress(arena, frame)` → output segment |

The arena form of `decompress` needs the frame to store its decompressed size.
One-shot compression stamps it — `Zstd.compress` always, a context's `compress`
unless you turn it off with `contentSize(false)`; a *streamed*
frame only does if you pledge the size up front (see below). For size-less
frames, size `dst` yourself.

## Compress a `ByteBuffer` (NIO / Netty) without copying

Much of the ecosystem speaks `ByteBuffer`. There is no separate `ByteBuffer` API —
wrap the buffer as a `MemorySegment` with `MemorySegment.ofBuffer(...)` and use the
segment overloads above. A **direct** buffer wraps with no boundary copy; a heap
buffer is rejected by the native guard (its wrap is a heap segment), so copy it to a
direct buffer or a `byte[]` first.

```java
try (Arena arena = Arena.ofConfined();
     ZstdCompressContext cctx = Zstd.newCompressContext()) {
    ByteBuffer src = channel.map(READ_ONLY, 0, size);        // MappedByteBuffer: direct, off-heap
    MemorySegment in  = MemorySegment.ofBuffer(src);         // covers [position, limit)
    MemorySegment out = cctx.compress(arena, in);            // arena-owned frame
    ByteBuffer frame  = out.asByteBuffer();                  // direct view, no copy
}
```

- `ofBuffer` covers the buffer's `[position, limit)`; a read-only buffer yields a
  read-only segment.
- The wrapped segment borrows the buffer's lifetime — keep the buffer reachable
  while compressing.
- `asByteBuffer()` on a native segment returns a **direct** buffer aliasing the same
  bytes, but always `BIG_ENDIAN`. For multi-byte reads, restore native order:
  `out.asByteBuffer().order(ByteOrder.nativeOrder())`. (Irrelevant for a pure byte
  payload.) That buffer also borrows the arena's scope — don't let it outlive the
  `try`.

## Stream zero-copy over native buffers

When data is large or arrives incrementally but both ends stay off-heap, use the
segment stream drivers — `ZstdCompressStream` / `ZstdDecompressStream` — which drive
`ZSTD_compressStream2` / `ZSTD_decompressStream` directly over native buffers in
bounded memory, no heap bounce (unlike `ZstdOutputStream` / `ZstdInputStream`, which
copy through `byte[]` to fit `java.io`).

Each step processes as much of `src` as fits in `dst` and reports a
`ZstdStreamResult` (`bytesConsumed`, `bytesProduced`, `remaining`). Advance the
source, drain `dst`, and for compression finish with `ZstdEndDirective.END` until
`isComplete()`:

```java
try (ZstdCompressStream cs = Zstd.newCompressStream()) {
    cs.level(level);
    long off = 0;
    ZstdStreamResult r;
    do {
        r = cs.compress(dst, src.asSlice(off), ZstdEndDirective.END);
        off += r.bytesConsumed();
        sink.write(dst.asSlice(0, r.bytesProduced()));
    } while (!r.isComplete());
}
```

Both drivers take an optional `ZstdDictionary`. Decompression mirrors the loop,
calling `decompress(dst, src)` until a result `isComplete()`.

## Stream over pooled `DirectByteBuffer`s (Netty-style)

Combine the two recipes above for a pooled-buffer network stack: streaming
compression driven directly on `DirectByteBuffer`s the caller already owns,
with no allocation per message.

```java
try (ZstdCompressStream cs = Zstd.newCompressStream()) {
    cs.level(level);
    MemorySegment in  = MemorySegment.ofBuffer(pooledSrc); // e.g. Netty ByteBuf.nioBuffer()
    MemorySegment out = MemorySegment.ofBuffer(pooledDst);
    long off = 0;
    ZstdStreamResult r;
    do {
        r = cs.compress(out, in.asSlice(off), ZstdEndDirective.FLUSH);
        off += r.bytesConsumed();
        channel.write(pooledDst.slice(0, (int) r.bytesProduced()));
    } while (!r.isComplete());                             // input consumed and fully flushed
}
```

`in`/`out` alias pool-owned buffers, so nothing allocates inside the loop —
`cs` itself allocates once, at construction. Use `ZstdEndDirective.FLUSH`
per message to push pending bytes out without closing the frame, and
`ZstdEndDirective.END` (see the recipe above) when the frame is done. A
`ZstdCompressStream` is not limited to one frame: once `END` completes, the
next `compress` call starts a new frame on the same stream, so one instance can
emit a sequence of frames.

This is also how to drive zstd if you were looking for the buffer-less
`ZSTD_compressBegin`/`compressContinue`/`compressEnd` API
([why it isn't bound](explanation.md#why-there-is-no-buffer-less-block-api)).

## Pledge the size so a streamed frame decodes in one shot

A streamed frame does **not** record its decompressed size, so it cannot be decoded
zero-copy — `Zstd.decompressedSize(frame)` throws and `decompress(arena, frame)`
can't size the arena (see [the explanation](explanation.md#why-a-streamed-frame-cant-be-decoded-zero-copy)). Tell the encoder the
total up front and it stamps the content size into the header:

```java
try (var zout = Zstd.newPledgedOutputStream(sink, ZstdByteSize.lengthOf(data))) {
    zout.level(ZstdCompressionLevel.of(6));
    zout.write(data);                                 // pledge must equal bytes written
}
// downstream, in a mapped reader: mmap is a mapped ByteBuffer of the frame, dctx an
// open Zstd.newDecompressContext(), arena the reader's own Arena
MemorySegment src = MemorySegment.ofBuffer(mmap);
MemorySegment out = dctx.decompress(arena, src);      // one allocation, no boundary copy
```

Pledge whenever the producer streams but the total is known (file length, record
count, `Content-Length`). A pledge that doesn't match the bytes written errors on
close.

## Negotiate and use an RFC 9842 dictionary

`zstd-rfc9842` (a separate module — see the [reference](reference.md#rfc-9842-compression-dictionary-transport))
implements RFC 9842's `dcz` wire format and header values, with no HTTP client
or server of its own ([why](explanation.md#why-rfc-9842-is-sans-io)) — wire it
into whatever you're already using.

**Server** — advertise a dictionary once, then wrap every response compressed against it:

```java
import io.github.dfa1.zstd.*;
import io.github.dfa1.zstd.rfc9842.*;

ZstdDictionary dict = ZstdDictionary.train(samples, ZstdByteSize.ofKiB(8));
UseAsDictionaryHeader useAsDictionary = UseAsDictionaryHeader.of("/api/*");
AvailableDictionaryHeader hash = AvailableDictionaryHeader.of(dict); // once, at startup

// first response — advertise the dictionary:
// Use-As-Dictionary: useAsDictionary.toHeaderValue()

// every later response, once the client has fetched it:
try (ZstdCompressContext cctx = Zstd.newCompressContext()) {
    byte[] frame = cctx.compress(payload, dict);
    byte[] dcz = Rfc9842Frame.wrap(frame, hash); // send with Content-Encoding: dcz
}
```

**Client** — negotiate once from the fetched dictionary, then unwrap and verify each response:

```java
Rfc9842Negotiation negotiation = Rfc9842Negotiation.from(dictionaryBytes, useAsDictionaryHeaderValue);

// every request:
// Available-Dictionary: negotiation.availableDictionary().toHeaderValue()
// Dictionary-ID: negotiation.dictionaryId(), if present, echoed back

try (ZstdDecompressContext dctx = Zstd.newDecompressContext()) {
    byte[] frame = Rfc9842Frame.unwrap(dcz, negotiation.availableDictionary()); // verifies the hash
    byte[] payload = dctx.decompress(frame, ZstdByteSize.ofMiB(16), negotiation.dictionary());
}
```

A full runnable demo — a server plus two clients, one RFC-9842-aware and one
plain, against the same endpoints, built on embedded Jetty so it speaks both
real HTTP/1.1 and real HTTP/2 — lives in `rfc9842`'s test classpath
([`ServerDemo`](../rfc9842/src/test/java/io/github/dfa1/zstd/rfc9842/demo/ServerDemo.java)
and friends).

## Run against a self-built libzstd

The loader only ever loads the library bundled in the platform native jar on the
classpath, as the resource `native/<classifier>/libzstd.<ext>`. There is no path
override ([ADR 0005](../adr/0005-no-lib-path-override.md)): loading a
caller-supplied native library would be arbitrary native code execution in the
JVM. To run against a `libzstd` you built yourself, put it *into* that resource
and repackage the native jar:

```bash
# classifier: osx-aarch64 | osx-x86_64 | linux-x86_64 | linux-aarch64
#           | windows-x86_64 | windows-aarch64
# ext:        dylib (osx) | so (linux) | dll (windows)
RES=native/<classifier>/src/main/resources/native/<classifier>

# Option A: your own binary — drop it in place of the bundled one
cp /path/to/your/libzstd.<ext> "$RES/libzstd.<ext>"

# Option B: a patched vendored source (third_party/zstd) — delete the bundled
# library, since scripts/build-zstd.sh skips the build when it already exists
rm "$RES/libzstd.<ext>"

./mvnw -pl native/<classifier> package   # Option B rebuilds from the submodule here
```

The repackaged jar is `native/<classifier>/target/zstd-native-<classifier>-<version>.jar`;
put it on your application's classpath in place of the published
`zstd-native-<classifier>` artifact. The bundled `.dylib/.so/.dll` files are
git-ignored, so deleting yours later and running `package` again restores the
library built from the vendored submodule.
