# How-to guides

Task-focused recipes. Each assumes you have the library on the classpath (see the
[tutorial](tutorial.md)).

## Compress on a hot path

Reuse a context to amortize native allocation across many calls:

```java
try (ZstdCompressContext cctx = new ZstdCompressContext().level(new ZstdCompressionLevel(19));
     ZstdDecompressContext dctx = new ZstdDecompressContext()) {
    byte[] packed   = cctx.compress(message);
    byte[] restored = dctx.decompress(packed, message.length);
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
try (ZstdCompressContext cctx = new ZstdCompressContext().level(new ZstdCompressionLevel(19))) {
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
try (ZstdCompressContext cctx = new ZstdCompressContext().level(new ZstdCompressionLevel(19)).checksum(true)) {
    cctx.loadDictionary(dict);          // ZstdDictionary, or a native MemorySegment
    byte[] frame = cctx.compress(record); // dictionary + checksum, together
}
```

For a dictionary reused across a pool of contexts, digest it once and attach it
by reference — no per-call digesting, no copy. It pairs with `reset` for a
pooled, recycled context:

```java
try (ZstdCompressDictionary cdict = dict.compressDict(new ZstdCompressionLevel(19))) {
    // one cctx per pooled worker, all sharing the one digested dictionary
    try (ZstdCompressContext cctx = new ZstdCompressContext()) {
        cctx.refDictionary(cdict);          // borrowed; cdict must outlive cctx
        byte[] a = cctx.compress(first);
        cctx.reset(ZstdResetDirective.SESSION_ONLY); // recycle, keep the dictionary
        byte[] b = cctx.compress(second);
    }
}
```

`refDictionary` only borrows: the digested `cdict` is *not* tied to the context's
lifetime, so it must be closed separately (hence its own try-with-resources). That
is the price of sharing one digest across many contexts. If you have just **one**
context, don't build a `ZstdCompressDictionary` at all — `loadDictionary` above digests
into the context and frees it for you, and a stray, never-closed
`ZstdCompressDictionary` is a native-memory leak.

A loaded or referenced dictionary stays until replaced, cleared with `null`, or
dropped by a parameter `reset`. `ZstdDecompressContext` mirrors all of this.

## Compress many small payloads with a dictionary

For many small, similar payloads (log lines, JSON records, protobufs), a
dictionary compresses each one far smaller than it could be alone. Train one on
representative samples:

```java
ZstdDictionary dict = ZstdDictionary.train(sampleRecords, ZstdByteSize.ofKiB(16));

try (ZstdCompressContext cctx = new ZstdCompressContext();
     ZstdDecompressContext dctx = new ZstdDecompressContext()) {
    byte[] packed   = cctx.compress(record, dict);
    byte[] restored = dctx.decompress(packed, record.length, dict);
}

byte[] persisted = dict.toByteArray();               // store / ship the dictionary
ZstdDictionary reloaded = ZstdDictionary.of(persisted);
```

On a hot path, digest the dictionary once to skip per-call setup:

```java
try (ZstdCompressDictionary cdict = dict.compressDict(new ZstdCompressionLevel(19));
     ZstdDecompressDictionary ddict = dict.decompressDict();
     ZstdCompressContext cctx = new ZstdCompressContext();
     ZstdDecompressContext dctx = new ZstdDecompressContext()) {
    byte[] packed   = cctx.compress(record, cdict);
    byte[] restored = dctx.decompress(packed, record.length, ddict);
}
```

## Avoid heap copies with `MemorySegment`

When your data is already off-heap — an `mmap` slice in, an arena buffer out —
use the `MemorySegment` overloads to skip the heap `byte[]` bounce entirely. FFM
hands zstd the segment address directly: no copy in, no copy out, no GC churn.

```java
try (Arena arena = Arena.ofConfined();
     ZstdDecompressContext dctx = new ZstdDecompressContext()) {
    MemorySegment frame = reader.mmapSlice();           // already native
    ZstdByteSize n = Zstd.decompressedSize(frame);      // read header, no copy
    MemorySegment out = arena.allocate(n.value());      // becomes the backing buffer
    dctx.decompress(out, frame);                        // native → native
}
```

There are matching `compress(dst, src)` / `decompress(dst, src)` overloads (plus
dictionary variants) returning the number of bytes written. For *why and when*
this pays off, see the [explanation](zero-copy.md).

The segment-API map:

| Operation              | byte[] (convenience)                            | MemorySegment (boundary zero-copy)                 |
|------------------------|-------------------------------------------------|----------------------------------------------------|
| compress               | `ZstdCompressContext.compress(byte[])`              | `ZstdCompressContext.compress(dst, src)`               |
| compress + dict        | `ZstdCompressContext.compress(byte[], ZstdCompressDictionary)` | `ZstdCompressContext.compress(dst, src, ZstdCompressDictionary)` |
| decompress             | `ZstdDecompressContext.decompress(byte[], int)`     | `ZstdDecompressContext.decompress(dst, src)`           |
| decompress + dict      | `ZstdDecompressContext.decompress(byte[], int, ZstdDecompressDictionary)` | `ZstdDecompressContext.decompress(dst, src, ZstdDecompressDictionary)` |
| size output (no copy)  | frame header via `Zstd.decompress(byte[])`      | `Zstd.decompressedSize(MemorySegment)`             |

Size `dst` with `Zstd.compressBound(new ZstdByteSize(srcSize))` for compression, or
`Zstd.decompressedSize(frame)` for decompression.

## Let the codec size and allocate the output

If you don't want to size `dst` yourself, pass an `Arena`: the codec sizes,
allocates in *your* arena, and writes the output directly into it (still no
boundary copy). The returned segment is owned by that arena.

```java
MemorySegment frame   = cctx.compress(arena, src);     // bound-sized, trimmed to frame length
MemorySegment decoded = dctx.decompress(arena, frame); // header-sized, exact length
```

| Operation   | explicit dst (you size)              | arena (codec sizes)                         |
|-------------|--------------------------------------|---------------------------------------------|
| compress    | `compress(dst, src)` → bytes written | `compress(arena, src)` → frame segment      |
| decompress  | `decompress(dst, src)` → bytes written | `decompress(arena, frame)` → output segment |

The arena form of `decompress` needs the frame to store its decompressed size —
one-shot `compress` always stamps it; a *streamed* frame only does if you pledge
the size up front (see below). For size-less frames, size `dst` yourself.

## Compress a `ByteBuffer` (NIO / Netty) without copying

Much of the ecosystem speaks `ByteBuffer`. There is no separate `ByteBuffer` API —
wrap the buffer as a `MemorySegment` with `MemorySegment.ofBuffer(...)` and use the
segment overloads above. A **direct** buffer wraps with no boundary copy; a heap
buffer is rejected by the native guard (its wrap is a heap segment), so copy it to a
direct buffer or a `byte[]` first.

```java
try (Arena arena = Arena.ofConfined();
     ZstdCompressContext cctx = new ZstdCompressContext()) {
    ByteBuffer src = channel.map(READ_ONLY, 0, size, arena); // direct, off-heap
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
try (ZstdCompressStream cs = new ZstdCompressStream(level)) {
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

## Pledge the size so a streamed frame decodes in one shot

A streamed frame does **not** record its decompressed size, so it cannot be decoded
zero-copy — `Zstd.decompressedSize(frame)` throws and `decompress(arena, frame)`
can't size the arena (see [the explanation](zero-copy.md)). Tell the encoder the
total up front and it stamps the content size into the header:

```java
try (var zout = ZstdOutputStream.withPledgedSize(sink, new ZstdCompressionLevel(6), new ZstdByteSize(data.length))) {
    zout.write(data);                                 // pledge must equal bytes written
}
MemorySegment src = MemorySegment.ofBuffer(mmap);     // downstream, in a mapped reader
MemorySegment out = dctx.decompress(arena, src);      // one allocation, no boundary copy
```

Pledge whenever the producer streams but the total is known (file length, record
count, `Content-Length`). A pledge that doesn't match the bytes written errors on
close.

## Run against a self-built libzstd

The loader only ever loads the library bundled in the platform native jar on the
classpath — there is no path override. Loading a caller-supplied native library
would be arbitrary native code execution in the JVM, so to use a `libzstd` you
built yourself, build it *into* that resource and rebuild the jar:

```bash
# write the library into the matching native module's resources
./scripts/build-zstd.sh native/<classifier>/src/main/resources <classifier>
# classifier: osx-aarch64 | osx-x86_64 | linux-x86_64 | linux-aarch64
#           | windows-x86_64 | windows-aarch64

./mvnw -pl native/<classifier> install   # repackage the native jar
```

The bundled `.dylib/.so/.dll` are git-ignored and regenerated from the submodule,
so this just overwrites the artifact the loader already trusts.
