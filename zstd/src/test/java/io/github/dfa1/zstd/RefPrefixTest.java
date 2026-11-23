package io.github.dfa1.zstd;

import static io.github.dfa1.zstd.ZstdTestSupport.bytesOf;
import static io.github.dfa1.zstd.ZstdTestSupport.randomBytes;
import static io.github.dfa1.zstd.ZstdTestSupport.segmentOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class RefPrefixTest {

    @Test
    void roundTripsWithMatchingPrefix() {
        // Given
        byte[] prefixBytes = "the quick brown fox jumps over the lazy dog".getBytes();
        byte[] dataBytes = "the quick brown fox jumps over the lazy cat".getBytes();
        try (Arena arena = Arena.ofConfined();
             ZstdCompressContext cctx = Zstd.newCompressContext();
             ZstdDecompressContext dctx = Zstd.newDecompressContext()) {
            MemorySegment prefix = segmentOf(arena, prefixBytes);
            MemorySegment src = segmentOf(arena, dataBytes);
            MemorySegment frame = arena.allocate(Zstd.compressBound(ZstdByteSize.of(dataBytes.length)).value());
            MemorySegment out = arena.allocate(dataBytes.length);

            // When
            cctx.refPrefix(prefix);
            long n = cctx.compress(frame, src);
            dctx.refPrefix(prefix);
            long m = dctx.decompress(out, frame.asSlice(0, n));

            // Then
            assertThat(m).isEqualTo(dataBytes.length);
            assertThat(bytesOf(out, (int) m)).isEqualTo(dataBytes);
        }
    }

    @Test
    void prefixIsAppliedAndRequiredToDecode() {
        // Given — incompressible (random) data identical to the prefix. With no
        // internal redundancy the encoder can only shrink it by matching against
        // the prefix, so a much smaller frame proves the prefix is applied.
        // (Level 19: the level-3 match finder is too weak to reach into the prefix
        // for random content.)
        byte[] random = randomBytes(0xBEEF, 16384);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment prefix = segmentOf(arena, random);
            MemorySegment src = segmentOf(arena, random);

            long baseline;
            try (ZstdCompressContext cctx = Zstd.newCompressContext().level(ZstdCompressionLevel.of(19))) {
                baseline = cctx.compress(arena, src).byteSize();
            }
            byte[] frame;
            try (ZstdCompressContext cctx = Zstd.newCompressContext().level(ZstdCompressionLevel.of(19))) {
                cctx.refPrefix(prefix);
                MemorySegment f = cctx.compress(arena, src);
                frame = bytesOf(f, (int) f.byteSize());
            }

            // Then — the prefix slashes the frame (16 KiB random → tens of bytes)
            assertThat((long) frame.length).isLessThan(baseline / 10);

            // And — it round-trips with the same prefix
            try (ZstdDecompressContext dctx = Zstd.newDecompressContext()) {
                MemorySegment out = arena.allocate(random.length);
                dctx.refPrefix(prefix);
                long m = dctx.decompress(out, segmentOf(arena, frame));
                assertThat(bytesOf(out, (int) m)).isEqualTo(random);
            }

            // But — it cannot be recovered without the prefix
            boolean reproduced;
            try (ZstdDecompressContext dctx = Zstd.newDecompressContext()) {
                MemorySegment out = arena.allocate(random.length);
                long m = dctx.decompress(out, segmentOf(arena, frame));
                reproduced = Arrays.equals(bytesOf(out, (int) m), random);
            } catch (ZstdException _) {
                reproduced = false;
            }
            assertThat(reproduced).isFalse();
        }
    }

    @Test
    void clearingPrefixWithNullCompressesPlainly() {
        // Given
        byte[] dataBytes = "the quick brown fox".getBytes();
        try (Arena arena = Arena.ofConfined();
             ZstdCompressContext cctx = Zstd.newCompressContext()) {
            MemorySegment prefix = segmentOf(arena, "a prior version of the text".getBytes());
            MemorySegment src = segmentOf(arena, dataBytes);
            MemorySegment frame = arena.allocate(Zstd.compressBound(ZstdByteSize.of(dataBytes.length)).value());

            // When — set then clear the prefix before compressing
            cctx.refPrefix(prefix);
            cctx.refPrefix(null);
            long n = cctx.compress(frame, src);

            // Then — a plain decoder with no prefix decodes it
            byte[] restored;
            try (ZstdDecompressContext dctx = Zstd.newDecompressContext()) {
                MemorySegment out = arena.allocate(dataBytes.length);
                long m = dctx.decompress(out, frame.asSlice(0, n));
                restored = bytesOf(out, (int) m);
            }
            assertThat(restored).isEqualTo(dataBytes);
        }
    }

    @Test
    void prefixIsSingleUseAndDoesNotStickAcrossFrames() {
        // Given — random data identical to the prefix (see prefixIsAppliedAndRequiredToDecode),
        // and one context kept open across two compressions with the prefix set once.
        byte[] random = randomBytes(0xCAFE, 16384);
        try (Arena arena = Arena.ofConfined();
             ZstdCompressContext cctx = Zstd.newCompressContext().level(ZstdCompressionLevel.of(19))) {
            MemorySegment prefix = segmentOf(arena, random);
            MemorySegment src = segmentOf(arena, random);
            MemorySegment first = arena.allocate(Zstd.compressBound(ZstdByteSize.of(random.length)).value());
            MemorySegment second = arena.allocate(Zstd.compressBound(ZstdByteSize.of(random.length)).value());

            // When — first frame consumes the prefix; second is compressed with no re-set
            cctx.refPrefix(prefix);
            long n1 = cctx.compress(first, src);
            long n2 = cctx.compress(second, src);

            // Then — the prefix shrank only the first frame; the second got no prefix
            // (incompressible random with no prefix stays ~full size)
            assertThat(n1).isLessThan(n2 / 10);

            // And — the second frame carries no prefix, so a plain decoder decodes it
            byte[] restored;
            try (ZstdDecompressContext dctx = Zstd.newDecompressContext()) {
                MemorySegment out = arena.allocate(random.length);
                long m = dctx.decompress(out, second.asSlice(0, n2));
                restored = bytesOf(out, (int) m);
            }
            assertThat(restored).isEqualTo(random);
        }
    }

    @Test
    void compressRefPrefixRejectsAHeapSegment() {
        // Given
        MemorySegment heap = MemorySegment.ofArray("prefix".getBytes());

        // When
        ThrowingCallable result = () -> {
            try (ZstdCompressContext cctx = Zstd.newCompressContext()) {
                cctx.refPrefix(heap);
            }
        };

        // Then
        assertThatThrownBy(result)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prefix");
    }

    @Test
    void decompressRefPrefixRejectsAHeapSegment() {
        // Given
        MemorySegment heap = MemorySegment.ofArray("prefix".getBytes());

        // When
        ThrowingCallable result = () -> {
            try (ZstdDecompressContext dctx = Zstd.newDecompressContext()) {
                dctx.refPrefix(heap);
            }
        };

        // Then
        assertThatThrownBy(result)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prefix");
    }

    @Test
    void compressLoadDictionaryRejectsAHeapSegment() {
        // Given a heap-backed segment
        MemorySegment heap = MemorySegment.ofArray("dictionary".getBytes());

        // When loaded as a zero-copy dictionary
        ThrowingCallable result = () -> {
            try (ZstdCompressContext cctx = Zstd.newCompressContext()) {
                cctx.loadDictionary(heap);
            }
        };

        // Then it is rejected naming the offending argument
        assertThatThrownBy(result)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dict");
    }

    @Test
    void decompressLoadDictionaryRejectsAHeapSegment() {
        // Given a heap-backed segment
        MemorySegment heap = MemorySegment.ofArray("dictionary".getBytes());

        // When loaded as a zero-copy dictionary
        ThrowingCallable result = () -> {
            try (ZstdDecompressContext dctx = Zstd.newDecompressContext()) {
                dctx.loadDictionary(heap);
            }
        };

        // Then it is rejected naming the offending argument
        assertThatThrownBy(result)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dict");
    }

    @Test
    void refPrefixReturnsTheSameContextForChaining() {
        // Given both contexts and a native prefix
        try (Arena arena = Arena.ofConfined();
             ZstdCompressContext cctx = Zstd.newCompressContext();
             ZstdDecompressContext dctx = Zstd.newDecompressContext()) {
            MemorySegment prefix = segmentOf(arena, "a prior version".getBytes());

            // When setting and then clearing a prefix on both contexts
            ZstdCompressContext cSet = cctx.refPrefix(prefix);
            ZstdCompressContext cCleared = cctx.refPrefix(null);
            ZstdDecompressContext dSet = dctx.refPrefix(prefix);
            ZstdDecompressContext dCleared = dctx.refPrefix(null);

            // Then every call returns the same instance, for chaining
            assertThat(cSet).isSameAs(cctx);
            assertThat(cCleared).isSameAs(cctx);
            assertThat(dSet).isSameAs(dctx);
            assertThat(dCleared).isSameAs(dctx);
        }
    }

    @Test
    void loadDictionaryFromSegmentReturnsTheSameContextForChaining() {
        // Given both contexts and a native dictionary segment
        try (Arena arena = Arena.ofConfined();
             ZstdCompressContext cctx = Zstd.newCompressContext();
             ZstdDecompressContext dctx = Zstd.newDecompressContext()) {
            MemorySegment dict = segmentOf(arena, "dictionary sample payload ".repeat(64).getBytes());

            // When loading and then clearing a segment dictionary on both contexts
            ZstdCompressContext cSet = cctx.loadDictionary(dict);
            ZstdCompressContext cCleared = cctx.loadDictionary(MemorySegment.NULL);
            ZstdDecompressContext dSet = dctx.loadDictionary(dict);
            ZstdDecompressContext dCleared = dctx.loadDictionary(MemorySegment.NULL);

            // Then every call returns the same instance, for chaining
            assertThat(cSet).isSameAs(cctx);
            assertThat(cCleared).isSameAs(cctx);
            assertThat(dSet).isSameAs(dctx);
            assertThat(dCleared).isSameAs(dctx);
        }
    }

}
