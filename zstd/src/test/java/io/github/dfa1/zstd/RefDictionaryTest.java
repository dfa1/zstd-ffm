package io.github.dfa1.zstd;

import static io.github.dfa1.zstd.ZstdTestSupport.segmentOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class RefDictionaryTest {

    private static final byte[] DICT_BYTES =
            "dictionary sample payload ".repeat(64).getBytes(StandardCharsets.UTF_8);
    private static final byte[] PAYLOAD =
            "hello world hello world hello world ".repeat(50).getBytes(StandardCharsets.UTF_8);

    @Test
    void compressContextSurvivesClosingAStillReferencedDictionary() {
        // Given a digested compress dictionary referenced by a context
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
        ZstdCompressDictionary cdict = new ZstdCompressDictionary(dict);
        ZstdCompressContext cctx = new ZstdCompressContext();
        cctx.refDictionary(cdict);

        // When the dictionary is closed while the context still references it
        cdict.close();
        byte[] frame = cctx.compress(PAYLOAD);
        cctx.close();

        // Then the frame still decodes correctly against the original dictionary
        try (ZstdDecompressContext dctx = new ZstdDecompressContext()) {
            byte[] restored = dctx.decompress(frame, new ZstdByteSize(PAYLOAD.length), dict);
            assertThat(restored).isEqualTo(PAYLOAD);
        }
    }

    @Test
    void decompressContextSurvivesClosingAStillReferencedDictionary() {
        // Given a frame compressed against a dictionary, and a digested decompress
        // dictionary referenced by a context
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
        byte[] frame;
        try (ZstdCompressContext cctx = new ZstdCompressContext()) {
            frame = cctx.compress(PAYLOAD, dict);
        }
        ZstdDecompressDictionary ddict = new ZstdDecompressDictionary(dict);
        ZstdDecompressContext dctx = new ZstdDecompressContext();
        dctx.refDictionary(ddict);

        // When the dictionary is closed while the context still references it
        ddict.close();
        byte[] restored = dctx.decompress(frame, new ZstdByteSize(PAYLOAD.length));
        dctx.close();

        // Then decompression still succeeds
        assertThat(restored).isEqualTo(PAYLOAD);
    }

    @Test
    void replacingAReferencedCompressDictionaryReleasesThePreviousOne() {
        // Given a context referencing a first dictionary
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
        ZstdCompressDictionary first = new ZstdCompressDictionary(dict);
        ZstdCompressDictionary second = new ZstdCompressDictionary(dict);
        try (ZstdCompressContext cctx = new ZstdCompressContext()) {
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
        ZstdCompressDictionary cdict = new ZstdCompressDictionary(dict);
        try (ZstdCompressContext cctx = new ZstdCompressContext()) {
            cctx.refDictionary(cdict);

            // When the reference is cleared and the dictionary closed
            cctx.refDictionary(null);
            cdict.close();
            byte[] frame = cctx.compress(PAYLOAD);

            // Then compression succeeds plainly, with no dictionary applied
            byte[] restored;
            try (ZstdDecompressContext dctx = new ZstdDecompressContext()) {
                restored = dctx.decompress(frame);
            }
            assertThat(restored).isEqualTo(PAYLOAD);
        }
    }

    @Test
    void resettingParametersReleasesAReferencedCompressDictionary() {
        // Given a context referencing a dictionary
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
        ZstdCompressDictionary cdict = new ZstdCompressDictionary(dict);
        try (ZstdCompressContext cctx = new ZstdCompressContext()) {
            cctx.refDictionary(cdict);

            // When the context is parameter-reset and the dictionary closed
            cctx.reset(ZstdResetDirective.SESSION_AND_PARAMETERS);
            cdict.close();

            // Then the context compresses plainly, with no dangling reference used
            byte[] frame = cctx.compress(PAYLOAD);
            byte[] restored;
            try (ZstdDecompressContext dctx = new ZstdDecompressContext()) {
                restored = dctx.decompress(frame);
            }
            assertThat(restored).isEqualTo(PAYLOAD);
        }
    }

    @Test
    void loadDictionaryReleasesAPreviouslyReferencedCompressDictionary() {
        // Given a context referencing a dictionary
        ZstdDictionary dict = ZstdDictionary.of(DICT_BYTES);
        ZstdCompressDictionary cdict = new ZstdCompressDictionary(dict);
        try (ZstdCompressContext cctx = new ZstdCompressContext()) {
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
        ZstdCompressDictionary cdict = new ZstdCompressDictionary(dict);
        try (Arena arena = Arena.ofConfined();
             ZstdCompressContext cctx = new ZstdCompressContext()) {
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
        ZstdDecompressDictionary ddict = new ZstdDecompressDictionary(dict);
        try (ZstdDecompressContext dctx = new ZstdDecompressContext()) {
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
        ZstdDecompressDictionary ddict = new ZstdDecompressDictionary(dict);
        try (Arena arena = Arena.ofConfined();
             ZstdDecompressContext dctx = new ZstdDecompressContext()) {
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
        ZstdCompressDictionary cdict = new ZstdCompressDictionary(dict);
        ZstdCompressContext cctx = new ZstdCompressContext();
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
        ZstdDecompressDictionary ddict = new ZstdDecompressDictionary(dict);
        ZstdDecompressContext dctx = new ZstdDecompressContext();
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
        try (ZstdCompressDictionary cdict = new ZstdCompressDictionary(dict);
             ZstdDecompressDictionary ddict = new ZstdDecompressDictionary(dict);
             ZstdCompressContext cctx = new ZstdCompressContext();
             ZstdDecompressContext dctx = new ZstdDecompressContext()) {

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
}
