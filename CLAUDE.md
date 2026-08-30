# zstd-ffm

Java **Foreign Function & Memory (FFM)** bindings for [Zstandard](https://github.com/facebook/zstd).
No JNI, no hand-written C. Targets **JDK 25+** (stable `java.lang.foreign`).

The differentiator is **dictionary compression** — trained from your own data via
ZDICT — plus a **zero-copy `MemorySegment` API** for callers whose bytes are
already off-heap (e.g. an mmap slice in, an arena buffer out).

## Layout

Multi-module Maven build (`io.github.dfa1.zstd:zstd`):

- `zstd/` — the library module, artifactId `zstd`, pure-Java FFM bindings
  (package `io.github.dfa1.zstd`). The only module with Java sources.
- `native/<classifier>/` — one module per platform; each packages a
  `libzstd.{dylib,so,dll}` built from the `third_party/zstd` submodule. No Java.
  Classifiers: `osx-aarch64`, `osx-x86_64`, `linux-x86_64`, `linux-aarch64`,
  `windows-x86_64`, `windows-aarch64`.
- `bom/` — dependency BOM.
- `zstd/` — vendored `facebook/zstd` git submodule (the C source of truth).

## Native build

`scripts/build-zstd.sh <output-resources-dir> <classifier>` compiles the zstd
library sources directly with **`zig cc`** — no zstd Makefile, no CMake. Zig
bundles clang + libc for every target, so any host cross-compiles any of the six
classifiers hermetically. The Maven `exec` plugin runs it in `generate-resources`;
it is idempotent (skips if the library already exists).

Key flags: `-DZSTD_DISABLE_ASM=1` (drops x86-only `.S`, identical codegen on every
target), `-fvisibility=hidden` on ELF/Mach-O, and lld `--export-all-symbols` on
Windows (do **not** use hidden visibility there — it suppresses the PE exports).

Built `.dylib/.so/.dll` are git-ignored; they are regenerated from the submodule.

## Code conventions

- Checkstyle-clean (`./mvnw validate` runs it); see the Code style section below.
- Native pointers wrap in `NativeObject` (`AutoCloseable`, idempotent close).
- All native handles live in `Bindings`; `size_t`/`unsigned long long` map to
  `JAVA_LONG`. Public methods guard zstd's negative sentinels.
- Native structs: model the layout as a named `StructLayout` and access fields
  through `static final VarHandle`s from it (`LAYOUT.varHandle(groupElement("…"))`),
  deriving size from `LAYOUT.byteSize()`. No hardcoded offsets/sizes — see
  `ZstdStreamBuffer`.
- API is **segment-first for the zero-copy fast path, with thin `byte[]`
  overloads** for heap callers. Never allocate a `byte[]` for decode output on a
  hot path — see [docs/zero-copy.md](docs/zero-copy.md).
- Run with `--enable-native-access=ALL-UNNAMED`.
- **Wrap naked `int`/`long`/`String` at public boundaries** when the value has a
  validity constraint, unit, or semantic identity — sizes (`ZstdByteSize`),
  levels (`ZstdCompressionLevel`), versions (`ZstdVersion`), window logs
  (`ZstdWindowLog`), magic variants (`ZstdMagicVariant`), dictionary ids
  (`ZstdDictionaryId`). Validate once at construction so illegal states are
  unrepresentable rather than guarded by scattered runtime checks; for
  hostile/unrepresentable input return `Optional<VO>` instead of throwing. This
  sweep is considered done — see CHANGELOG.md's Unreleased section. Exception:
  heterogeneous native passthroughs like
  `ZstdCompressContext#parameter(ZstdCompressParameter, int)` stay raw `int`,
  since the value's meaning depends on which enum constant is passed and native
  bounds-checking already validates it; the common cases have typed convenience
  methods (`level()`, `windowLog(ZstdWindowLog)`, `checksum(boolean)`).

## Testing

- Cover happy path, negative cases (invalid input / errors), and corners (empty, zero, max,
  boundaries). Unit tests must be fast — no file I/O, network, or sleep; mock or use in-memory data.
- **Integration tests are ground truth** (no formal spec): interop with the `zstd-jni` reference
  binding (luben, the zstd C library via JNI) and the vendored golden corpus under
  `third_party/zstd/tests/` (`golden-compression`, `golden-decompression`,
  `golden-decompression-errors`, `golden-dictionaries`). Write one for every encoding round-trip
  and file-format boundary.
- JUnit 5 + Mockito (BDDMockito) + AssertJ. Class under test named `sut`. Every test has
  `// Given` / `// When` / `// Then`. BDDMockito only: `given(mock.m()).willReturn(v)` /
  `then(...)` (static-import only `given`/`then`, never `willReturn`/`willThrow`).
  For exception assertions, capture the action under `// When` as a
  `ThrowingCallable result = () -> sut.m(...);` and assert it under `// Then` with
  `assertThatThrownBy(result)` — the callable is the When, the assertion is the Then.
- Prefer `@ParameterizedTest` over copy-paste (`@ValueSource`, else `@ArgumentsSource`/named cases).
  For large input spaces use seeded-random `@MethodSource` generators — they find corners examples
  miss. Put generators in `RandomArrays` (integration) or a similar util; keep counts low (10–30)
  when the test does file I/O or JNI.
- `@Nested` groups related scenarios (`@BeforeEach` in a nested class applies only to it). Private
  helpers go after all `@Test` methods.

# Code style

- 4-space indent, **zero SonarQube bugs/smells**, no `sun.misc.Unsafe` or internal JDK APIs.
- Prefer explicit over clever; fail fast on unhandled cases.
- Always braces for `if`/`else`/`for`/`while`, even one-liners (`if (c) { return a; }`).
- **Time quantities use `java.time.Duration`, never `long`** (no `long timeoutMs`/`delayNanos`).
  Exception: low-level JDK interop taking `long ns` (`Thread.sleep`, `LockSupport.parkNanos`,
  `System.nanoTime` math) — convert at the call site via `duration.toNanos()`/`toMillis()`.

### Javadoc (build-enforced: `failOnError` + `failOnWarnings`)

- Every public method: main prose description, `@param` per parameter, `@return` (unless `void`).
  Every public record: `@param` per component on the class doc. `@see`-only counts as no description.
- All `///` Markdown — **no HTML** (checkstyle `RegexpSingleline` blocks `<p>`,`<ul>`,`<li>`,
  `<strong>`,`<pre>`,`<table>`, …). Use blank `///` for paragraphs, `- ` lists, ` ```java ``` `,
  `**bold**`. Cross-refs `[ClassName#method(ParamType)]` — verify the target exists (wrong refs are
  **errors**).
- **American English everywhere** (javadoc, comments, identifiers): `recognize`/`optimize`/
  `finalize`/`serialize`/`behavior`/`color`, never `-ise`/`-isation`/`-our`. Matches the JDK
  (`Object.finalize`, `Serializable`) and zstd's C docs.
- Check: `./mvnw javadoc:javadoc -pl core` must produce zero output.
