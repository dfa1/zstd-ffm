package io.github.dfa1.zstd;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static io.github.dfa1.zstd.ZstdTestSupport.randomBytes;
import static io.github.dfa1.zstd.ZstdTestSupport.sample;
import static io.github.dfa1.zstd.ZstdTestSupport.trainDictionary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZstdStreamTest {

    @Nested
    class RoundTrip {

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 100, 64 * 1024, 5 * 1024 * 1024})
        void preservesPayloadsAcrossBufferBoundaries(int size) throws IOException {
            // Given a payload, some far larger than the stream buffers
            byte[] original = randomBytes(7, size);

            // When streamed through compress then decompress
            byte[] restored = streamDecompress(streamCompress(original, new ZstdCompressionLevel(3)));

            // Then it is recovered byte for byte
            assertThat(restored).isEqualTo(original);
        }

        @Test
        void compressesIncompressibleAndCompressibleAlike() throws IOException {
            byte[] text = "structured log line ".repeat(100_000).getBytes(StandardCharsets.UTF_8);

            byte[] frame = streamCompress(text, new ZstdCompressionLevel(9));

            assertThat(frame).hasSizeLessThan(text.length);
            assertThat(streamDecompress(frame)).isEqualTo(text);
        }

        @Test
        void writesSingleBytes() throws IOException {
            // Given bytes written one at a time via write(int)
            byte[] original = "abcdefghij".getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (ZstdOutputStream zout = new ZstdOutputStream(sink)) {
                for (byte value : original) {
                    zout.write(value);
                }
            }

            // Then the stream still round-trips
            assertThat(streamDecompress(sink.toByteArray())).isEqualTo(original);
        }
    }

    @Nested
    class Interop {

        @Test
        void streamOutputDecodesWithOneShot() throws IOException {
            // Given a frame produced by the streaming compressor
            byte[] original = "interop payload ".repeat(1000).getBytes(StandardCharsets.UTF_8);
            byte[] frame = streamCompress(original, new ZstdCompressionLevel(6));

            // Then the one-shot decompressor reads it (frame stores no size -> give a bound)
            assertThat(Zstd.decompress(frame, new ZstdByteSize(original.length))).isEqualTo(original);
        }

        @Test
        void oneShotOutputDecodesWithStream() throws IOException {
            // Given a frame produced by the one-shot compressor
            byte[] original = "interop payload ".repeat(1000).getBytes(StandardCharsets.UTF_8);
            byte[] frame = Zstd.compress(original);

            // Then the streaming decompressor reads it
            assertThat(streamDecompress(frame)).isEqualTo(original);
        }
    }

    @Nested
    class Dictionary {

        @Test
        void roundTripsWithDictionary() throws IOException {
            // Given a dictionary and a small sample
            ZstdDictionary dict = trainDictionary(3000);
            byte[] sample = sample(42);

            // When streamed through compress and decompress with the same dictionary
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (ZstdOutputStream zout = new ZstdOutputStream(sink, dict)) {
                zout.write(sample);
            }
            byte[] restored;
            try (ZstdInputStream zin = new ZstdInputStream(new ByteArrayInputStream(sink.toByteArray()), dict)) {
                restored = zin.readAllBytes();
            }

            // Then the sample is recovered
            assertThat(restored).isEqualTo(sample);
        }

        @Test
        void dictionaryShrinksStreamedRecord() throws IOException {
            ZstdDictionary dict = trainDictionary(3000);
            byte[] sample = sample(123);

            ByteArrayOutputStream withDict = new ByteArrayOutputStream();
            try (ZstdOutputStream zout = new ZstdOutputStream(withDict, dict)) {
                zout.write(sample);
            }

            // a dictionary frame of a tiny sample is smaller than a plain stream frame
            assertThat(withDict.size()).isLessThan(streamCompress(sample, ZstdCompressionLevel.DEFAULT).length);
        }

        @Test
        void streamWithDictionaryDecodesWithOneShot() throws IOException {
            // Given a dictionary frame produced by the streaming compressor
            ZstdDictionary dict = trainDictionary(3000);
            byte[] sample = sample(7);
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (ZstdOutputStream zout = new ZstdOutputStream(sink, dict)) {
                zout.write(sample);
            }

            // Then the one-shot context decodes it with the same dictionary
            try (ZstdDecompressContext ctx = new ZstdDecompressContext()) {
                assertThat(ctx.decompress(sink.toByteArray(), new ZstdByteSize(sample.length), dict)).isEqualTo(sample);
            }
        }

    }

    @Nested
    class Truncation {

        @ParameterizedTest
        @ValueSource(ints = {1, 8, 64})
        void throwsWhenFinalFrameIsCutShort(int bytesDropped) throws IOException {
            // Given a valid frame with its tail bytes lopped off (random data so the
            // frame stays large enough to drop bytes from)
            byte[] original = randomBytes(7, 50_000);
            byte[] frame = streamCompress(original, new ZstdCompressionLevel(6));
            byte[] cut = java.util.Arrays.copyOf(frame, frame.length - bytesDropped);

            try (ZstdInputStream sut = new ZstdInputStream(new ByteArrayInputStream(cut))) {
                // When the streaming decompressor drains it
                ThrowingCallable result = sut::readAllBytes;

                // Then it reports the truncation instead of returning a clean EOF
                assertThatThrownBy(result)
                        .isInstanceOf(ZstdException.class)
                        .hasMessageContaining("truncated");
            }
        }

        @Test
        void emptyInputIsCleanEofNotTruncation() throws IOException {
            // Given no input at all
            // When read, it is end-of-stream, not an error
            try (ZstdInputStream zin = new ZstdInputStream(new ByteArrayInputStream(new byte[0]))) {
                assertThat(zin.read()).isEqualTo(-1);
            }
        }
    }

    @Nested
    class PledgedSize {

        @Test
        void recordsContentSizeInTheFrame() throws IOException {
            // Given a stream told the exact total up front
            byte[] original = "pledged payload ".repeat(300).getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (ZstdOutputStream zout = ZstdOutputStream.withPledgedSize(sink, new ZstdCompressionLevel(6), new ZstdByteSize(original.length))) {
                zout.write(original);
            }
            byte[] frame = sink.toByteArray();

            // Then the frame header carries the size, so size-less decompress works
            assertThat(ZstdFrame.header(frame).contentSize()).hasValue(new ZstdByteSize(original.length));
            assertThat(Zstd.decompress(frame)).isEqualTo(original);
        }

        @Test
        void plainStreamFrameCannotBeSizedForZeroCopyDecode() throws IOException {
            // Given a streamed frame with no pledged size
            byte[] original = "no pledge ".repeat(500).getBytes(StandardCharsets.UTF_8);
            byte[] frame = streamCompress(original, new ZstdCompressionLevel(6));

            // When the zero-copy decoder asks the frame how big the output is
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment src = Zstd.copyIn(arena, frame);
                ThrowingCallable result = () -> Zstd.decompressedSize(src);

                // Then it cannot answer — the content size was never recorded
                assertThatThrownBy(result)
                        .isInstanceOf(ZstdException.class)
                        .hasMessageContaining("not stored");
            }
        }

        @Test
        void contextDecompressReadsTheStoredSizeWithoutABound() throws IOException {
            // Given a frame that records its decompressed size
            byte[] original = "sized frame ".repeat(300).getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (ZstdOutputStream zout = ZstdOutputStream.withPledgedSize(sink, new ZstdCompressionLevel(6), new ZstdByteSize(original.length))) {
                zout.write(original);
            }
            byte[] frame = sink.toByteArray();

            // When decompressed through a context with no explicit bound
            byte[] restored;
            try (ZstdDecompressContext dctx = new ZstdDecompressContext()) {
                restored = dctx.decompress(frame);
            }

            // Then the stored size sizes the output and the bytes round-trip
            assertThat(restored).isEqualTo(original);
        }

        @Test
        void contextDecompressWithoutABoundNeedsAStoredSize() throws IOException {
            // Given a streamed frame with no pledged size
            byte[] original = "no pledge ".repeat(500).getBytes(StandardCharsets.UTF_8);
            byte[] frame = streamCompress(original, new ZstdCompressionLevel(6));

            // When decompressed through a context with no explicit bound
            ThrowingCallable result = () -> {
                try (ZstdDecompressContext dctx = new ZstdDecompressContext()) {
                    dctx.decompress(frame);
                }
            };

            // Then it fails: the frame never recorded a size to allocate from
            assertThatThrownBy(result)
                    .isInstanceOf(ZstdException.class)
                    .hasMessageContaining("not stored");
        }

        @Test
        void pledgedFrameDecodesZeroCopyIntoArenaInOneShot() throws IOException {
            // Given a streamed frame that pledged its total up front
            byte[] original = "pledge enables zero-copy ".repeat(500).getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (ZstdOutputStream zout = ZstdOutputStream.withPledgedSize(sink, new ZstdCompressionLevel(6), new ZstdByteSize(original.length))) {
                zout.write(original);
            }
            byte[] frame = sink.toByteArray();

            // a memory-mapped reader sees the frame as a direct ByteBuffer — no heap copy in
            ByteBuffer mmap = ByteBuffer.allocateDirect(frame.length).put(frame).flip();

            // When it decodes straight into its arena and hands the result back as a ByteBuffer
            byte[] restored;
            try (Arena arena = Arena.ofConfined();
                 ZstdDecompressContext dctx = new ZstdDecompressContext()) {
                MemorySegment src = MemorySegment.ofBuffer(mmap);   // zero-copy input view
                MemorySegment out = dctx.decompress(arena, src);    // one allocation, zero copies
                ByteBuffer result = out.asByteBuffer();             // zero-copy hand-off out

                // Then the arena was sized exactly from the header and decode round-trips
                assertThat(out.byteSize()).isEqualTo(original.length);
                restored = new byte[result.remaining()];
                result.get(restored);
            }
            assertThat(restored).isEqualTo(original);
        }
    }

    @Nested
    class Parameters {

        @Test
        void returnsTheSameStreamForChaining() throws IOException {
            // Given a fresh output stream
            try (ZstdOutputStream zout = new ZstdOutputStream(new ByteArrayOutputStream())) {
                // When setting an advanced parameter
                ZstdOutputStream result = zout.parameter(ZstdCompressParameter.CHECKSUM_FLAG, 1);

                // Then it returns the same instance, for chaining
                assertThat(result).isSameAs(zout);
            }
        }

        @Test
        void rejectsOutOfRangeValue() throws IOException {
            // Given a fresh output stream
            try (ZstdOutputStream zout = new ZstdOutputStream(new ByteArrayOutputStream())) {
                // When setting an absurd window log
                ThrowingCallable result = () -> zout.parameter(ZstdCompressParameter.WINDOW_LOG, 99);

                // Then it is rejected natively
                assertThatThrownBy(result).isInstanceOf(ZstdException.class);
            }
        }
    }

    @Nested
    class OutputStreamLifecycle {

        @Test
        void flushPushesBufferedBytesThroughToTheSink() throws IOException {
            // Given a payload written but not yet closed
            byte[] original = "flushed payload ".repeat(2000).getBytes(StandardCharsets.UTF_8);
            CountingOutputStream sink = new CountingOutputStream();
            try (ZstdOutputStream zout = new ZstdOutputStream(sink, new ZstdCompressionLevel(3))) {
                zout.write(original);

                // When flushed mid-stream
                zout.flush();

                // Then the flush propagated to the underlying sink and emitted compressed bytes
                assertThat(sink.flushes).isPositive();
                assertThat(sink.size()).isPositive();

                // And the frame still completes and round-trips after the flush
                zout.write(original);
            }
            byte[] doubled = new byte[original.length * 2];
            System.arraycopy(original, 0, doubled, 0, original.length);
            System.arraycopy(original, 0, doubled, original.length, original.length);
            assertThat(streamDecompress(sink.toByteArray())).isEqualTo(doubled);
        }

        @Test
        void closeFlushesAndClosesTheUnderlyingStreamExactlyOnce() throws IOException {
            // Given a payload written to a stream that tracks flush/close on its sink
            byte[] original = "epilogue payload ".repeat(1000).getBytes(StandardCharsets.UTF_8);
            CountingOutputStream sink = new CountingOutputStream();
            ZstdOutputStream zout = new ZstdOutputStream(sink, new ZstdCompressionLevel(3));
            zout.write(original);

            // When closed twice
            zout.close();
            zout.close();

            // Then the underlying sink was flushed and closed, the close being idempotent
            assertThat(sink.flushes).isPositive();
            assertThat(sink.closes).isEqualTo(1);

            // And the written frame carries the full payload (epilogue was emitted)
            assertThat(streamDecompress(sink.toByteArray())).isEqualTo(original);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("io.github.dfa1.zstd.ZstdStreamTest#closedStreamOperations")
        void operationAfterCloseThrows(String name, ClosedStreamOperation operation) throws IOException {
            // Given a closed stream
            ThrowingCallable closedStreamOperation = operation.closeThenOperate();

            // When operating on it
            ThrowingCallable result = closedStreamOperation;

            // Then it refuses with an IOException rather than touching freed native state
            assertThatThrownBy(result)
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("closed");
        }
    }

    @Nested
    class InputStreamLifecycle {

        @Test
        void singleByteReadReturnsTheUnsignedValue() throws IOException {
            // Given a frame whose first decoded byte has its high bit set
            byte[] original = {(byte) 0xFF, (byte) 0x80, 0x01};
            byte[] frame = streamCompress(original, new ZstdCompressionLevel(3));

            // When read one byte at a time
            try (ZstdInputStream zin = new ZstdInputStream(new ByteArrayInputStream(frame))) {
                // Then each byte comes back as its unsigned 0..255 value, not a sign-extended int
                assertThat(zin.read()).isEqualTo(0xFF);
                assertThat(zin.read()).isEqualTo(0x80);
                assertThat(zin.read()).isEqualTo(0x01);
                assertThat(zin.read()).isEqualTo(-1);
            }
        }

        @Test
        void readPastEndOfStreamStaysMinusOne() throws IOException {
            // Given a compressed frame
            byte[] frame = streamCompress("done".getBytes(StandardCharsets.UTF_8), new ZstdCompressionLevel(3));
            try (ZstdInputStream zin = new ZstdInputStream(new ByteArrayInputStream(frame))) {
                // When the stream is drained to the end, then read again past EOF
                byte[] all = zin.readAllBytes();
                int afterByte = zin.read();
                int afterBlock = zin.read(new byte[8], 0, 8);

                // Then the content matches and both read overloads keep reporting EOF
                assertThat(all).isEqualTo("done".getBytes(StandardCharsets.UTF_8));
                assertThat(afterByte).isEqualTo(-1);
                assertThat(afterBlock).isEqualTo(-1);
            }
        }

        @Test
        void closeClosesTheUnderlyingStreamExactlyOnce() throws IOException {
            // Given an input stream over a source that tracks close()
            byte[] frame = streamCompress("payload".getBytes(StandardCharsets.UTF_8), new ZstdCompressionLevel(3));
            CountingInputStream source = new CountingInputStream(frame);
            ZstdInputStream zin = new ZstdInputStream(source);

            // When closed twice
            zin.close();
            zin.close();

            // Then the underlying source was closed once, the close being idempotent
            assertThat(source.closes).isEqualTo(1);
        }

        @Test
        void firstSingleByteIsCorrectEvenWhenInputDribblesInOneByteAtATime() throws IOException {
            // Given a frame whose first decoded byte has its high bit set, fed one
            // byte per read so the first decode calls produce nothing until the header
            // is complete — the decoder must keep refilling before returning a byte
            byte[] original = {(byte) 0xFF, 0x10, 0x20};
            byte[] frame = streamCompress(original, new ZstdCompressionLevel(3));

            // When the very first byte is read
            try (ZstdInputStream zin = new ZstdInputStream(new DribbleInputStream(frame))) {
                // Then it is the real first byte, not a premature zero from an empty slice
                assertThat(zin.read()).isEqualTo(0xFF);
            }
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 100, 64 * 1024})
        void readsCorrectlyWhenInputArrivesOneByteAtATime(int size) throws IOException {
            // Given a frame fed through a source that yields a single byte per read,
            // forcing the decoder across many refill/no-progress iterations
            byte[] original = randomBytes(7, size);
            byte[] frame = streamCompress(original, new ZstdCompressionLevel(3));

            // When drained
            byte[] restored;
            try (ZstdInputStream zin = new ZstdInputStream(new DribbleInputStream(frame))) {
                restored = zin.readAllBytes();
            }

            // Then every byte survives the slow refill path
            assertThat(restored).isEqualTo(original);
        }
    }

    private static Stream<Arguments> closedStreamOperations() {
        return Stream.of(
                Arguments.of("write after close", (ClosedStreamOperation) () -> {
                    ZstdOutputStream stream = new ZstdOutputStream(new ByteArrayOutputStream());
                    stream.close();
                    return () -> stream.write(1);
                }),
                Arguments.of("flush after close", (ClosedStreamOperation) () -> {
                    ZstdOutputStream stream = new ZstdOutputStream(new ByteArrayOutputStream());
                    stream.close();
                    return stream::flush;
                }),
                Arguments.of("read after close", (ClosedStreamOperation) () -> {
                    byte[] frame = streamCompress("payload".getBytes(StandardCharsets.UTF_8), new ZstdCompressionLevel(3));
                    ZstdInputStream stream = new ZstdInputStream(new ByteArrayInputStream(frame));
                    stream.close();
                    return stream::read;
                })
        );
    }

    @FunctionalInterface
    private interface ClosedStreamOperation {
        ThrowingCallable closeThenOperate() throws IOException;
    }

    /// A sink that records flush/close calls while retaining the bytes written to it
    /// (a [ByteArrayOutputStream] whose close is a no-op, so bytes stay readable).
    private static final class CountingOutputStream extends ByteArrayOutputStream {
        private int flushes;
        private int closes;

        @Override
        public void flush() throws IOException {
            flushes++;
            super.flush();
        }

        @Override
        public void close() throws IOException {
            closes++;
            super.close();
        }
    }

    /// A source over a fixed byte array that records close() calls.
    private static final class CountingInputStream extends ByteArrayInputStream {
        private int closes;

        CountingInputStream(byte[] buf) {
            super(buf);
        }

        @Override
        public void close() throws IOException {
            closes++;
            super.close();
        }
    }

    /// A source that hands out at most one byte per read, exercising the decoder's
    /// partial-input refill loop.
    private static final class DribbleInputStream extends InputStream {
        private final byte[] data;
        private int pos;

        DribbleInputStream(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            return pos < data.length ? (data[pos++] & 0xFF) : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (len == 0) {
                return 0;
            }
            if (pos >= data.length) {
                return -1;
            }
            b[off] = data[pos++];
            return 1;
        }
    }

    private static byte[] streamCompress(byte[] data, ZstdCompressionLevel level) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (ZstdOutputStream zout = new ZstdOutputStream(sink, level)) {
            zout.write(data);
        }
        return sink.toByteArray();
    }

    private static byte[] streamDecompress(byte[] frame) throws IOException {
        try (ZstdInputStream zin = new ZstdInputStream(new ByteArrayInputStream(frame))) {
            return zin.readAllBytes();
        }
    }
}
