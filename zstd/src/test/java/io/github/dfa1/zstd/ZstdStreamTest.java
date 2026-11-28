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

import static io.github.dfa1.zstd.ZstdTestSupport.corruptDictionary;
import static io.github.dfa1.zstd.ZstdTestSupport.randomBytes;
import static io.github.dfa1.zstd.ZstdTestSupport.rawFrameWithWindowLog;
import static io.github.dfa1.zstd.ZstdTestSupport.sample;
import static io.github.dfa1.zstd.ZstdTestSupport.trainDictionary;
import static io.github.dfa1.zstd.ZstdTestSupport.withWrongChecksum;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
            byte[] restored = streamDecompress(streamCompress(original, ZstdCompressionLevel.of(3)));

            // Then it is recovered byte for byte
            assertThat(restored).isEqualTo(original);
        }

        @Test
        void compressesIncompressibleAndCompressibleAlike() throws IOException {
            byte[] text = "structured log line ".repeat(100_000).getBytes(StandardCharsets.UTF_8);

            byte[] frame = streamCompress(text, ZstdCompressionLevel.of(9));

            assertThat(frame).hasSizeLessThan(text.length);
            assertThat(streamDecompress(frame)).isEqualTo(text);
        }

        @Test
        void writesSingleBytes() throws IOException {
            // Given bytes written one at a time via write(int)
            byte[] original = "abcdefghij".getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (ZstdOutputStream zout = Zstd.newOutputStream(sink)) {
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
            byte[] frame = streamCompress(original, ZstdCompressionLevel.of(6));

            // Then the one-shot decompressor reads it (frame stores no size -> give a bound)
            assertThat(Zstd.decompress(frame, ZstdByteSize.of(original))).isEqualTo(original);
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
            try (ZstdOutputStream zout = Zstd.newOutputStream(sink, dict)) {
                zout.write(sample);
            }
            byte[] restored;
            try (ZstdInputStream zin = Zstd.newInputStream(new ByteArrayInputStream(sink.toByteArray()), dict)) {
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
            try (ZstdOutputStream zout = Zstd.newOutputStream(withDict, dict)) {
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
            try (ZstdOutputStream zout = Zstd.newOutputStream(sink, dict)) {
                zout.write(sample);
            }

            // Then the one-shot context decodes it with the same dictionary
            try (ZstdDecompressContext ctx = Zstd.newDecompressContext()) {
                assertThat(ctx.decompress(sink.toByteArray(), ZstdByteSize.of(sample), dict)).isEqualTo(sample);
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
            byte[] frame = streamCompress(original, ZstdCompressionLevel.of(6));
            byte[] cut = java.util.Arrays.copyOf(frame, frame.length - bytesDropped);

            try (ZstdInputStream sut = Zstd.newInputStream(new ByteArrayInputStream(cut))) {
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
            try (ZstdInputStream zin = Zstd.newInputStream(new ByteArrayInputStream(new byte[0]))) {
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
            try (ZstdOutputStream zout = Zstd.newPledgedOutputStream(sink, ZstdByteSize.of(original))) {
                zout.level(ZstdCompressionLevel.of(6));
                zout.write(original);
            }
            byte[] frame = sink.toByteArray();

            // Then the frame header carries the size, so size-less decompress works
            assertThat(ZstdFrame.header(frame).contentSize()).hasValue(ZstdByteSize.of(original));
            assertThat(Zstd.decompress(frame)).isEqualTo(original);
        }

        @Test
        void plainStreamFrameCannotBeSizedForZeroCopyDecode() throws IOException {
            // Given a streamed frame with no pledged size
            byte[] original = "no pledge ".repeat(500).getBytes(StandardCharsets.UTF_8);
            byte[] frame = streamCompress(original, ZstdCompressionLevel.of(6));

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
            try (ZstdOutputStream zout = Zstd.newPledgedOutputStream(sink, ZstdByteSize.of(original))) {
                zout.level(ZstdCompressionLevel.of(6));
                zout.write(original);
            }
            byte[] frame = sink.toByteArray();

            // When decompressed through a context with no explicit bound
            byte[] restored;
            try (ZstdDecompressContext dctx = Zstd.newDecompressContext()) {
                restored = dctx.decompress(frame);
            }

            // Then the stored size sizes the output and the bytes round-trip
            assertThat(restored).isEqualTo(original);
        }

        @Test
        void contextDecompressWithoutABoundNeedsAStoredSize() throws IOException {
            // Given a streamed frame with no pledged size
            byte[] original = "no pledge ".repeat(500).getBytes(StandardCharsets.UTF_8);
            byte[] frame = streamCompress(original, ZstdCompressionLevel.of(6));

            // When decompressed through a context with no explicit bound
            ThrowingCallable result = () -> {
                try (ZstdDecompressContext dctx = Zstd.newDecompressContext()) {
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
            try (ZstdOutputStream zout = Zstd.newPledgedOutputStream(sink, ZstdByteSize.of(original))) {
                zout.level(ZstdCompressionLevel.of(6));
                zout.write(original);
            }
            byte[] frame = sink.toByteArray();

            // a memory-mapped reader sees the frame as a direct ByteBuffer — no heap copy in
            ByteBuffer mmap = ByteBuffer.allocateDirect(frame.length).put(frame).flip();

            // When it decodes straight into its arena and hands the result back as a ByteBuffer
            byte[] restored;
            try (Arena arena = Arena.ofConfined();
                 ZstdDecompressContext dctx = Zstd.newDecompressContext()) {
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
            try (ZstdOutputStream zout = Zstd.newOutputStream(new ByteArrayOutputStream())) {
                // When setting an advanced parameter
                ZstdOutputStream result = zout.checksum(true);

                // Then it returns the same instance, for chaining
                assertThat(result).isSameAs(zout);
            }
        }

        @Test
        void rejectsOutOfRangeValue() throws IOException {
            // Given a fresh output stream
            try (ZstdOutputStream zout = Zstd.newOutputStream(new ByteArrayOutputStream())) {
                // When setting an absurd window log
                ThrowingCallable result = () -> zout.windowLog(99);

                // Then it is rejected before reaching native code
                assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Nested
    class InputStreamParameters {

        private static final byte[] PAYLOAD = randomBytes(0x1F, 64 * 1024);

        @Test
        void returnsTheSameStreamForChaining() throws IOException {
            // Given a fresh input stream
            try (ZstdInputStream zin = Zstd.newInputStream(new ByteArrayInputStream(Zstd.compress(PAYLOAD)))) {
                // When setting an advanced parameter
                ZstdInputStream result = zin.windowLogMax(27);

                // Then it returns the same instance, for chaining
                assertThat(result).isSameAs(zin);
            }
        }

        @Test
        void windowLogMaxBelowTheFrameWindowRejectsTheFrame() throws IOException {
            // Given a frame larger than the stream's output buffer (smaller ones take
            // zstd's single-shot path, which skips the window check) and a stream
            // that accepts at most a 1 KiB window
            byte[] frame = Zstd.compress(randomBytes(0x4F, 1024 * 1024));
            try (ZstdInputStream sut = Zstd.newInputStream(new ByteArrayInputStream(frame))
                    .windowLogMax(10)) {
                // When reading it
                ThrowingCallable result = sut::readAllBytes;

                // Then the limit is enforced
                assertThatThrownBy(result)
                        .isInstanceOfSatisfying(ZstdException.class,
                                e -> assertThat(e.code()).isEqualTo(ZstdErrorCode.FRAME_PARAMETER_WINDOW_TOO_LARGE));
            }
        }

        @Test
        void defaultLimitRejectsAWindowAboveIt() throws IOException {
            // Given a frame declaring a 2^28 window, above the default limit (27)
            byte[] frame = rawFrameWithWindowLog(28, "hello".getBytes(StandardCharsets.UTF_8));
            try (ZstdInputStream sut = Zstd.newInputStream(new ByteArrayInputStream(frame))) {
                // When streaming it without raising the limit
                ThrowingCallable result = sut::readAllBytes;

                // Then the stream refuses to allocate the window
                assertThatThrownBy(result)
                        .isInstanceOfSatisfying(ZstdException.class,
                                e -> assertThat(e.code()).isEqualTo(ZstdErrorCode.FRAME_PARAMETER_WINDOW_TOO_LARGE));
            }
        }

        @Test
        void magiclessStreamReadsAMagiclessFrame() throws IOException {
            // Given a magicless frame
            byte[] frame;
            try (ZstdCompressContext cctx = Zstd.newCompressContext().magicless(true)) {
                frame = cctx.compress(PAYLOAD);
            }

            // When reading it through a magicless stream
            byte[] restored;
            try (ZstdInputStream sut = Zstd.newInputStream(new ByteArrayInputStream(frame)).magicless(true)) {
                restored = sut.readAllBytes();
            }

            // Then the payload comes back
            assertThat(restored).isEqualTo(PAYLOAD);
        }

        @Test
        void defaultStreamRejectsAMagiclessFrame() throws IOException {
            // Given a magicless frame and a standard-format stream
            byte[] frame;
            try (ZstdCompressContext cctx = Zstd.newCompressContext().magicless(true)) {
                frame = cctx.compress(PAYLOAD);
            }
            try (ZstdInputStream sut = Zstd.newInputStream(new ByteArrayInputStream(frame))) {
                // When reading it
                ThrowingCallable result = sut::readAllBytes;

                // Then the missing magic number is rejected
                assertThatThrownBy(result).isInstanceOf(ZstdException.class);
            }
        }

        @Test
        void magiclessOutputStreamRoundTripsThroughAMagiclessInputStream() throws IOException {
            // Given a payload written through a magicless output stream
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (ZstdOutputStream zout = Zstd.newOutputStream(sink).magicless(true)) {
                zout.write(PAYLOAD);
            }

            // When reading it back through a magicless input stream
            byte[] restored;
            try (ZstdInputStream sut = Zstd.newInputStream(new ByteArrayInputStream(sink.toByteArray())).magicless(true)) {
                restored = sut.readAllBytes();
            }

            // Then the payload comes back
            assertThat(restored).isEqualTo(PAYLOAD);
        }

        @Test
        void defaultStreamRejectsAWrongChecksum() throws IOException {
            // Given a frame whose stored checksum is flipped
            byte[] frame = withWrongChecksum(PAYLOAD);
            try (ZstdInputStream sut = Zstd.newInputStream(new ByteArrayInputStream(frame))) {
                // When reading it with validation on
                ThrowingCallable result = sut::readAllBytes;

                // Then the mismatch is reported
                assertThatThrownBy(result)
                        .isInstanceOfSatisfying(ZstdException.class,
                                e -> assertThat(e.code()).isEqualTo(ZstdErrorCode.CHECKSUM_WRONG));
            }
        }

        @Test
        void forceIgnoreChecksumRecoversTheIntactPayload() throws IOException {
            // Given a frame whose stored checksum is flipped
            byte[] frame = withWrongChecksum(PAYLOAD);

            // When reading it with validation off
            byte[] restored;
            try (ZstdInputStream sut = Zstd.newInputStream(new ByteArrayInputStream(frame))
                    .ignoreChecksum(true)) {
                restored = sut.readAllBytes();
            }

            // Then the payload comes back untouched
            assertThat(restored).isEqualTo(PAYLOAD);
        }

        @Test
        void rejectsAChangeMidFrame() throws IOException {
            // Given a stream that has started decoding a frame larger than its output buffer
            byte[] large = randomBytes(0x2F, 1024 * 1024);
            try (ZstdInputStream sut = Zstd.newInputStream(new ByteArrayInputStream(Zstd.compress(large)))) {
                assertThat(sut.read()).isNotNegative();

                // When changing a parameter mid-frame
                ThrowingCallable result = () -> sut.ignoreChecksum(true);

                // Then zstd refuses it
                assertThatThrownBy(result)
                        .isInstanceOfSatisfying(ZstdException.class,
                                e -> assertThat(e.code()).isEqualTo(ZstdErrorCode.STAGE_WRONG));
            }
        }

        @Test
        void parameterAfterCloseThrowsInsteadOfTouchingTheFreedContext() throws IOException {
            // Given a closed input stream
            ZstdInputStream sut = Zstd.newInputStream(new ByteArrayInputStream(new byte[0]));
            sut.close();

            // When a parameter is set on it
            ThrowingCallable result = () -> sut.ignoreChecksum(true);

            // Then it fails fast rather than using the freed context
            assertThatThrownBy(result)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
        }
    }

    @Nested
    class OutputStreamLifecycle {

        @Test
        void flushPushesBufferedBytesThroughToTheSink() throws IOException {
            // Given a payload written but not yet closed
            byte[] original = "flushed payload ".repeat(2000).getBytes(StandardCharsets.UTF_8);
            CountingOutputStream sink = new CountingOutputStream();
            try (ZstdOutputStream zout = Zstd.newOutputStream(sink)) {
                zout.level(ZstdCompressionLevel.of(3));
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
            ZstdOutputStream zout = Zstd.newOutputStream(sink).level(ZstdCompressionLevel.of(3));
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

        @Test
        void closeReenteredFromTheSinkFreesOnceAndFinishesTheFrame() throws IOException {
            // Given a sink that closes the stream again from its flush (#183)
            byte[] original = "re-entrant close ".repeat(200).getBytes(StandardCharsets.UTF_8);
            ReenteringOutputStream sink = new ReenteringOutputStream();
            ZstdOutputStream sut = Zstd.newOutputStream(sink);
            sink.target = sut;
            sut.write(original);

            // When closed
            sut.close();

            // Then the inner close returned at once, and the outer one finished the frame
            assertThat(sink.reentered).isTrue();
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

        @Test
        void parameterAfterCloseThrowsInsteadOfTouchingTheFreedContext() throws IOException {
            // Given a closed stream, whose native cctx has been freed
            ZstdOutputStream sut = Zstd.newOutputStream(new ByteArrayOutputStream());
            sut.close();

            // When a parameter is set on it
            ThrowingCallable result = () -> sut.workers(2);

            // Then it refuses before reaching native code
            assertThatThrownBy(result)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("stream closed");
        }

        @Test
        void inputStreamSetterAfterCloseThrowsInsteadOfTouchingTheFreedContext() throws IOException {
            // Given a closed input stream, whose native dctx has been freed
            ZstdInputStream sut = Zstd.newInputStream(new ByteArrayInputStream(new byte[0]));
            sut.close();

            // When a setting is applied to it
            ThrowingCallable result = () -> sut.magicless(true);

            // Then it refuses before reaching native code
            assertThatThrownBy(result)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("stream closed");
        }
    }

    @Nested
    class ConstructionFailure {

        @Test
        void inputStreamWithCorruptDictionaryFailsAfterCreatingItsContext() {
            // Given a dictionary with a valid magic number but garbage entropy tables
            ZstdDictionary corrupt = corruptDictionary();
            ByteArrayInputStream source = new ByteArrayInputStream(new byte[0]);

            // When constructing the stream, which digests the dictionary eagerly
            ThrowingCallable result = () -> Zstd.newInputStream(source, corrupt);

            // Then the native rejection propagates as a ZstdException
            assertThatThrownBy(result).isInstanceOf(ZstdException.class)
                    .extracting(e -> ((ZstdException) e).code())
                    .isEqualTo(ZstdErrorCode.DICTIONARY_CORRUPTED);
        }
    }

    @Nested
    class InputStreamLifecycle {

        @Test
        void singleByteReadReturnsTheUnsignedValue() throws IOException {
            // Given a frame whose first decoded byte has its high bit set
            byte[] original = {(byte) 0xFF, (byte) 0x80, 0x01};
            byte[] frame = streamCompress(original, ZstdCompressionLevel.of(3));

            // When read one byte at a time
            try (ZstdInputStream zin = Zstd.newInputStream(new ByteArrayInputStream(frame))) {
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
            byte[] frame = streamCompress("done".getBytes(StandardCharsets.UTF_8), ZstdCompressionLevel.of(3));
            try (ZstdInputStream zin = Zstd.newInputStream(new ByteArrayInputStream(frame))) {
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
            byte[] frame = streamCompress("payload".getBytes(StandardCharsets.UTF_8), ZstdCompressionLevel.of(3));
            CountingInputStream source = new CountingInputStream(frame);
            ZstdInputStream zin = Zstd.newInputStream(source);

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
            byte[] frame = streamCompress(original, ZstdCompressionLevel.of(3));

            // When the very first byte is read
            try (ZstdInputStream zin = Zstd.newInputStream(new DribbleInputStream(frame))) {
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
            byte[] frame = streamCompress(original, ZstdCompressionLevel.of(3));

            // When drained
            byte[] restored;
            try (ZstdInputStream zin = Zstd.newInputStream(new DribbleInputStream(frame))) {
                restored = zin.readAllBytes();
            }

            // Then every byte survives the slow refill path
            assertThat(restored).isEqualTo(original);
        }
    }

    @Nested
    class CloseFromAnotherThread {

        @Test
        void outputStreamFinishesTheFrameAndClosesTheSink() throws IOException {
            // Given an output stream created on this thread, with data written
            byte[] original = "any thread ".repeat(200).getBytes(StandardCharsets.UTF_8);
            CountingOutputStream sink = new CountingOutputStream();
            ZstdOutputStream sut = Zstd.newOutputStream(sink);
            sut.write(original);

            // When another thread closes it
            ThrowingCallable result = () -> closeOnAnotherThread(sut);

            // Then the frame is finished and the sink closed
            assertThatCode(result).doesNotThrowAnyException();
            assertThat(sink.closes).isEqualTo(1);
            assertThat(streamDecompress(sink.toByteArray())).isEqualTo(original);
        }

        @Test
        void inputStreamClosesTheSource() {
            // Given an input stream created on this thread
            CountingInputStream source = new CountingInputStream(Zstd.compress(new byte[16]));
            ZstdInputStream sut = Zstd.newInputStream(source);

            // When another thread closes it
            ThrowingCallable result = () -> closeOnAnotherThread(sut);

            // Then the source is closed and the stream refuses further reads
            assertThatCode(result).doesNotThrowAnyException();
            assertThat(source.closes).isEqualTo(1);
            assertThatThrownBy(sut::read).isInstanceOf(IOException.class);
        }
    }

    @SuppressWarnings("java:S1144") // referenced via @MethodSource's qualified name from the nested OutputStreamLifecycle class
    private static Stream<Arguments> closedStreamOperations() {
        return Stream.of(
                Arguments.of("write after close", (ClosedStreamOperation) () -> {
                    ZstdOutputStream stream = Zstd.newOutputStream(new ByteArrayOutputStream());
                    stream.close();
                    return () -> stream.write(1);
                }),
                Arguments.of("flush after close", (ClosedStreamOperation) () -> {
                    ZstdOutputStream stream = Zstd.newOutputStream(new ByteArrayOutputStream());
                    stream.close();
                    return stream::flush;
                }),
                Arguments.of("read after close", (ClosedStreamOperation) () -> {
                    byte[] frame = streamCompress("payload".getBytes(StandardCharsets.UTF_8), ZstdCompressionLevel.of(3));
                    ZstdInputStream stream = Zstd.newInputStream(new ByteArrayInputStream(frame));
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

    /// A sink whose `flush` closes `target` once, as a careless wrapper might.
    private static final class ReenteringOutputStream extends ByteArrayOutputStream {
        private ZstdOutputStream target;
        private boolean reentered;

        @Override
        public void flush() throws IOException {
            if (!reentered) {
                reentered = true;
                target.close();
            }
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

    /// Closes `resource` on a fresh platform thread and rethrows here whatever
    /// that close threw.
    private static void closeOnAnotherThread(AutoCloseable resource) throws Exception {
        Exception[] thrown = new Exception[1];
        Thread.ofPlatform().start(() -> {
            try {
                resource.close();
            } catch (Exception e) {
                thrown[0] = e;
            }
        }).join();
        if (thrown[0] != null) {
            throw thrown[0];
        }
    }

    private static byte[] streamCompress(byte[] data, ZstdCompressionLevel level) throws IOException {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (ZstdOutputStream zout = Zstd.newOutputStream(sink)) {
            zout.level(level);
            zout.write(data);
        }
        return sink.toByteArray();
    }

    private static byte[] streamDecompress(byte[] frame) throws IOException {
        try (ZstdInputStream zin = Zstd.newInputStream(new ByteArrayInputStream(frame))) {
            return zin.readAllBytes();
        }
    }
}
