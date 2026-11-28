package io.github.dfa1.zstd;

import static io.github.dfa1.zstd.ZstdTestSupport.segmentOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RefDictionaryTest {

    private static final byte[] DICT_BYTES =
            "dictionary sample payload ".repeat(64).getBytes(StandardCharsets.UTF_8);
    private static final byte[] PAYLOAD =
            "hello world hello world hello world ".repeat(50).getBytes(StandardCharsets.UTF_8);

    @Test
    void compressContextSurvivesClosingAStillReferencedDictionary() {
        // Given a digested compress dictionary referenced by a context
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
        ZstdCompressDictionary cdict = Zstd.newCompressDictionary(dict);
        ZstdCompressContext cctx = Zstd.newCompressContext();
        cctx.refDictionary(cdict);

        // When the dictionary is closed while the context still references it
        cdict.close();
        byte[] frame = cctx.compress(PAYLOAD);
        cctx.close();

        // Then the frame still decodes correctly against the original dictionary
        try (ZstdDecompressContext dctx = Zstd.newDecompressContext()) {
            byte[] restored = dctx.decompress(frame, ZstdByteSize.lengthOf(PAYLOAD), dict);
            assertThat(restored).isEqualTo(PAYLOAD);
        }
    }

    @Test
    void decompressContextSurvivesClosingAStillReferencedDictionary() {
        // Given a frame compressed against a dictionary, and a digested decompress
        // dictionary referenced by a context
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
        byte[] frame;
        try (ZstdCompressContext cctx = Zstd.newCompressContext()) {
            frame = cctx.compress(PAYLOAD, dict);
        }
        ZstdDecompressDictionary ddict = Zstd.newDecompressDictionary(dict);
        ZstdDecompressContext dctx = Zstd.newDecompressContext();
        dctx.refDictionary(ddict);

        // When the dictionary is closed while the context still references it
        ddict.close();
        byte[] restored = dctx.decompress(frame, ZstdByteSize.lengthOf(PAYLOAD));
        dctx.close();

        // Then decompression still succeeds
        assertThat(restored).isEqualTo(PAYLOAD);
    }

    @Test
    void replacingAReferencedCompressDictionaryReleasesThePreviousOne() {
        // Given a context referencing a first dictionary
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
        ZstdCompressDictionary first = Zstd.newCompressDictionary(dict);
        ZstdCompressDictionary second = Zstd.newCompressDictionary(dict);
        try (ZstdCompressContext cctx = Zstd.newCompressContext()) {
            cctx.refDictionary(first);

            // When it is replaced by a second dictionary and the first is closed
            cctx.refDictionary(second);
            first.close();
            byte[] frame = cctx.compress(PAYLOAD);

            // Then compression still succeeds against the still-referenced second dictionary
            assertThat(frame).isNotEmpty();
        } finally {
            second.close();
        }
    }

    @Test
    void clearingAReferencedCompressDictionaryWithNullReleasesIt() {
        // Given a context referencing a dictionary
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
        ZstdCompressDictionary cdict = Zstd.newCompressDictionary(dict);
        try (ZstdCompressContext cctx = Zstd.newCompressContext()) {
            cctx.refDictionary(cdict);

            // When the reference is cleared and the dictionary closed
            cctx.refDictionary(null);
            cdict.close();
            byte[] frame = cctx.compress(PAYLOAD);

            // Then compression succeeds plainly, with no dictionary applied
            byte[] restored;
            try (ZstdDecompressContext dctx = Zstd.newDecompressContext()) {
                restored = dctx.decompress(frame);
            }
            assertThat(restored).isEqualTo(PAYLOAD);
        }
    }

    @Test
    void resettingParametersReleasesAReferencedCompressDictionary() {
        // Given a context referencing a dictionary
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
        ZstdCompressDictionary cdict = Zstd.newCompressDictionary(dict);
        try (ZstdCompressContext cctx = Zstd.newCompressContext()) {
            cctx.refDictionary(cdict);

            // When the context is parameter-reset and the dictionary closed
            cctx.reset(ZstdResetDirective.SESSION_AND_PARAMETERS);
            cdict.close();

            // Then the context compresses plainly, with no dangling reference used
            byte[] frame = cctx.compress(PAYLOAD);
            byte[] restored;
            try (ZstdDecompressContext dctx = Zstd.newDecompressContext()) {
                restored = dctx.decompress(frame);
            }
            assertThat(restored).isEqualTo(PAYLOAD);
        }
    }

    @Test
    void loadDictionaryReleasesAPreviouslyReferencedCompressDictionary() {
        // Given a context referencing a dictionary
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
        ZstdCompressDictionary cdict = Zstd.newCompressDictionary(dict);
        try (ZstdCompressContext cctx = Zstd.newCompressContext()) {
            cctx.refDictionary(cdict);

            // When the context switches to a sticky loaded dictionary, which
            // natively supersedes the referenced cdict, and the caller closes it
            cctx.loadDictionary(dict);
            cdict.close();

            // Then the dictionary's own constructor reference was the last one
            // outstanding, so its native memory is already freed
            ThrowingCallable result = cdict::sizeOf;
            assertThatThrownBy(result).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void refPrefixReleasesAPreviouslyReferencedCompressDictionary() {
        // Given a context referencing a dictionary
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
        ZstdCompressDictionary cdict = Zstd.newCompressDictionary(dict);
        try (Arena arena = Arena.ofConfined();
             ZstdCompressContext cctx = Zstd.newCompressContext()) {
            cctx.refDictionary(cdict);

            // When the context refs a native prefix, which natively supersedes
            // the referenced cdict, and the caller closes it
            MemorySegment prefix = segmentOf(arena, "a prior version of the text".getBytes(StandardCharsets.UTF_8));
            cctx.refPrefix(prefix);
            cdict.close();

            // Then the dictionary's own constructor reference was the last one
            // outstanding, so its native memory is already freed
            ThrowingCallable result = cdict::sizeOf;
            assertThatThrownBy(result).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void loadDictionaryReleasesAPreviouslyReferencedDecompressDictionary() {
        // Given a context referencing a dictionary
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
        ZstdDecompressDictionary ddict = Zstd.newDecompressDictionary(dict);
        try (ZstdDecompressContext dctx = Zstd.newDecompressContext()) {
            dctx.refDictionary(ddict);

            // When the context switches to a sticky loaded dictionary, which
            // natively supersedes the referenced ddict, and the caller closes it
            dctx.loadDictionary(dict);
            ddict.close();

            // Then the dictionary's own constructor reference was the last one
            // outstanding, so its native memory is already freed
            ThrowingCallable result = ddict::sizeOf;
            assertThatThrownBy(result).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void refPrefixReleasesAPreviouslyReferencedDecompressDictionary() {
        // Given a context referencing a dictionary
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
        ZstdDecompressDictionary ddict = Zstd.newDecompressDictionary(dict);
        try (Arena arena = Arena.ofConfined();
             ZstdDecompressContext dctx = Zstd.newDecompressContext()) {
            dctx.refDictionary(ddict);

            // When the context refs a native prefix, which natively supersedes
            // the referenced ddict, and the caller closes it
            MemorySegment prefix = segmentOf(arena, "a prior version of the text".getBytes(StandardCharsets.UTF_8));
            dctx.refPrefix(prefix);
            ddict.close();

            // Then the dictionary's own constructor reference was the last one
            // outstanding, so its native memory is already freed
            ThrowingCallable result = ddict::sizeOf;
            assertThatThrownBy(result).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void refDictionaryOnAClosedCompressContextDoesNotLeakARetain() {
        // Given a closed context and a fresh dictionary
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
        ZstdCompressDictionary cdict = Zstd.newCompressDictionary(dict);
        ZstdCompressContext cctx = Zstd.newCompressContext();
        cctx.close();

        // When referencing the dictionary against the already-closed context
        ThrowingCallable result = () -> cctx.refDictionary(cdict);

        // Then the call fails, and the dictionary's own reference alone still
        // frees it — no reference was retained on cctx's behalf
        assertThatThrownBy(result).isInstanceOf(IllegalStateException.class);
        cdict.close();
        ThrowingCallable sizeOf = cdict::sizeOf;
        assertThatThrownBy(sizeOf).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refDictionaryOnAClosedDecompressContextDoesNotLeakARetain() {
        // Given a closed context and a fresh dictionary
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
        ZstdDecompressDictionary ddict = Zstd.newDecompressDictionary(dict);
        ZstdDecompressContext dctx = Zstd.newDecompressContext();
        dctx.close();

        // When referencing the dictionary against the already-closed context
        ThrowingCallable result = () -> dctx.refDictionary(ddict);

        // Then the call fails, and the dictionary's own reference alone still
        // frees it — no reference was retained on dctx's behalf
        assertThatThrownBy(result).isInstanceOf(IllegalStateException.class);
        ddict.close();
        ThrowingCallable sizeOf = ddict::sizeOf;
        assertThatThrownBy(sizeOf).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refDictionaryReturnsTheSameContextForChaining() {
        // Given a compress and a decompress dictionary
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
        try (ZstdCompressDictionary cdict = Zstd.newCompressDictionary(dict);
             ZstdDecompressDictionary ddict = Zstd.newDecompressDictionary(dict);
             ZstdCompressContext cctx = Zstd.newCompressContext();
             ZstdDecompressContext dctx = Zstd.newDecompressContext()) {

            // When setting and then clearing the dictionary reference on both contexts
            ZstdCompressContext cSet = cctx.refDictionary(cdict);
            ZstdCompressContext cCleared = cctx.refDictionary(null);
            ZstdDecompressContext dSet = dctx.refDictionary(ddict);
            ZstdDecompressContext dCleared = dctx.refDictionary(null);

            // Then every call returns the same instance, for chaining
            assertThat(cSet).isSameAs(cctx);
            assertThat(cCleared).isSameAs(cctx);
            assertThat(dSet).isSameAs(dctx);
            assertThat(dCleared).isSameAs(dctx);
        }
    }

    @ParameterizedTest
    @MethodSource("closedCompressDictionaryUses")
    void closedCompressDictionaryHandleRejectsUseWhileAReferencingContextKeepsWorking(
            Consumer<ZstdCompressDictionary> use) {
        // Given a digested compress dictionary still referenced by a context
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
        ZstdCompressDictionary sut = Zstd.newCompressDictionary(dict);
        try (ZstdCompressContext retaining = Zstd.newCompressContext()) {
            retaining.refDictionary(sut);
            sut.close();

            // When the closed handle itself is used
            ThrowingCallable result = () -> use.accept(sut);

            // Then the handle is rejected, yet the context that retained it still compresses with it
            assertThatThrownBy(result)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
            byte[] frame = retaining.compress(PAYLOAD);
            assertThat(Zstd.dictId(frame)).isEqualTo(dict.id());
        }
    }

    @ParameterizedTest
    @MethodSource("closedDecompressDictionaryUses")
    void closedDecompressDictionaryHandleRejectsUseWhileAReferencingContextKeepsWorking(
            Consumer<ZstdDecompressDictionary> use) {
        // Given a dictionary-compressed frame and a digested decompress dictionary still referenced by a context
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
        byte[] frame;
        try (ZstdCompressContext cctx = Zstd.newCompressContext()) {
            frame = cctx.compress(PAYLOAD, dict);
        }
        ZstdDecompressDictionary sut = Zstd.newDecompressDictionary(dict);
        try (ZstdDecompressContext retaining = Zstd.newDecompressContext()) {
            retaining.refDictionary(sut);
            sut.close();

            // When the closed handle itself is used
            ThrowingCallable result = () -> use.accept(sut);

            // Then the handle is rejected, yet the context that retained it still decompresses with it
            assertThatThrownBy(result)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
            assertThat(retaining.decompress(frame, ZstdByteSize.lengthOf(PAYLOAD))).isEqualTo(PAYLOAD);
        }
    }

    @Test
    void closedUnreferencedDictionaryHandleRejectsUse() {
        // Given a digested dictionary no context references
        ZstdCompressDictionary sut = Zstd.newCompressDictionary(ZstdDictionary.of(DICT_BYTES));
        sut.close();

        // When it is used after close
        ThrowingCallable result = sut::id;

        // Then it is rejected
        assertThatThrownBy(result).isInstanceOf(IllegalStateException.class);
    }

    static Stream<Arguments> closedCompressDictionaryUses() {
        return Stream.of(
                use("id()", (Consumer<ZstdCompressDictionary>) ZstdCompressDictionary::id),
                use("sizeOf()", (Consumer<ZstdCompressDictionary>) ZstdCompressDictionary::sizeOf),
                use("compress(byte[], dict)", (ZstdCompressDictionary d) -> {
                    try (ZstdCompressContext other = Zstd.newCompressContext()) {
                        other.compress(PAYLOAD, d);
                    }
                }),
                use("refDictionary(dict)", (ZstdCompressDictionary d) -> {
                    try (ZstdCompressContext other = Zstd.newCompressContext()) {
                        other.refDictionary(d);
                    }
                }));
    }

    static Stream<Arguments> closedDecompressDictionaryUses() {
        return Stream.of(
                use("id()", (Consumer<ZstdDecompressDictionary>) ZstdDecompressDictionary::id),
                use("sizeOf()", (Consumer<ZstdDecompressDictionary>) ZstdDecompressDictionary::sizeOf),
                use("decompress(byte[], size, dict)", (ZstdDecompressDictionary d) -> {
                    try (ZstdDecompressContext other = Zstd.newDecompressContext()) {
                        other.decompress(Zstd.compress(PAYLOAD), ZstdByteSize.lengthOf(PAYLOAD), d);
                    }
                }),
                use("refDictionary(dict)", (ZstdDecompressDictionary d) -> {
                    try (ZstdDecompressContext other = Zstd.newDecompressContext()) {
                        other.refDictionary(d);
                    }
                }));
    }

    private static <D> Arguments use(String name, Consumer<D> use) {
        return Arguments.of(Named.of(name, use));
    }
}
