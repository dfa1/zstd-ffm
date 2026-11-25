package io.github.dfa1.zstd;

import static io.github.dfa1.zstd.ZstdTestSupport.bytesOf;
import static io.github.dfa1.zstd.ZstdTestSupport.randomBytes;
import static io.github.dfa1.zstd.ZstdTestSupport.segmentOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RefPrefixTest {

    /// zstd keeps only the raw address of a referenced prefix, so closing the
    /// prefix's arena before a frame uses it must fail fast rather than let zstd
    /// read freed memory (issue #162).
    @Nested
    class ClosedPrefix {

        private static final byte[] PREFIX = randomBytes(0x162, 4096);
        private static final byte[] DATA = randomBytes(0x163, 4096);

        @Test
        void compressBytesThrowsWhenThePrefixWasClosed() {
            // Given a context whose pending prefix's arena is closed
            try (ZstdCompressContext sut = Zstd.newCompressContext()) {
                referenceThenClose(sut::refPrefix);

                // When compressing
                ThrowingCallable result = () -> sut.compress(DATA);

                // Then it fails fast instead of reading freed memory
                assertThatThrownBy(result)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("refPrefix");
            }
        }

        @Test
        void compressSegmentThrowsWhenThePrefixWasClosed() {
            // Given a context whose pending prefix's arena is closed
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressContext sut = Zstd.newCompressContext()) {
                referenceThenClose(sut::refPrefix);
                MemorySegment src = segmentOf(arena, DATA);
                MemorySegment dst = arena.allocate(Zstd.compressBound(ZstdByteSize.of(DATA.length)).value());

                // When compressing segment to segment
                ThrowingCallable result = () -> sut.compress(dst, src);

                // Then it fails fast
                assertThatThrownBy(result).isInstanceOf(IllegalStateException.class);
            }
        }

        @Test
        void compressDropsTheClosedPrefixAndKeepsWorking() {
            // Given a context that rejected a closed prefix
            try (ZstdCompressContext sut = Zstd.newCompressContext()) {
                referenceThenClose(sut::refPrefix);
                ThrowingCallable rejected = () -> sut.compress(DATA);
                assertThatThrownBy(rejected).isInstanceOf(IllegalStateException.class);

                // When compressing again
                byte[] frame = sut.compress(DATA);

                // Then the frame needs no prefix: the dead one was dropped
                assertThat(Zstd.decompress(frame)).isEqualTo(DATA);
            }
        }

        @Test
        void perCallDictionaryCompressLeavesThePrefixPending() {
            // Given a prefix, then a frame compressed with a per-call dictionary,
            // which does not use the prefix
            try (ZstdCompressContext sut = Zstd.newCompressContext()) {
                referenceThenClose(prefix -> sut.refPrefix(prefix).compress(DATA, ZstdDictionary.of(PREFIX)));

                // When the next frame would use the still-pending prefix
                ThrowingCallable result = () -> sut.compress(DATA);

                // Then the closed prefix is caught
                assertThatThrownBy(result).isInstanceOf(IllegalStateException.class);
            }
        }

        @Test
        void sessionResetKeepsThePrefixPending() {
            // Given a pending prefix kept by a session-only reset
            try (ZstdCompressContext sut = Zstd.newCompressContext()) {
                referenceThenClose(prefix -> sut.refPrefix(prefix).reset(ZstdResetDirective.SESSION_ONLY));

                // When compressing
                ThrowingCallable result = () -> sut.compress(DATA);

                // Then the closed prefix is caught
                assertThatThrownBy(result).isInstanceOf(IllegalStateException.class);
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"used by a frame", "parameter reset", "cleared with null",
                "loadDictionary(null)", "refDictionary(null)"})
        void compressForgetsAPrefixThatIsGone(String how) {
            // Given a prefix that zstd no longer holds, whose arena is then closed
            try (ZstdCompressContext sut = Zstd.newCompressContext()) {
                referenceThenClose(prefix -> {
                    sut.refPrefix(prefix);
                    switch (how) {
                        case "used by a frame" -> sut.compress(DATA);
                        case "parameter reset" -> sut.reset(ZstdResetDirective.PARAMETERS);
                        case "cleared with null" -> sut.refPrefix(null);
                        case "loadDictionary(null)" -> sut.loadDictionary((ZstdDictionary) null);
                        case "refDictionary(null)" -> sut.refDictionary(null);
                        default -> throw new IllegalArgumentException(how);
                    }
                });

                // When compressing again
                byte[] frame = sut.compress(DATA);

                // Then nothing is rejected and the frame needs no prefix
                assertThat(Zstd.decompress(frame)).isEqualTo(DATA);
            }
        }

        @Test
        void decompressBytesThrowsWhenThePrefixWasClosed() {
            // Given a decoder whose pending prefix's arena is closed
            byte[] frame = Zstd.compress(DATA);
            try (ZstdDecompressContext sut = Zstd.newDecompressContext()) {
                referenceThenClose(sut::refPrefix);

                // When decoding
                ThrowingCallable result = () -> sut.decompress(frame, ZstdByteSize.of(DATA.length));

                // Then it fails fast instead of reading freed memory
                assertThatThrownBy(result)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("refPrefix");
            }
        }

        @Test
        void decompressSegmentThrowsWhenThePrefixWasClosed() {
            // Given a decoder whose pending prefix's arena is closed
            try (Arena arena = Arena.ofConfined();
                 ZstdDecompressContext sut = Zstd.newDecompressContext()) {
                referenceThenClose(sut::refPrefix);
                MemorySegment frame = segmentOf(arena, Zstd.compress(DATA));
                MemorySegment dst = arena.allocate(DATA.length);

                // When decoding segment to segment
                ThrowingCallable result = () -> sut.decompress(dst, frame);

                // Then it fails fast
                assertThatThrownBy(result).isInstanceOf(IllegalStateException.class);
            }
        }

        @Test
        void decompressDropsTheClosedPrefixAndKeepsWorking() {
            // Given a decoder that rejected a closed prefix
            byte[] frame = Zstd.compress(DATA);
            try (ZstdDecompressContext sut = Zstd.newDecompressContext()) {
                referenceThenClose(sut::refPrefix);
                ThrowingCallable rejected = () -> sut.decompress(frame, ZstdByteSize.of(DATA.length));
                assertThatThrownBy(rejected).isInstanceOf(IllegalStateException.class);

                // When decoding again
                byte[] restored = sut.decompress(frame, ZstdByteSize.of(DATA.length));

                // Then it decodes, with the dead prefix dropped
                assertThat(restored).isEqualTo(DATA);
            }
        }

        @Test
        void perCallDictionaryDecompressLeavesThePrefixPending() {
            // Given a prefix, then a frame decoded with a per-call dictionary,
            // which does not use the prefix
            ZstdDictionary dict = ZstdDictionary.of(PREFIX);
            byte[] dictFrame;
            try (ZstdCompressContext cctx = Zstd.newCompressContext()) {
                dictFrame = cctx.compress(DATA, dict);
            }
            byte[] plainFrame = Zstd.compress(DATA);
            try (ZstdDecompressContext sut = Zstd.newDecompressContext()) {
                referenceThenClose(prefix -> sut.refPrefix(prefix).decompress(dictFrame, ZstdByteSize.of(DATA.length), dict));

                // When the next frame would use the still-pending prefix
                ThrowingCallable result = () -> sut.decompress(plainFrame, ZstdByteSize.of(DATA.length));

                // Then the closed prefix is caught
                assertThatThrownBy(result).isInstanceOf(IllegalStateException.class);
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {"used by a frame", "parameter reset", "cleared with null",
                "loadDictionary(null)", "refDictionary(null)"})
        void decompressForgetsAPrefixThatIsGone(String how) {
            // Given a prefix that zstd no longer holds, whose arena is then closed
            byte[] prefixedFrame = compressWithPrefix();
            byte[] plainFrame = Zstd.compress(DATA);
            try (ZstdDecompressContext sut = Zstd.newDecompressContext()) {
                referenceThenClose(prefix -> {
                    sut.refPrefix(prefix);
                    switch (how) {
                        case "used by a frame" -> sut.decompress(prefixedFrame, ZstdByteSize.of(PREFIX.length));
                        case "parameter reset" -> sut.reset(ZstdResetDirective.PARAMETERS);
                        case "cleared with null" -> sut.refPrefix(null);
                        case "loadDictionary(null)" -> sut.loadDictionary((ZstdDictionary) null);
                        case "refDictionary(null)" -> sut.refDictionary(null);
                        default -> throw new IllegalArgumentException(how);
                    }
                });

                // When decoding the next frame
                byte[] restored = sut.decompress(plainFrame, ZstdByteSize.of(DATA.length));

                // Then nothing is rejected
                assertThat(restored).isEqualTo(DATA);
            }
        }

        @Test
        void compressRefusedByTheLinkerStillUsesUpThePrefix() {
            // Given a pending prefix and a source segment whose arena is closed
            try (Arena prefixArena = Arena.ofConfined();
                 ZstdCompressContext sut = Zstd.newCompressContext()) {
                sut.level(ZstdCompressionLevel.of(19));
                sut.refPrefix(segmentOf(prefixArena, PREFIX));
                MemorySegment closedSrc = closedSegment();
                MemorySegment dst = prefixArena.allocate(Zstd.compressBound(ZstdByteSize.of(DATA.length)).value());
                ThrowingCallable refused = () -> sut.compress(dst, closedSrc);
                assertThatThrownBy(refused).isInstanceOf(IllegalStateException.class);

                // When compressing the prefix's own content as the next frame: a
                // prefix zstd still held would shrink it to a few bytes that cannot
                // be decoded without it
                byte[] frame = sut.compress(PREFIX);

                // Then zstd no longer holds the prefix: the frame decodes without it
                assertThat(Zstd.decompress(frame)).isEqualTo(PREFIX);
            }
        }

        @Test
        void decompressRefusedByTheLinkerStillUsesUpThePrefix() {
            // Given a pending prefix and a source segment whose arena is closed
            byte[] prefixedFrame = compressWithPrefix();
            try (Arena prefixArena = Arena.ofConfined();
                 ZstdDecompressContext sut = Zstd.newDecompressContext()) {
                sut.refPrefix(segmentOf(prefixArena, PREFIX));
                MemorySegment closedSrc = closedSegment();
                MemorySegment dst = prefixArena.allocate(DATA.length);
                ThrowingCallable refused = () -> sut.decompress(dst, closedSrc);
                assertThatThrownBy(refused).isInstanceOf(IllegalStateException.class);

                // When decoding a frame that needs the prefix, without referencing it again
                ThrowingCallable result = () -> sut.decompress(prefixedFrame, ZstdByteSize.of(PREFIX.length));

                // Then zstd no longer holds the prefix, so the frame cannot be decoded
                assertThatThrownBy(result).isInstanceOf(ZstdException.class);
            }
        }

        @Test
        void refPrefixRejectsAPrefixConfinedToAnotherThread() throws Exception {
            // Given a prefix segment confined to another thread
            try (ExecutorService owner = Executors.newSingleThreadExecutor();
                 ZstdCompressContext sut = Zstd.newCompressContext()) {
                Arena foreignArena = owner.submit(Arena::ofConfined).get();
                MemorySegment foreign = owner.submit(() -> segmentOf(foreignArena, PREFIX)).get();
                try {
                    // When referencing it from this thread
                    ThrowingCallable result = () -> sut.refPrefix(foreign);

                    // Then it is refused, as the linker would refuse it as a downcall argument
                    assertThatThrownBy(result).isInstanceOf(WrongThreadException.class);
                } finally {
                    owner.submit(foreignArena::close).get();
                }
            }
        }

        @Test
        void refPrefixRejectsAClosedPrefix() {
            // Given a prefix segment whose arena is already closed
            MemorySegment closed = closedSegment();
            try (ZstdDecompressContext sut = Zstd.newDecompressContext()) {
                // When referencing it
                ThrowingCallable result = () -> sut.refPrefix(closed);

                // Then it is refused up front
                assertThatThrownBy(result).isInstanceOf(IllegalStateException.class);
            }
        }

        /// A frame of [#PREFIX] compressed against itself as a prefix, so decoding it
        /// genuinely needs the prefix: random content has nothing else to match, and
        /// level 19 is strong enough to reach into the prefix. Decodes to
        /// `PREFIX.length` bytes.
        private static byte[] compressWithPrefix() {
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressContext cctx = Zstd.newCompressContext()) {
                cctx.level(ZstdCompressionLevel.of(19));
                MemorySegment frame = cctx.refPrefix(segmentOf(arena, PREFIX)).compress(arena, segmentOf(arena, PREFIX));
                return bytesOf(frame, frame.byteSize());
            }
        }

        /// A native copy of [#DATA] whose arena is already closed.
        private static MemorySegment closedSegment() {
            Arena arena = Arena.ofConfined();
            MemorySegment seg = segmentOf(arena, DATA);
            arena.close();
            return seg;
        }

        /// Hands `use` a native copy of [#PREFIX], then closes its arena.
        private static void referenceThenClose(Consumer<MemorySegment> use) {
            Arena prefixArena = Arena.ofConfined();
            use.accept(segmentOf(prefixArena, PREFIX));
            prefixArena.close();
        }
    }

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
            try (ZstdCompressContext cctx = Zstd.newCompressContext()) {
                cctx.level(ZstdCompressionLevel.of(19));
                baseline = cctx.compress(arena, src).byteSize();
            }
            byte[] frame;
            try (ZstdCompressContext cctx = Zstd.newCompressContext()) {
                cctx.level(ZstdCompressionLevel.of(19));
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
             ZstdCompressContext cctx = Zstd.newCompressContext()) {
            cctx.level(ZstdCompressionLevel.of(19));
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
