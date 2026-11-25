package io.github.dfa1.zstd.it;

import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;
import io.github.dfa1.zstd.Zstd;
import io.github.dfa1.zstd.ZstdByteSize;
import io.github.dfa1.zstd.ZstdCompressContext;
import io.github.dfa1.zstd.ZstdCompressDictionary;
import io.github.dfa1.zstd.ZstdCompressStream;
import io.github.dfa1.zstd.ZstdCompressionLevel;
import io.github.dfa1.zstd.ZstdDecompressContext;
import io.github.dfa1.zstd.ZstdDecompressStream;
import io.github.dfa1.zstd.ZstdDictionary;
import io.github.dfa1.zstd.ZstdEndDirective;
import io.github.dfa1.zstd.ZstdFrame;
import io.github.dfa1.zstd.ZstdInputStream;
import io.github.dfa1.zstd.ZstdOutputStream;
import io.github.dfa1.zstd.ZstdStreamResult;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static io.github.dfa1.zstd.it.ItTestSupport.LEVELS;
import static io.github.dfa1.zstd.it.ItTestSupport.compressible;
import static io.github.dfa1.zstd.it.ItTestSupport.random;
import static org.assertj.core.api.Assertions.assertThat;

/// Format-compatibility tests: frames produced by the reference zstd-jni binding
/// must decode with zstd-ffm and vice versa. zstd-jni bundles a different zstd
/// version (1.5.x) than this library (1.6.0); the frame format is compatible, so
/// these prove real interop, not just internal round-trips.
class ZstdJniInteropTest {

    /// 2 MiB: above zstd's 512 KiB cutoff, below which a one-shot compress with a
    /// known source size runs single-threaded, and four jobs at the minimum job size.
    private static final int MULTI_THREAD_PAYLOAD_SIZE = 2 * 1024 * 1024;

    /// zstd's smallest job size (`ZSTDMT_JOBSIZE_MIN`, 512 KiB). The default
    /// (`4 << windowLog`, 8 MiB at level 3) would put the whole payload in one job.
    private static final int MIN_JOB_SIZE = 512 * 1024;

    static Stream<Arguments> payloadsAndLevels() {
        Random r = new Random(0xABCD);
        List<Arguments> cases = new ArrayList<>();
        int[] sizes = {0, 1, 1024, 64 * 1024, 1024 * 1024};
        for (int size : sizes) {
            byte[] data = size % 2 == 0 ? compressible(r, size) : random(r, size);
            for (int level : LEVELS) {
                cases.add(Arguments.of(level, data));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("payloadsAndLevels")
    void jniCompressJavaDecompress(int level, byte[] data) {
        byte[] frame = com.github.luben.zstd.Zstd.compress(data, level);
        assertThat(Zstd.decompress(frame, ZstdByteSize.of(data.length))).isEqualTo(data);
    }

    @ParameterizedTest
    @MethodSource("payloadsAndLevels")
    void javaCompressJniDecompress(int level, byte[] data) {
        byte[] frame = Zstd.compress(data, ZstdCompressionLevel.of(level));
        assertThat(com.github.luben.zstd.Zstd.decompress(frame, data.length)).isEqualTo(data);
    }

    @Nested
    class Streaming {

        @Test
        void javaStreamToJniStream() throws Exception {
            byte[] data = "interop streaming ".repeat(50_000).getBytes(StandardCharsets.UTF_8);

            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (ZstdOutputStream zout = Zstd.newOutputStream(sink)) {
                zout.level(ZstdCompressionLevel.of(7));
                zout.write(data);
            }
            byte[] restored;
            try (var zin = new com.github.luben.zstd.ZstdInputStream(
                    new ByteArrayInputStream(sink.toByteArray()))) {
                restored = zin.readAllBytes();
            }
            assertThat(restored).isEqualTo(data);
        }

        @Test
        void jniStreamToJavaStream() throws Exception {
            byte[] data = "interop streaming ".repeat(50_000).getBytes(StandardCharsets.UTF_8);

            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (var zout = new com.github.luben.zstd.ZstdOutputStream(sink)) {
                zout.write(data);
            }
            byte[] restored;
            try (ZstdInputStream zin = Zstd.newInputStream(new ByteArrayInputStream(sink.toByteArray()))) {
                restored = zin.readAllBytes();
            }
            assertThat(restored).isEqualTo(data);
        }
    }

    @Nested
    class SegmentStreaming {

        private static final ZstdDictionary DICT = trainDict();

        // Seeded payloads crossing zstd's 128 KiB block size, pushed through chunk
        // sizes small enough that every call consumes or produces only part of a block.
        static Stream<Arguments> payloadsAndChunks() {
            Random r = new Random(0x5E6);
            List<Arguments> cases = new ArrayList<>();
            int[] sizes = {0, 1, 4096, 300 * 1024};
            int[] chunks = {1, 7, 4096};
            for (int size : sizes) {
                byte[] data = size % 2 == 0 ? compressible(r, size) : random(r, size);
                for (int chunk : chunks) {
                    cases.add(Arguments.of(data, chunk));
                }
            }
            return cases.stream();
        }

        @ParameterizedTest
        @MethodSource("payloadsAndChunks")
        void segmentCompressStreamToJni(byte[] data, int chunk) {
            // Given a payload fed to ZstdCompressStream in chunk-sized segments
            byte[] frame;
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressStream sut = Zstd.newCompressStream()) {
                sut.level(ZstdCompressionLevel.of(5));
                // When it is compressed chunk by chunk and then ended
                frame = compressInChunks(arena, sut, data, chunk);
            }

            // Then zstd-jni decodes the frame back to the payload
            assertThat(com.github.luben.zstd.Zstd.decompress(frame, data.length)).isEqualTo(data);
        }

        @ParameterizedTest
        @MethodSource("payloadsAndChunks")
        void jniToSegmentDecompressStream(byte[] data, int chunk) {
            // Given a frame produced by zstd-jni
            byte[] frame = com.github.luben.zstd.Zstd.compress(data, 5);
            try (Arena arena = Arena.ofConfined();
                 ZstdDecompressStream sut = Zstd.newDecompressStream()) {
                // When ZstdDecompressStream decodes it through chunk-sized segments
                byte[] restored = decompressInChunks(arena, sut, frame, chunk);

                // Then the payload is restored exactly
                assertThat(restored).isEqualTo(data);
            }
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 7, 4096})
        void segmentCompressStreamWithDictionaryToJni(int chunk) {
            // Given records the dictionary was trained on, and a dictionary-loaded stream
            byte[] data = records(200);
            byte[] frame;
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressStream sut = Zstd.newCompressStream(DICT)) {
                // When they are compressed chunk by chunk and then ended
                frame = compressInChunks(arena, sut, data, chunk);
            }

            // Then the frame names the dictionary, and zstd-jni decodes it against the same one
            assertThat(ZstdFrame.dictId(frame)).isEqualTo(DICT.id());
            ZstdDictDecompress jniDict = new ZstdDictDecompress(DICT.toByteArray());
            assertThat(com.github.luben.zstd.Zstd.decompress(frame, jniDict, data.length)).isEqualTo(data);
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 7, 4096})
        void jniDictionaryToSegmentDecompressStream(int chunk) {
            // Given a frame zstd-jni compressed against the dictionary
            byte[] data = records(200);
            ZstdDictCompress jniDict = new ZstdDictCompress(DICT.toByteArray(), ZstdCompressionLevel.DEFAULT.value());
            byte[] frame = com.github.luben.zstd.Zstd.compress(data, jniDict);
            try (Arena arena = Arena.ofConfined();
                 ZstdDecompressStream sut = Zstd.newDecompressStream(DICT)) {
                // When a dictionary-loaded ZstdDecompressStream decodes it chunk by chunk
                byte[] restored = decompressInChunks(arena, sut, frame, chunk);

                // Then the records are restored exactly
                assertThat(restored).isEqualTo(data);
            }
        }

        @ParameterizedTest
        @ValueSource(ints = {1, 2})
        void multithreadedSegmentCompressStreamToJni(int nbWorkers) {
            // Given a stream split into minimum-size jobs. Streaming starts with CONTINUE,
            // so the source size is unknown and multithreading engages regardless of
            // payload size; it is the job size that makes the payload span several jobs.
            byte[] data = compressible(new Random(0x57EA), MULTI_THREAD_PAYLOAD_SIZE);
            byte[] frame;
            int jobs;
            try (Arena arena = Arena.ofConfined();
                 ZstdCompressStream sut = Zstd.newCompressStream()) {
                sut.workers(nbWorkers)
                        .jobSize(ZstdByteSize.of(MIN_JOB_SIZE));

                // When it is compressed through 64 KiB segments by the worker pool
                frame = compressInChunks(arena, sut, data, 64 * 1024);
                jobs = sut.progress().currentJobId();
            }

            // Then the payload really went through several jobs (single-threaded reports 0),
            // and zstd-jni decodes the frame back to the payload
            assertThat(jobs).isGreaterThanOrEqualTo(MULTI_THREAD_PAYLOAD_SIZE / MIN_JOB_SIZE);
            assertThat(com.github.luben.zstd.Zstd.decompress(frame, data.length)).isEqualTo(data);
        }

        private static byte[] records(int count) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (int i = 0; i < count; i++) {
                out.writeBytes(sample(i));
            }
            return out.toByteArray();
        }

        private static byte[] compressInChunks(Arena arena, ZstdCompressStream cs, byte[] data, int chunk) {
            MemorySegment src = arena.allocate(Math.max(data.length, 1));
            MemorySegment.copy(data, 0, src, ValueLayout.JAVA_BYTE, 0, data.length);
            MemorySegment dst = arena.allocate(chunk);
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            long off = 0;
            ZstdStreamResult r;
            do {
                long n = Math.min(chunk, data.length - off);
                boolean last = off + n == data.length;
                r = cs.compress(dst, src.asSlice(off, n), last ? ZstdEndDirective.END : ZstdEndDirective.CONTINUE);
                off += r.bytesConsumed();
                sink.writeBytes(dst.asSlice(0, r.bytesProduced()).toArray(ValueLayout.JAVA_BYTE));
            } while (off < data.length || !r.isComplete());
            return sink.toByteArray();
        }

        private static byte[] decompressInChunks(Arena arena, ZstdDecompressStream ds, byte[] frame, int chunk) {
            MemorySegment src = arena.allocate(frame.length);
            MemorySegment.copy(frame, 0, src, ValueLayout.JAVA_BYTE, 0, frame.length);
            MemorySegment dst = arena.allocate(chunk);
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            long off = 0;
            ZstdStreamResult r;
            do {
                long n = Math.min(chunk, frame.length - off);
                r = ds.decompress(dst, src.asSlice(off, n));
                off += r.bytesConsumed();
                sink.writeBytes(dst.asSlice(0, r.bytesProduced()).toArray(ValueLayout.JAVA_BYTE));
            } while (!r.isComplete());
            return sink.toByteArray();
        }
    }

    @Nested
    class Multithreaded {


        @ParameterizedTest
        @ValueSource(ints = {1, 2})
        void javaMultiThreadCompressJniDecompress(int nbWorkers) {
            byte[] data = compressible(new Random(0x5EED), MULTI_THREAD_PAYLOAD_SIZE);

            byte[] frame;
            try (ZstdCompressContext ctx = Zstd.newCompressContext()) {
                ctx.workers(nbWorkers);
                frame = ctx.compress(data);
            }
            assertThat(com.github.luben.zstd.Zstd.decompress(frame, data.length)).isEqualTo(data);
        }

        @Test
        void jniMultiThreadCompressJavaDecompress() throws Exception {
            byte[] data = compressible(new Random(0xFEED), MULTI_THREAD_PAYLOAD_SIZE);

            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (var zout = new com.github.luben.zstd.ZstdOutputStream(sink)) {
                zout.setWorkers(2);
                zout.write(data);
            }
            assertThat(Zstd.decompress(sink.toByteArray(), ZstdByteSize.of(data.length))).isEqualTo(data);
        }

        @Test
        void javaMultiThreadStreamCompressJniDecompress() throws Exception {
            byte[] data = compressible(new Random(0xC0FFEE), MULTI_THREAD_PAYLOAD_SIZE);

            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (ZstdOutputStream zout = Zstd.newOutputStream(sink)) {
                zout.workers(2);
                zout.write(data);
            }
            assertThat(com.github.luben.zstd.Zstd.decompress(sink.toByteArray(), data.length)).isEqualTo(data);
        }
    }

    @Nested
    class Dictionary {

        @Test
        void javaDictCompressJniDictDecompress() {
            ZstdDictionary dict = trainDict();
            byte[] sample = sample(11);

            byte[] frame;
            try (ZstdCompressContext ctx = Zstd.newCompressContext()) {
                frame = ctx.compress(sample, dict);
            }
            ZstdDictDecompress jniDict = new ZstdDictDecompress(dict.toByteArray());
            assertThat(com.github.luben.zstd.Zstd.decompress(frame, jniDict, sample.length)).isEqualTo(sample);
        }

        @Test
        void jniDictCompressJavaDictDecompress() {
            ZstdDictionary dict = trainDict();
            byte[] sample = sample(22);

            ZstdDictCompress jniDict = new ZstdDictCompress(dict.toByteArray(), ZstdCompressionLevel.DEFAULT.value());
            byte[] frame = com.github.luben.zstd.Zstd.compress(sample, jniDict);

            byte[] restored;
            try (ZstdDecompressContext ctx = Zstd.newDecompressContext()) {
                restored = ctx.decompress(frame, ZstdByteSize.of(sample.length), dict);
            }
            assertThat(restored).isEqualTo(sample);
        }

        @Test
        void javaLoadedDictWithChecksumJniDictDecompress() {
            // A sticky loaded dictionary combined with an advanced parameter
            // (checksum) — the COMPRESS2 path — must still produce a frame zstd-jni
            // decodes against the same dictionary.
            ZstdDictionary dict = trainDict();
            byte[] sample = sample(33);

            byte[] frame;
            try (ZstdCompressContext ctx = Zstd.newCompressContext().checksum(true)) {
                ctx.loadDictionary(dict);
                frame = ctx.compress(sample);
            }
            ZstdDictDecompress jniDict = new ZstdDictDecompress(dict.toByteArray());
            assertThat(com.github.luben.zstd.Zstd.decompress(frame, jniDict, sample.length)).isEqualTo(sample);
        }

        @Test
        void javaReferencedDigestedDictJniDictDecompress() {
            // A frame from a context referencing a digested CDict must decode in zstd-jni.
            ZstdDictionary dict = trainDict();
            byte[] sample = sample(44);

            byte[] frame;
            try (ZstdCompressDictionary cdict = Zstd.newCompressDictionary(dict, ZstdCompressionLevel.DEFAULT);
                 ZstdCompressContext ctx = Zstd.newCompressContext()) {
                ctx.refDictionary(cdict);
                frame = ctx.compress(sample);
            }
            ZstdDictDecompress jniDict = new ZstdDictDecompress(dict.toByteArray());
            assertThat(com.github.luben.zstd.Zstd.decompress(frame, jniDict, sample.length)).isEqualTo(sample);
        }
    }

    /// `ZSTD_d_forceIgnoreChecksum`: zstd-jni has no setter for it, so it serves
    /// only as the producer of checksummed frames.
    @Nested
    class IgnoreChecksum {

        static Stream<byte[]> payloads() {
            Random r = new Random(0x5348_4B53L);
            List<byte[]> cases = new ArrayList<>();
            cases.add(new byte[0]);
            for (int i = 0; i < 10; i++) {
                int size = r.nextInt(64 * 1024);
                cases.add(i % 2 == 0 ? compressible(r, size) : random(r, size));
            }
            return cases.stream();
        }

        @ParameterizedTest
        @MethodSource("payloads")
        void jniChecksummedFramesDecodeIdenticallyWithAndWithoutValidation(byte[] data) {
            // Given a checksummed frame from zstd-jni
            byte[] frame;
            try (var jni = new com.github.luben.zstd.ZstdCompressCtx()) {
                frame = jni.setChecksum(true).compress(data);
            }

            // When decoding it with validation on and off
            byte[] validated;
            byte[] ignored;
            try (ZstdDecompressContext on = Zstd.newDecompressContext();
                 ZstdDecompressContext off = Zstd.newDecompressContext().ignoreChecksum(true)) {
                validated = on.decompress(frame, ZstdByteSize.of(data.length));
                ignored = off.decompress(frame, ZstdByteSize.of(data.length));
            }

            // Then both produce the original input
            assertThat(validated).isEqualTo(data);
            assertThat(ignored).isEqualTo(data);
        }

        @ParameterizedTest
        @MethodSource("payloads")
        void jniChecksummedStreamDecodesWithValidationOff(byte[] data) throws Exception {
            // Given a checksummed stream from zstd-jni
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (var zout = new com.github.luben.zstd.ZstdOutputStream(sink)) {
                zout.setChecksum(true);
                zout.write(data);
            }

            // When reading it through a zstd-ffm stream with validation off
            byte[] restored;
            try (ZstdInputStream sut = Zstd.newInputStream(new ByteArrayInputStream(sink.toByteArray()))
                    .ignoreChecksum(true)) {
                restored = sut.readAllBytes();
            }

            // Then the original input comes back
            assertThat(restored).isEqualTo(data);
        }
    }

    /// Magicless frames (`ZSTD_f_zstd1_magicless`) against zstd-jni's `setMagicless`.
    @Nested
    class Magicless {

        static Stream<byte[]> payloads() {
            Random r = new Random(0x4D41_4749L);
            List<byte[]> cases = new ArrayList<>();
            cases.add(new byte[0]);
            for (int i = 0; i < 10; i++) {
                int size = r.nextInt(64 * 1024);
                cases.add(i % 2 == 0 ? compressible(r, size) : random(r, size));
            }
            return cases.stream();
        }

        @ParameterizedTest
        @MethodSource("payloads")
        void jniMagiclessCompressJavaMagiclessDecompress(byte[] data) {
            // Given a magicless frame from zstd-jni
            byte[] frame;
            try (var jni = new com.github.luben.zstd.ZstdCompressCtx()) {
                frame = jni.setMagicless(true).compress(data);
            }

            // When decoding it with a magicless zstd-ffm context
            byte[] restored;
            try (ZstdDecompressContext sut = Zstd.newDecompressContext().magicless(true)) {
                restored = sut.decompress(frame, ZstdByteSize.of(data.length));
            }

            // Then the input comes back
            assertThat(restored).isEqualTo(data);
        }

        @ParameterizedTest
        @MethodSource("payloads")
        void javaMagiclessCompressJniMagiclessDecompress(byte[] data) {
            // Given a magicless frame from zstd-ffm
            byte[] frame;
            try (ZstdCompressContext sut = Zstd.newCompressContext().magicless(true)) {
                frame = sut.compress(data);
            }

            // When decoding it with a magicless zstd-jni context
            byte[] restored;
            try (var jni = new com.github.luben.zstd.ZstdDecompressCtx()) {
                restored = jni.setMagicless(true).decompress(frame, data.length);
            }

            // Then the input comes back
            assertThat(restored).isEqualTo(data);
        }

        @ParameterizedTest
        @MethodSource("payloads")
        void jniMagiclessFrameReadsThroughJavaMagiclessInputStream(byte[] data) throws Exception {
            // Given a magicless frame from zstd-jni (its streams have no magicless setter)
            byte[] frame;
            try (var jni = new com.github.luben.zstd.ZstdCompressCtx()) {
                frame = jni.setMagicless(true).compress(data);
            }

            // When reading it through a magicless zstd-ffm input stream
            byte[] restored;
            try (ZstdInputStream sut = Zstd.newInputStream(new ByteArrayInputStream(frame)).magicless(true)) {
                restored = sut.readAllBytes();
            }

            // Then the input comes back
            assertThat(restored).isEqualTo(data);
        }

        @ParameterizedTest
        @MethodSource("payloads")
        void javaMagiclessOutputStreamDecodesWithJniMagiclessContext(byte[] data) throws Exception {
            // Given a magicless frame written through a zstd-ffm output stream
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (ZstdOutputStream sut = Zstd.newOutputStream(sink).magicless(true)) {
                sut.write(data);
            }

            // When decoding it with a magicless zstd-jni context
            byte[] restored;
            try (var jni = new com.github.luben.zstd.ZstdDecompressCtx()) {
                restored = jni.setMagicless(true).decompress(sink.toByteArray(), data.length);
            }

            // Then the input comes back
            assertThat(restored).isEqualTo(data);
        }
    }

    private static ZstdDictionary trainDict() {
        List<byte[]> samples = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            samples.add(sample(i));
        }
        return ZstdDictionary.train(samples, ZstdByteSize.ofKiB(8));
    }

    private static byte[] sample(int i) {
        return ("{\"id\":" + i + ",\"user\":\"u" + (i % 30) + "\",\"event\":\"click\"}")
                .getBytes(StandardCharsets.UTF_8);
    }
}
