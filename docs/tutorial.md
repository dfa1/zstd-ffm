# Tutorial: Getting started

This walks you from adding the dependency to your first compress/decompress
round-trip.

## 1. Add the dependency

zstd-ffm is on Maven Central. The `zstd` jar is pure Java and ships no `libzstd`
itself — you also need a native artifact. Simplest: depend on `zstd-platform`,
which bundles the bindings plus every platform's native library, so the build
runs on any OS/arch:

```xml
<dependency>
  <groupId>io.github.dfa1.zstd</groupId>
  <artifactId>zstd-platform</artifactId>
  <version>0.8</version>
</dependency>
```

```groovy
implementation("io.github.dfa1.zstd:zstd-platform:0.8")
```

That pulls all six natives (~3.8 MB, five unused per platform). When you target a
known platform and want only its native, import the BOM to pin versions and pick
the matching classifier:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.dfa1.zstd</groupId>
      <artifactId>zstd-bom</artifactId>
      <version>0.8</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>io.github.dfa1.zstd</groupId>
    <artifactId>zstd</artifactId>
  </dependency>
  <dependency>
    <groupId>io.github.dfa1.zstd</groupId>
    <artifactId>zstd-native-osx-aarch64</artifactId>
    <scope>runtime</scope>
  </dependency>
</dependencies>
```

```groovy
implementation(platform("io.github.dfa1.zstd:zstd-bom:0.8"))
implementation("io.github.dfa1.zstd:zstd")
runtimeOnly("io.github.dfa1.zstd:zstd-native-osx-aarch64")
```

Classifiers: `osx-aarch64`, `osx-x86_64`, `linux-x86_64`, `linux-aarch64`,
`windows-x86_64`, `windows-aarch64`. Need JDK 25+. Building the native lib from
source is only for contributors — see the [reference](reference.md).

Every artifact has **zero transitive dependencies** — the bindings are the JDK's
FFM API plus the bundled `libzstd`, nothing else on your classpath.

## 2. Your first round-trip

```java
import io.github.dfa1.zstd.Zstd;

byte[] original = "hello world".getBytes();
byte[] packed   = Zstd.compress(original);
byte[] restored = Zstd.decompress(packed);   // size read from the frame header

assert java.util.Arrays.equals(original, restored);
```

## 3. Run with native access enabled

The FFM API requires an explicit flag:

```bash
java --enable-native-access=ALL-UNNAMED Demo.java
```

## 4. Skip the heap: zero-copy with `MemorySegment`

The `byte[]` round-trip above copies your data onto the heap on the way in and
off it on the way out. When your bytes are **already off-heap** — an `mmap` slice,
an arena buffer — that copy is pure waste. This is the library's strong point:
the `MemorySegment` overloads hand zstd the segment address directly, so there is
**no copy in, no copy out, and no per-call heap allocation** (hence no GC churn).

```java
try (Arena arena = Arena.ofConfined();
     ZstdDecompressContext dctx = new ZstdDecompressContext()) {
    MemorySegment frame = reader.mmapSlice();   // already native — never touches the heap
    ZstdByteSize n = Zstd.decompressedSize(frame); // read the header, no copy
    MemorySegment out = arena.allocate(n.value()); // this segment *is* the output buffer
    dctx.decompress(out, frame);                // native → native
}
```

In benchmarks this path allocates ~0 bytes/op regardless of payload size, while
the `byte[]` path allocates the full output every call. See
[docs/benchmarks.md](benchmarks.md) for numbers and [docs/zero-copy.md](zero-copy.md)
for when it pays.

## 5. Stream data that doesn't fit in memory

For large or unbounded data, don't buffer the whole thing — wrap an ordinary
`java.io` stream. `ZstdOutputStream` / `ZstdInputStream` compress and decompress
incrementally, so memory stays flat no matter how big the payload.

```java
// compress a file as you write it
try (var out = new ZstdOutputStream(Files.newOutputStream(packed), new ZstdCompressionLevel(9))) {
    Files.copy(source, out);
}

// decompress as you read it back (transferTo loops internally until EOF)
try (var in = new ZstdInputStream(Files.newInputStream(packed));
     var out = Files.newOutputStream(restored)) {
    in.transferTo(out);
}
```

They are plain `OutputStream` / `InputStream` subclasses — drop them into any code
that already speaks `java.io`.

That's the whole loop. From here, pick a [how-to guide](how-to.md) for your actual
task, or browse the [reference](reference.md).
