package io.github.dfa1.zstd;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static io.github.dfa1.zstd.ZstdTestSupport.bytesOf;
import static io.github.dfa1.zstd.ZstdTestSupport.randomBytes;
import static io.github.dfa1.zstd.ZstdTestSupport.segmentOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The block-size cap of [ZstdCompressSettings#maxBlockSize(ZstdByteSize)] and
/// [ZstdDecompressSettings#maxBlockSize(ZstdByteSize)].
class ZstdMaxBlockSizeTest {

    private static final ZstdByteSize CAP = ZstdByteSize.ofKiB(4);

    /// Incompressible, so every block is stored raw and its header size is its content size.
    private static final byte[] RANDOM = randomBytes(160, 256 * 1024);

    private static final byte[] TEXT =
            "a block-size cap keeps every block small ".repeat(8000).getBytes(StandardCharsets.UTF_8);

    /// Raw block type in a block header (RFC 8878 §3.1.1.2.2).
    private static final int RAW_BLOCK = 0;

    @Nested
    class Compress {

        @Test
        void cappedFramesHaveNoBlockAboveTheCap() {
            // Given a compressor capped at 4 KiB
            byte[] frame;
            try (ZstdCompressContext sut = Zstd.newCompressContext().maxBlockSize(CAP)) {
                // When compressing incompressible data
                frame = sut.compress(RANDOM);
            }

            // Then every block is raw and at most 4 KiB, so 64 of them cover 256 KiB
            List<int[]> blocks = blockHeaders(frame);
            assertThat(blocks).hasSize(RANDOM.length / (int) CAP.value())
                    .allSatisfy(block -> {
                        assertThat(block[0]).isEqualTo(RAW_BLOCK);
                        assertThat(block[1]).isLessThanOrEqualTo((int) CAP.value());
                    });
        }

        @Test
        void cappedCompressibleFramesHaveAtLeastOneBlockPerCap() {
            // Given a compressor capped at 4 KiB
            byte[] frame;
            try (ZstdCompressContext sut = Zstd.newCompressContext().maxBlockSize(CAP)) {
                // When compressing compressible data
                frame = sut.compress(TEXT);
            }

            // Then the content is split into blocks of at most 4 KiB each
            int minimumBlocks = Math.ceilDiv(TEXT.length, (int) CAP.value());
            assertThat(blockHeaders(frame)).hasSizeGreaterThanOrEqualTo(minimumBlocks);
        }

        @Test
        void zeroRestoresTheDefaultBlockSize() {
            // Given a compressor capped, then reset to zero
            byte[] frame;
            try (ZstdCompressContext sut = Zstd.newCompressContext()
                    .maxBlockSize(CAP)
                    .maxBlockSize(ZstdByteSize.of(0))) {
                // When compressing incompressible data
                frame = sut.compress(RANDOM);
            }

            // Then it uses the default 128 KiB blocks
            assertThat(blockHeaders(frame)).hasSize(2);
        }

        @Test
        void outputStreamHonorsTheCap() throws IOException {
            // Given an output stream capped at 4 KiB
            ByteArrayOutputStream sink = new ByteArrayOutputStream();
            try (ZstdOutputStream sut = Zstd.newOutputStream(sink).maxBlockSize(CAP)) {
                // When writing incompressible data
                sut.write(RANDOM);
            }

            // Then every block is at most 4 KiB
            assertThat(blockHeaders(sink.toByteArray()))
                    .allSatisfy(block -> assertThat(block[1]).isLessThanOrEqualTo((int) CAP.value()));
        }
    }

    @Nested
    class Decompress {

        @Test
        void contextWithTheSameCapRoundTrips() {
            // Given a frame compressed with a 4 KiB cap
            byte[] frame = cappedFrame(TEXT);

            // When decoding it one-shot with the same cap
            byte[] restored;
            try (ZstdDecompressContext sut = Zstd.newDecompressContext().maxBlockSize(CAP)) {
                restored = sut.decompress(frame);
            }

            // Then the payload comes back
            assertThat(restored).isEqualTo(TEXT);
        }

        @Test
        void inputStreamWithTheSameCapRoundTrips() throws IOException {
            // Given a frame compressed with a 4 KiB cap
            byte[] frame = cappedFrame(RANDOM);

            // When reading it through an input stream with the same cap
            byte[] restored;
            try (ZstdInputStream sut = Zstd.newInputStream(new ByteArrayInputStream(frame)).maxBlockSize(CAP)) {
                restored = sut.readAllBytes();
            }

            // Then the payload comes back
            assertThat(restored).isEqualTo(RANDOM);
        }

        @Test
        void segmentStreamWithTheSameCapRoundTrips() {
            // Given a frame compressed with a 4 KiB cap
            byte[] frame = cappedFrame(TEXT);

            // When streaming it through a decoder with the same cap
            byte[] restored;
            try (ZstdDecompressStream sut = Zstd.newDecompressStream().maxBlockSize(CAP)) {
                restored = streamDecode(sut, frame);
            }

            // Then the payload comes back
            assertThat(restored).isEqualTo(TEXT);
        }

        @Test
        void uncappedDecoderDecodesCappedFrames() {
            // Given a frame compressed with a 4 KiB cap
            byte[] frame = cappedFrame(TEXT);

            // When decoding it without a cap
            byte[] restored = Zstd.decompress(frame);

            // Then the payload comes back
            assertThat(restored).isEqualTo(TEXT);
        }

        @Test
        void contextCappedBelowTheEncoderRejectsTheFrame() {
            // Given a default frame with 128 KiB blocks and a decoder capped at 4 KiB
            byte[] frame = Zstd.compress(RANDOM);
            try (ZstdDecompressContext sut = Zstd.newDecompressContext().maxBlockSize(CAP)) {
                // When decoding it one-shot
                ThrowingCallable result = () -> sut.decompress(frame);

                // Then the oversized block is rejected
                assertThatThrownBy(result).isInstanceOf(ZstdException.class);
            }
        }

        @Test
        void inputStreamCappedBelowTheEncoderRejectsTheFrame() throws IOException {
            // Given a default frame with 128 KiB blocks and an input stream capped at 4 KiB
            byte[] frame = Zstd.compress(RANDOM);
            try (ZstdInputStream sut = Zstd.newInputStream(new ByteArrayInputStream(frame)).maxBlockSize(CAP)) {
                // When reading it
                ThrowingCallable result = sut::readAllBytes;

                // Then the oversized block is rejected
                assertThatThrownBy(result).isInstanceOf(ZstdException.class);
            }
        }

        @Test
        void segmentStreamCappedBelowTheEncoderRejectsTheFrame() {
            // Given a default frame with 128 KiB blocks and a stream capped at 4 KiB
            byte[] frame = Zstd.compress(RANDOM);
            try (ZstdDecompressStream sut = Zstd.newDecompressStream().maxBlockSize(CAP)) {
                // When streaming it
                ThrowingCallable result = () -> streamDecode(sut, frame);

                // Then the oversized block is rejected
                assertThatThrownBy(result).isInstanceOf(ZstdException.class);
            }
        }

        @Test
        void cappedStreamingDecoderUsesLessMemory() {
            // Given a capped frame without a content size, so the decoder must buffer
            byte[] frame;
            try (ZstdCompressContext cctx = Zstd.newCompressContext().maxBlockSize(CAP).contentSize(false)) {
                frame = cctx.compress(TEXT);
            }
            try (ZstdDecompressStream capped = Zstd.newDecompressStream().maxBlockSize(CAP);
                 ZstdDecompressStream uncapped = Zstd.newDecompressStream()) {
                // When decoding it with and without the cap
                streamDecode(capped, frame);
                streamDecode(uncapped, frame);

                // Then the capped decoder allocated smaller buffers
                assertThat(capped.sizeOf().value()).isLessThan(uncapped.sizeOf().value());
            }
        }
    }

    @Nested
    class Validation {

        @ParameterizedTest
        @ValueSource(longs = {1, 1023, 128 * 1024 + 1, Integer.MAX_VALUE + 1L})
        void compressRejectsOutOfRangeSizes(long size) {
            // Given a compressor and a size outside [1 KiB, 128 KiB]
            try (ZstdCompressContext sut = Zstd.newCompressContext()) {
                // When setting it
                ThrowingCallable result = () -> sut.maxBlockSize(ZstdByteSize.of(size));

                // Then it is rejected before reaching zstd, naming the setter
                assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("maxBlockSize(" + size + ")");
            }
        }

        @ParameterizedTest
        @ValueSource(longs = {1, 1023, 128 * 1024 + 1, Integer.MAX_VALUE + 1L})
        void decompressRejectsOutOfRangeSizes(long size) {
            // Given a decompressor and a size outside [1 KiB, 128 KiB]
            try (ZstdDecompressContext sut = Zstd.newDecompressContext()) {
                // When setting it
                ThrowingCallable result = () -> sut.maxBlockSize(ZstdByteSize.of(size));

                // Then it is rejected before reaching zstd, naming the setter
                assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("maxBlockSize(" + size + ")");
            }
        }

        @Test
        void boundsAreOneKibToOneHundredTwentyEightKib() {
            // Given both sides' native bounds
            ZstdBounds compress = ZstdCompressParameter.MAX_BLOCK_SIZE.bounds();
            ZstdBounds decompress = ZstdDecompressParameter.MAX_BLOCK_SIZE.bounds();

            // When / Then they match zstd's documented range
            assertThat(compress.lowerBound()).isEqualTo(1024);
            assertThat(compress.upperBound()).isEqualTo(128 * 1024);
            assertThat(decompress.lowerBound()).isEqualTo(1024);
            assertThat(decompress.upperBound()).isEqualTo(128 * 1024);
        }

        @Test
        void nullIsRejected() {
            // Given both contexts
            try (ZstdCompressContext cctx = Zstd.newCompressContext();
                 ZstdDecompressContext dctx = Zstd.newDecompressContext()) {
                // When passing null
                ThrowingCallable compress = () -> cctx.maxBlockSize(null);
                ThrowingCallable decompress = () -> dctx.maxBlockSize(null);

                // Then both fail fast
                assertThatThrownBy(compress).isInstanceOf(NullPointerException.class);
                assertThatThrownBy(decompress).isInstanceOf(NullPointerException.class);
            }
        }
    }

    private static byte[] cappedFrame(byte[] payload) {
        try (ZstdCompressContext cctx = Zstd.newCompressContext().maxBlockSize(CAP)) {
            return cctx.compress(payload);
        }
    }

    /// Streams `frame` through `ds` in 1 KiB output steps, so the decoder buffers.
    private static byte[] streamDecode(ZstdDecompressStream ds, byte[] frame) {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = segmentOf(arena, frame);
            MemorySegment dst = arena.allocate(1024);
            long srcOff = 0;
            ZstdStreamResult r;
            do {
                r = ds.decompress(dst, src.asSlice(srcOff));
                srcOff += r.bytesConsumed();
                collected.writeBytes(bytesOf(dst, r.bytesProduced()));
            } while (!r.isComplete());
        }
        return collected.toByteArray();
    }

    /// Each block header of a single standard frame, as `{type, size}`.
    private static List<int[]> blockHeaders(byte[] frame) {
        List<int[]> blocks = new ArrayList<>();
        int pos = (int) ZstdFrame.header(frame).headerSize().value();
        boolean last;
        do {
            int header = (frame[pos] & 0xFF) | (frame[pos + 1] & 0xFF) << 8 | (frame[pos + 2] & 0xFF) << 16;
            last = (header & 1) == 1;
            int type = (header >>> 1) & 0b11;
            int size = header >>> 3;
            blocks.add(new int[] {type, size});
            // RLE blocks store one byte regardless of their content size
            pos += 3 + (type == 1 ? 1 : size);
        } while (!last);
        return blocks;
    }
}
