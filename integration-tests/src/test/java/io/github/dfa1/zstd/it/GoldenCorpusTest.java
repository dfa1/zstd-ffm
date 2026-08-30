package io.github.dfa1.zstd.it;

import io.github.dfa1.zstd.ZstdDictionaryId;
import io.github.dfa1.zstd.Zstd;
import io.github.dfa1.zstd.ZstdByteSize;
import io.github.dfa1.zstd.ZstdCompressContext;
import io.github.dfa1.zstd.ZstdCompressionLevel;
import io.github.dfa1.zstd.ZstdDecompressContext;
import io.github.dfa1.zstd.ZstdDictionary;
import io.github.dfa1.zstd.ZstdException;
import io.github.dfa1.zstd.ZstdFrame;
import io.github.dfa1.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Tests against zstd's own vendored golden corpus under
/// `third_party/zstd/tests/` — the canonical, version-matched fixtures the C
/// project uses for its own regression suite. These exercise encoder corners no
/// synthetic payload reaches (block-128k boundaries, RLE/empty blocks, huffman,
/// the PR-3517 block-splitter case) and adversarial frames that must fail.
///
/// The corpus ships via the `third_party/zstd` git submodule. When it is not
/// checked out the cases are skipped, so a shallow clone still builds.
class GoldenCorpusTest {

    private static final Path TESTS = locateCorpus();

    /// Walks up from the working directory to find `third_party/zstd/tests`.
    /// The corpus is the vendored zstd submodule, so its absence is a setup
    /// error — fail loudly rather than silently skipping every golden test.
    private static Path locateCorpus() {
        Path dir = Path.of("").toAbsolutePath();
        for (; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("third_party/zstd/tests");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "golden corpus not found: third_party/zstd/tests is missing — "
                        + "initialize the zstd submodule (git submodule update --init --recursive)");
    }

    private static Stream<Arguments> filesIn(String subdir, String suffix) {
        Path dir = TESTS.resolve(subdir);
        if (!Files.isDirectory(dir)) {
            return Stream.empty();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            List<Path> files = entries
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .toList();
            return files.stream().map(p -> Arguments.of(p.getFileName().toString(), p));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Streaming decode (handles frames that do not store content size).
    private static byte[] javaStreamDecode(byte[] frame) {
        try (ZstdInputStream in = new ZstdInputStream(new ByteArrayInputStream(frame))) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] jniStreamDecode(byte[] frame) {
        try (var in = new com.github.luben.zstd.ZstdInputStream(new ByteArrayInputStream(frame))) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// Valid frames the C project guarantees decode. Decode with both zstd-ffm
    /// and the zstd-jni reference; both must succeed and agree byte-for-byte.
    @Nested
    class GoldenDecompression {

        static Stream<Arguments> frames() {
            return filesIn("golden-decompression", ".zst");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("frames")
        void javaDecodeMatchesJni(String name, Path file) {
            // Given
            byte[] frame = read(file);

            // When
            byte[] javaOut = javaStreamDecode(frame);

            // Then
            assertThat(javaOut).isEqualTo(jniStreamDecode(frame));
        }
    }

    /// Raw inputs the C project compresses in its suite. Round-trip them across
    /// the JNI/FFM boundary in both directions.
    @Nested
    class GoldenCompression {

        static Stream<Arguments> inputs() {
            return filesIn("golden-compression", "");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("inputs")
        void javaCompressJniDecompress(String name, Path file) {
            // Given
            byte[] data = read(file);

            // When
            byte[] frame = Zstd.compress(data, ZstdCompressionLevel.DEFAULT);

            // Then
            assertThat(com.github.luben.zstd.Zstd.decompress(frame, data.length)).isEqualTo(data);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("inputs")
        void jniCompressJavaDecompress(String name, Path file) {
            // Given
            byte[] data = read(file);

            // When
            byte[] frame = com.github.luben.zstd.Zstd.compress(data, ZstdCompressionLevel.MAX.value());

            // Then
            assertThat(Zstd.decompress(frame, new ZstdByteSize(data.length))).isEqualTo(data);
        }
    }

    /// Malformed frames the C project guarantees fail to decode. zstd-ffm must
    /// reject every one rather than return garbage.
    @Nested
    class GoldenDecompressionErrors {

        static Stream<Arguments> frames() {
            return filesIn("golden-decompression-errors", ".zst");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("frames")
        void javaDecodeThrows(String name, Path file) {
            // Given
            byte[] frame = read(file);

            // When
            org.assertj.core.api.ThrowableAssert.ThrowingCallable result = () -> javaStreamDecode(frame);

            // Then
            assertThatThrownBy(result).isInstanceOfAny(ZstdException.class, UncheckedIOException.class);
        }
    }

    /// Raw dictionaries the C project ships under `golden-dictionaries/`. These
    /// are adversarial dictionaries that surfaced real encoder bugs (e.g.
    /// `http-dict-missing-symbols`, where the dictionary omits symbols the entropy
    /// tables expect). zstd's own suite pairs them with `golden-compression/http`
    /// and asserts a dict round-trip survives, so we drive the same payload across
    /// the FFM/JNI boundary in both directions and check the dictionary id rides
    /// along with the frame.
    @Nested
    class GoldenDictionaries {

        static Stream<Named<Path>> dictionaries() {
            return filesIn("golden-dictionaries", "")
                    .map(args -> {
                        Object[] a = args.get();
                        return Named.of((String) a[0], (Path) a[1]);
                    });
        }

        private byte[] payload() {
            return read(TESTS.resolve("golden-compression/http"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("dictionaries")
        void javaDictCompressJniDictDecompress(Path file) {
            // Given
            byte[] raw = read(file);
            byte[] data = payload();
            ZstdDictionary dict = ZstdDictionary.of(raw);
            byte[] frame;
            try (ZstdCompressContext ctx = new ZstdCompressContext()) {
                frame = ctx.compress(data, dict);
            }

            // When
            byte[] restored = com.github.luben.zstd.Zstd.decompress(frame, new ZstdDictDecompress(raw), data.length);

            // Then
            assertThat(restored).isEqualTo(data);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("dictionaries")
        void jniDictCompressJavaDictDecompress(Path file) {
            // Given
            byte[] raw = read(file);
            byte[] data = payload();
            ZstdDictCompress jniDict = new ZstdDictCompress(raw, ZstdCompressionLevel.DEFAULT.value());
            byte[] frame = com.github.luben.zstd.Zstd.compress(data, jniDict);

            // When
            byte[] restored;
            try (ZstdDecompressContext ctx = new ZstdDecompressContext()) {
                restored = ctx.decompress(frame, new ZstdByteSize(data.length), ZstdDictionary.of(raw));
            }

            // Then
            assertThat(restored).isEqualTo(data);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("dictionaries")
        void dictIdRidesWithFrame(Path file) {
            // Given
            byte[] raw = read(file);
            ZstdDictionary dict = ZstdDictionary.of(raw);
            byte[] frame;
            try (ZstdCompressContext ctx = new ZstdCompressContext()) {
                frame = ctx.compress(payload(), dict);
            }

            // When
            ZstdDictionaryId frameDictId = ZstdFrame.dictId(frame);

            // Then
            assertThat(frameDictId)
                    .isEqualTo(dict.id())
                    .isNotEqualTo(ZstdDictionaryId.NONE);
        }
    }
}
