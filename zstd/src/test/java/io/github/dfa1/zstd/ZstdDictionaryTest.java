package io.github.dfa1.zstd;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static io.github.dfa1.zstd.ZstdTestSupport.sample;
import static io.github.dfa1.zstd.ZstdTestSupport.segmentOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ZstdDictionaryTest {

    private List<byte[]> samples;
    private ZstdDictionary sut;

    @BeforeAll
    void trainDictionary() {
        // Given many small, structurally-similar records — the case dictionaries win on
        samples = new ArrayList<>();
        for (int i = 0; i < 4000; i++) {
            samples.add(sample(i));
        }
        sut = ZstdDictionary.train(samples, ZstdByteSize.ofKiB(16));
    }

    @Nested
    class CoverTraining {

        @Test
        void fastCoverRoundTrips() {
            // Given a fast-COVER-trained dictionary
            ZstdDictionary dict = ZstdDictionary.trainFastCover(samples, ZstdByteSize.ofKiB(16));
            assertThat(dict.size()).isGreaterThan(0);

            // Then records round-trip and compress smaller than dictionaryless
            byte[] sample = samples.get(321);
            byte[] plain;
            byte[] withDict;
            byte[] restored;
            try (ZstdCompressContext cctx = new ZstdCompressContext();
                 ZstdDecompressContext dctx = new ZstdDecompressContext()) {
                plain = cctx.compress(sample);
                withDict = cctx.compress(sample, dict);
                restored = dctx.decompress(cctx.compress(sample, dict), new ZstdByteSize(sample.length), dict);
            }
            assertThat(withDict).hasSizeLessThan(plain.length);
            assertThat(restored).isEqualTo(sample);
        }

        @Test
        void coverRoundTrips() {
            // COVER is slower, so train on a subset to keep the test quick
            ZstdDictionary dict = ZstdDictionary.trainCover(samples.subList(0, 1000), ZstdByteSize.ofKiB(8));
            assertThat(dict.size()).isGreaterThan(0);

            byte[] sample = samples.get(5);
            try (ZstdCompressContext cctx = new ZstdCompressContext();
                 ZstdDecompressContext dctx = new ZstdDecompressContext()) {
                byte[] frame = cctx.compress(sample, dict);
                assertThat(dctx.decompress(frame, new ZstdByteSize(sample.length), dict)).isEqualTo(sample);
            }
        }

        @Test
        void coverFailsWithoutSamples() {
            // When COVER-training on no samples
            ThrowingCallable result = () -> ZstdDictionary.trainCover(List.of(), new ZstdByteSize(4096));

            // Then it fails fast with the empty-samples guard
            assertThatThrownBy(result)
                    .isInstanceOf(ZstdException.class)
                    .hasMessageContaining("zero samples");
        }

        @Test
        void fastCoverFailsWithoutSamples() {
            // When fast-COVER-training on no samples
            ThrowingCallable result = () -> ZstdDictionary.trainFastCover(List.of(), new ZstdByteSize(4096));

            // Then it fails fast with the empty-samples guard
            assertThatThrownBy(result)
                    .isInstanceOf(ZstdException.class)
                    .hasMessageContaining("zero samples");
        }
    }

    @Nested
    class Finalize {

        @Test
        void finalizesRawContentIntoUsableDictionary() {
            // Given some raw dictionary content plus tuning samples
            byte[] content = "{\"user\":\"\",\"event\":\"click\",\"id\":}".repeat(40)
                    .getBytes(StandardCharsets.UTF_8);

            ZstdDictionary dict =
                    ZstdDictionary.finalizeFrom(content, samples, ZstdByteSize.ofKiB(16), new ZstdCompressionLevel(0));

            // Then it carries a header and round-trips a sample
            assertThat(dict.size()).isGreaterThan(0);
            assertThat(dict.headerSize()).isPositive();

            byte[] sample = samples.get(3);
            try (ZstdCompressContext cctx = new ZstdCompressContext();
                 ZstdDecompressContext dctx = new ZstdDecompressContext()) {
                byte[] frame = cctx.compress(sample, dict);
                assertThat(dctx.decompress(frame, new ZstdByteSize(sample.length), dict)).isEqualTo(sample);
            }
        }

        @Test
        void trainedDictionaryHasHeader() {
            assertThat(sut.headerSize()).isPositive();
            assertThat(sut.headerSize()).isLessThanOrEqualTo(sut.size());
        }

        @Test
        void finalizeFailsWithoutSamples() {
            // When finalizing raw content with no tuning samples
            ThrowingCallable result =
                    () -> ZstdDictionary.finalizeFrom(new byte[]{1, 2, 3}, List.of(), new ZstdByteSize(4096), new ZstdCompressionLevel(0));

            // Then it fails fast with the empty-samples guard
            assertThatThrownBy(result)
                    .isInstanceOf(ZstdException.class)
                    .hasMessageContaining("zero samples");
        }
    }

    @Nested
    class Training {

        @Test
        void producesNonEmptyDictionary() {
            // Then the trained dictionary has content matching its reported size
            assertThat(sut.size()).isGreaterThan(0);
            assertThat(sut.toByteArray()).hasSize(sut.size());
        }

        @Test
        void beatsDictionarylessOnTinyPayload() {
            // Given a single small sample
            byte[] sample = samples.get(123);

            // When compressed with and without the dictionary
            byte[] plain;
            byte[] withDict;
            try (ZstdCompressContext ctx = new ZstdCompressContext()) {
                plain = ctx.compress(sample);
                withDict = ctx.compress(sample, sut);
            }

            // Then the dictionary compresses the tiny sample noticeably better
            assertThat(withDict).hasSizeLessThan(plain.length);
        }

        @Test
        void failsWithoutSamples() {
            // When training on no samples
            ThrowingCallable result = () -> ZstdDictionary.train(List.of(), new ZstdByteSize(4096));

            // Then it fails fast with the empty-samples guard, before reaching ZDICT
            assertThatThrownBy(result)
                    .isInstanceOf(ZstdException.class)
                    .hasMessageContaining("zero samples");
        }

        @Test
        void surfacesTheNativeErrorNameWhenTrainingFails() {
            // Given samples that pass the empty-check but are far too little for ZDICT
            List<byte[]> tooFew = List.of(new byte[]{1, 2, 3});

            // When training
            ThrowingCallable result = () -> ZstdDictionary.train(tooFew, new ZstdByteSize(112_640));

            // Then the failure carries the native ZDICT error name, not an empty string
            assertThatThrownBy(result)
                    .isInstanceOf(ZstdException.class)
                    .hasMessageMatching("dictionary training failed: .+");
        }
    }

    @Nested
    class RawDictionary {

        @ParameterizedTest
        @ValueSource(ints = {0, 7, 123, 2048, 3999})
        void roundTripsRecord(int index) {
            // Given a sample
            byte[] sample = samples.get(index);

            // When compressed and decompressed against the raw dictionary
            byte[] restored;
            try (ZstdCompressContext cctx = new ZstdCompressContext();
                 ZstdDecompressContext dctx = new ZstdDecompressContext()) {
                byte[] frame = cctx.compress(sample, sut);
                restored = dctx.decompress(frame, new ZstdByteSize(sample.length), sut);
            }

            // Then the sample is recovered
            assertThat(restored).isEqualTo(sample);
        }
    }

    @Nested
    class DigestedDictionary {

        @Test
        void roundTripsViaCDictAndDDict() {
            // Given digested compress/decompress dictionaries at a fixed level
            byte[] sample = samples.get(999);

            byte[] restored;
            ZstdCompressionLevel level;
            try (ZstdCompressContext cctx = new ZstdCompressContext();
                 ZstdDecompressContext dctx = new ZstdDecompressContext();
                 ZstdCompressDictionary cdict = new ZstdCompressDictionary(sut, new ZstdCompressionLevel(19));
                 ZstdDecompressDictionary ddict = new ZstdDecompressDictionary(sut)) {

                // When round-tripped through the digested dictionaries
                byte[] frame = cctx.compress(sample, cdict);
                restored = dctx.decompress(frame, new ZstdByteSize(sample.length), ddict);
                level = cdict.level();
            }

            // Then the sample is recovered at the requested level
            assertThat(restored).isEqualTo(sample);
            assertThat(level).isEqualTo(new ZstdCompressionLevel(19));
        }

        @Test
        void digestedDictionariesReportTheSameId() {
            try (ZstdCompressDictionary cdict = new ZstdCompressDictionary(sut);
                 ZstdDecompressDictionary ddict = new ZstdDecompressDictionary(sut)) {
                assertThat(cdict.id()).isEqualTo(sut.id());
                assertThat(ddict.id()).isEqualTo(sut.id());
            }
        }

        @Test
        void interoperatesWithRawPath() {
            // Given a sample compressed with the raw dictionary
            byte[] sample = samples.get(2048);

            byte[] restored;
            try (ZstdCompressContext cctx = new ZstdCompressContext();
                 ZstdDecompressContext dctx = new ZstdDecompressContext();
                 ZstdDecompressDictionary ddict = new ZstdDecompressDictionary(sut)) {
                byte[] frame = cctx.compress(sample, sut);

                // When decompressed with the digested dictionary
                restored = dctx.decompress(frame, new ZstdByteSize(sample.length), ddict);
            }

            // Then the two dictionary forms interoperate
            assertThat(restored).isEqualTo(sample);
        }
    }

    @Nested
    class Factories {

        @Test
        void compressDictFixesTheRequestedLevel() {
            // When a digested compress dictionary is built via the factory
            try (ZstdCompressDictionary cdict = sut.compressDict(new ZstdCompressionLevel(19))) {
                // Then it carries that level and the dictionary's id
                assertThat(cdict.level()).isEqualTo(new ZstdCompressionLevel(19));
                assertThat(cdict.id()).isEqualTo(sut.id());
            }
        }

        @Test
        void compressDictDefaultsToTheLibraryLevel() {
            // When built without a level
            try (ZstdCompressDictionary cdict = sut.compressDict()) {
                // Then it uses the library default
                assertThat(cdict.level()).isEqualTo(ZstdCompressionLevel.DEFAULT);
            }
        }

        @Test
        void decompressDictReportsTheDictionaryId() {
            // When a digested decompress dictionary is built via the factory
            try (ZstdDecompressDictionary ddict = sut.decompressDict()) {
                // Then it carries the dictionary's id
                assertThat(ddict.id()).isEqualTo(sut.id());
            }
        }

        @Test
        void factoryDictionariesRoundTrip() {
            // Given factory-built digested dictionaries
            byte[] sample = samples.get(123);

            byte[] restored;
            try (ZstdCompressContext cctx = new ZstdCompressContext();
                 ZstdDecompressContext dctx = new ZstdDecompressContext();
                 ZstdCompressDictionary cdict = sut.compressDict(new ZstdCompressionLevel(19));
                 ZstdDecompressDictionary ddict = sut.decompressDict()) {

                // When round-tripped through them
                byte[] frame = cctx.compress(sample, cdict);
                restored = dctx.decompress(frame, new ZstdByteSize(sample.length), ddict);
            }

            // Then the sample is recovered
            assertThat(restored).isEqualTo(sample);
        }
    }

    @Nested
    class Serialization {

        @Test
        void reloadedDictionaryKeepsIdentityAndDecodes() {
            // Given the dictionary serialized and reloaded
            ZstdDictionary reloaded = ZstdDictionary.of(sut.toByteArray());

            // Then it carries the same dictionary id
            assertThat(reloaded.id()).isEqualTo(sut.id());

            // And a frame from the reload decodes against the original
            byte[] sample = samples.get(1);
            byte[] restored;
            try (ZstdCompressContext cctx = new ZstdCompressContext();
                 ZstdDecompressContext dctx = new ZstdDecompressContext()) {
                byte[] frame = cctx.compress(sample, reloaded);
                restored = dctx.decompress(frame, new ZstdByteSize(sample.length), sut);
            }
            assertThat(restored).isEqualTo(sample);
        }
    }

    @Nested
    class SegmentDigestedDictionary {

        @Test
        void roundTripsViaSegmentBuiltCDictAndDDict() {
            // Given digested dictionaries built straight from native dictionary
            // segments (the zero-copy path — no heap byte[] bounce)
            byte[] sample = samples.get(999);
            byte[] raw = sut.toByteArray();

            byte[] restored;
            ZstdCompressionLevel level;
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressContext cctx = new ZstdCompressContext();
                 ZstdDecompressContext dctx = new ZstdDecompressContext();
                 ZstdCompressDictionary cdict =
                         new ZstdCompressDictionary(segmentOf(arena, raw), new ZstdCompressionLevel(19));
                 ZstdDecompressDictionary ddict = new ZstdDecompressDictionary(segmentOf(arena, raw))) {

                // When round-tripped through the segment-built dictionaries
                byte[] frame = cctx.compress(sample, cdict);
                restored = dctx.decompress(frame, new ZstdByteSize(sample.length), ddict);
                level = cdict.level();
            }

            // Then the sample is recovered at the requested level
            assertThat(restored).isEqualTo(sample);
            assertThat(level).isEqualTo(new ZstdCompressionLevel(19));
        }

        @Test
        void segmentBuiltDictionariesReportSameIdAsHeap() {
            // Given dictionaries digested from a native segment
            byte[] raw = sut.toByteArray();

            // Then they carry the same id as the heap-built dictionary
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressDictionary cdict = new ZstdCompressDictionary(segmentOf(arena, raw));
                 ZstdDecompressDictionary ddict = new ZstdDecompressDictionary(segmentOf(arena, raw))) {
                assertThat(cdict.id()).isEqualTo(sut.id());
                assertThat(ddict.id()).isEqualTo(sut.id());
            }
        }

        @Test
        void interoperatesWithHeapBuiltDictionaries() {
            // Given a frame compressed with a segment-built CDict
            byte[] sample = samples.get(2048);
            byte[] raw = sut.toByteArray();

            byte[] restored;
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressContext cctx = new ZstdCompressContext();
                 ZstdDecompressContext dctx = new ZstdDecompressContext();
                 ZstdCompressDictionary cdict = new ZstdCompressDictionary(segmentOf(arena, raw));
                 ZstdDecompressDictionary ddict = new ZstdDecompressDictionary(sut)) {
                byte[] frame = cctx.compress(sample, cdict);

                // When decompressed with a heap-built DDict
                restored = dctx.decompress(frame, new ZstdByteSize(sample.length), ddict);
            }

            // Then segment- and heap-built dictionaries interoperate
            assertThat(restored).isEqualTo(sample);
        }

        @Test
        void rejectsHeapDecompressDictSegment() {
            // Given a heap-backed dictionary segment
            MemorySegment heap = MemorySegment.ofArray(sut.toByteArray());

            // When digesting it as a decompress dictionary
            ThrowingCallable result = () -> new ZstdDecompressDictionary(heap);

            // Then it fails fast rather than dereferencing a heap address in C
            assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsHeapCompressDictSegment() {
            // Given a heap-backed dictionary segment
            MemorySegment heap = MemorySegment.ofArray(sut.toByteArray());

            // When digesting it as a compress dictionary
            ThrowingCallable result = () -> new ZstdCompressDictionary(heap);

            // Then it fails fast
            assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class StickyDictionary {

        @Test
        void loadedDictionaryCombinesWithAdvancedParameters() {
            // Given a context with both a loaded dictionary AND a checksum — the
            // combination the per-call compress(src, dict) overloads cannot give
            byte[] sample = samples.get(123);
            byte[] frame;
            try (ZstdCompressContext cctx = new ZstdCompressContext().checksum(true)) {
                cctx.loadDictionary(sut);
                frame = cctx.compress(sample);
            }
            byte[] plain;
            try (ZstdCompressContext ctx = new ZstdCompressContext()) {
                plain = ctx.compress(sample);
            }

            // Then the dictionary is honored (smaller than dictionaryless) and decodes
            assertThat(frame).hasSizeLessThan(plain.length);
            byte[] restored;
            try (ZstdDecompressContext dctx = new ZstdDecompressContext()) {
                dctx.loadDictionary(sut);
                restored = dctx.decompress(frame);
            }
            assertThat(restored).isEqualTo(sample);
        }

        @Test
        void referencedDigestedDictionarySurvivesSessionReset() {
            // Given a pooled context referencing a digested dictionary, recycled between frames
            byte[] first = samples.get(1);
            byte[] second = samples.get(2);
            byte[] restoredFirst;
            byte[] restoredSecond;
            try (ZstdCompressDictionary cdict = new ZstdCompressDictionary(sut, new ZstdCompressionLevel(19));
                 ZstdDecompressDictionary ddict = new ZstdDecompressDictionary(sut);
                 ZstdCompressContext cctx = new ZstdCompressContext();
                 ZstdDecompressContext dctx = new ZstdDecompressContext()) {
                cctx.refDictionary(cdict);
                byte[] frameFirst = cctx.compress(first);
                cctx.reset(ZstdResetDirective.SESSION_ONLY);
                byte[] frameSecond = cctx.compress(second);

                dctx.refDictionary(ddict);
                restoredFirst = dctx.decompress(frameFirst);
                dctx.reset(ZstdResetDirective.SESSION_ONLY);
                restoredSecond = dctx.decompress(frameSecond);
            }

            // Then both frames round-trip: the reference outlived the session reset
            assertThat(restoredFirst).isEqualTo(first);
            assertThat(restoredSecond).isEqualTo(second);
        }

        @Test
        void parameterResetClearsTheLoadedDictionary() {
            // Given a context that loaded a dictionary, then cleared its parameters
            byte[] sample = samples.get(7);
            byte[] afterReset;
            try (ZstdCompressContext cctx = new ZstdCompressContext()) {
                cctx.loadDictionary(sut);
                cctx.compress(sample);
                cctx.reset(ZstdResetDirective.SESSION_AND_PARAMETERS);
                afterReset = cctx.compress(sample);
            }
            byte[] noDict;
            try (ZstdCompressContext ctx = new ZstdCompressContext()) {
                noDict = ctx.compress(sample);
            }

            // Then the dictionary is gone: the frame matches a fresh dictionaryless one
            assertThat(afterReset).isEqualTo(noDict);
        }

        @Test
        void nullClearsTheLoadedDictionary() {
            // Given a context whose loaded dictionary is then cleared with null
            byte[] sample = samples.get(7);
            byte[] cleared;
            try (ZstdCompressContext cctx = new ZstdCompressContext()) {
                cctx.loadDictionary(sut);
                cctx.loadDictionary((ZstdDictionary) null);
                cleared = cctx.compress(sample);
            }
            byte[] noDict;
            try (ZstdCompressContext ctx = new ZstdCompressContext()) {
                noDict = ctx.compress(sample);
            }

            // Then it compresses as if no dictionary was ever loaded
            assertThat(cleared).isEqualTo(noDict);
        }

        @Test
        void loadsDictionaryFromNativeSegmentWithoutHeapCopy() {
            // Given a dictionary loaded straight from native segments (zero-copy path)
            byte[] sample = samples.get(2048);
            byte[] raw = sut.toByteArray();
            byte[] restored;
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressContext cctx = new ZstdCompressContext();
                 ZstdDecompressContext dctx = new ZstdDecompressContext()) {
                cctx.loadDictionary(segmentOf(arena, raw));
                byte[] frame = cctx.compress(sample);
                dctx.loadDictionary(segmentOf(arena, raw));
                restored = dctx.decompress(frame);
            }

            // Then the sample round-trips through the segment-loaded dictionary
            assertThat(restored).isEqualTo(sample);
        }

        @Test
        void rejectsHeapDictionarySegment() {
            // Given a heap-backed dictionary segment
            MemorySegment heap = MemorySegment.ofArray(sut.toByteArray());

            // When loaded into a context
            try (ZstdCompressContext cctx = new ZstdCompressContext()) {
                ThrowingCallable result = () -> cctx.loadDictionary(heap);

                // Then it fails fast rather than handing C a heap address
                assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        void nullNativeSegmentClearsTheLoadedDictionary() {
            // Given a context whose dictionary is cleared through the native overload
            byte[] sample = samples.get(7);
            byte[] cleared;
            try (ZstdCompressContext cctx = new ZstdCompressContext()) {
                cctx.loadDictionary(sut);
                cctx.loadDictionary((MemorySegment) null);
                cleared = cctx.compress(sample);
            }
            byte[] noDict;
            try (ZstdCompressContext ctx = new ZstdCompressContext()) {
                noDict = ctx.compress(sample);
            }

            // Then it compresses as if no dictionary was ever loaded
            assertThat(cleared).isEqualTo(noDict);
        }

        @Test
        void loadAndRefReturnTheSameCompressContext() {
            // Given a compress context and dictionaries to load and reference
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressContext cctx = new ZstdCompressContext();
                 ZstdCompressDictionary cdict = new ZstdCompressDictionary(sut, new ZstdCompressionLevel(19))) {

                // Then every sticky-dictionary call returns the same context, for chaining
                assertThat(cctx.loadDictionary(sut)).isSameAs(cctx);
                assertThat(cctx.loadDictionary(segmentOf(arena, sut.toByteArray()))).isSameAs(cctx);
                assertThat(cctx.loadDictionary((ZstdDictionary) null)).isSameAs(cctx);
                assertThat(cctx.refDictionary(cdict)).isSameAs(cctx);
                assertThat(cctx.refDictionary(null)).isSameAs(cctx);
            }
        }

        @Test
        void loadAndRefReturnTheSameDecompressContext() {
            // Given a decompress context and dictionaries to load and reference
            try (Arena arena = Arena.ofConfined();
                 ZstdDecompressContext dctx = new ZstdDecompressContext();
                 ZstdDecompressDictionary ddict = new ZstdDecompressDictionary(sut)) {

                // Then every sticky-dictionary call returns the same context, for chaining
                assertThat(dctx.loadDictionary(sut)).isSameAs(dctx);
                assertThat(dctx.loadDictionary(segmentOf(arena, sut.toByteArray()))).isSameAs(dctx);
                assertThat(dctx.loadDictionary((ZstdDictionary) null)).isSameAs(dctx);
                assertThat(dctx.refDictionary(ddict)).isSameAs(dctx);
                assertThat(dctx.refDictionary(null)).isSameAs(dctx);
            }
        }
    }

}
