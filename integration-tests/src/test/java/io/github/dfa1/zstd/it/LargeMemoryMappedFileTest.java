package io.github.dfa1.zstd.it;

import com.github.luben.zstd.ZstdOutputStream;
import io.github.dfa1.zstd.ZstdCompressStream;
import io.github.dfa1.zstd.ZstdCompressionLevel;
import io.github.dfa1.zstd.ZstdDecompressStream;
import io.github.dfa1.zstd.ZstdEndDirective;
import io.github.dfa1.zstd.ZstdStreamResult;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Proves the zero-copy claim from `docs/explanation.md` that the `MemorySegment`
/// API removes the 2 GiB cap that `byte[]` / `ByteBuffer` impose, and that this
/// project's segment-based streaming compressor round-trips a file larger than
/// [Integer#MAX_VALUE] bytes read straight off a single memory mapping — and
/// contrasts that with the reference `zstd-jni` binding, whose zero-copy surface
/// is entirely `ByteBuffer`-based and hits the same `int` cap with no way around
/// it (it falls back to the classic heap-copying stream API instead).
///
/// This test creates a file just over 2 GiB on disk, memory-maps it, and streams
/// the whole thing through compress/decompress, so it is slow and disk-hungry —
/// far too heavy for a plain `mvn test`/`mvn verify` or CI. It is therefore gated
/// behind the `zstd.test.large` system property and skipped unless it is `true`.
///
/// Run it explicitly with:
///
/// ```
/// ./mvnw -pl integration-tests test -Dzstd.test.large=true -Dtest=LargeMemoryMappedFileTest
/// ```
@EnabledIfSystemProperty(named = "zstd.test.large", matches = "true")
class LargeMemoryMappedFileTest {

    /// A size strictly greater than [Integer#MAX_VALUE], so the `int`-indexed
    /// [ByteBuffer] path cannot address it. About 2.25 GiB.
    private static final long FILE_SIZE = (long) Integer.MAX_VALUE + 256L * 1024 * 1024;

    /// Chunk used both to write the file and to feed the streaming buffers.
    private static final int CHUNK = 8 * 1024 * 1024;

    @TempDir
    private static Path tempDir;

    private static Path file;

    /// Written once and shared by every test in this class — regenerating a
    /// 2.25 GiB file per test would double the already-heavy I/O for no benefit,
    /// since every test here only reads it.
    @BeforeAll
    static void writeSharedFile() throws IOException {
        file = tempDir.resolve("large-2gib.bin");
        writeDeterministicFile(file);
    }

    /// Deterministic content: every byte is a pure function of its absolute file
    /// offset, so any offset (including past 2 GiB) can be regenerated for
    /// verification without keeping a multi-gigabyte reference buffer around.
    private static byte expectedByteAt(long offset) {
        return (byte) ('a' + (offset % 8));
    }

    @Test
    void segmentMappingBeatsByteBufferAndRoundTripsPast2Gib() throws IOException {
        // Given the shared file, just over the Integer.MAX_VALUE byte boundary
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
             Arena arena = Arena.ofConfined()) {

            // When the classic int-indexed ByteBuffer overload is asked to map
            // more than Integer.MAX_VALUE bytes
            ThrowingCallable byteBufferMap =
                    () -> channel.map(FileChannel.MapMode.READ_ONLY, 0, FILE_SIZE);

            // Then it rejects the request outright
            assertThatThrownBy(byteBufferMap).isInstanceOf(IllegalArgumentException.class);

            // When the long-indexed FFM overload maps the same range in one call
            MemorySegment mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, FILE_SIZE, arena);

            // Then the whole file is addressable from a single segment
            assertThat(mapped.byteSize()).isEqualTo(FILE_SIZE);

            // And reads at offsets past Integer.MAX_VALUE resolve correctly,
            // proving addressing beyond the int boundary
            long[] probes = {
                (long) Integer.MAX_VALUE,
                (long) Integer.MAX_VALUE + 1,
                (long) Integer.MAX_VALUE + 123_456_789L,
                FILE_SIZE - 1
            };
            for (long offset : probes) {
                assertThat(mapped.get(JAVA_BYTE, offset)).isEqualTo(expectedByteAt(offset));
            }

            // When the mapped segment is streamed through the zero-copy compressor
            byte[] frame = compressFromSegment(arena, mapped);

            // Then decompressing verifies every byte in place against the same
            // offset -> byte function, without materializing 2.25 GiB anywhere
            long produced = verifyDecompress(arena, frame);
            assertThat(produced).isEqualTo(FILE_SIZE);
        }
    }

    @Test
    void zstdJniCannotZeroCopyMapTheWholeFileButItsStreamApiStillCompressesIt() throws IOException {
        // Given the shared file, and zstd-jni's zero-copy surface — which is
        // entirely java.nio.ByteBuffer, backed by the same 3-arg FileChannel.map
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            // When it is asked to map the whole file for a zero-copy compress call
            ThrowingCallable byteBufferMap =
                    () -> channel.map(FileChannel.MapMode.READ_ONLY, 0, FILE_SIZE);

            // Then it hits the exact same int cap zstd-jni's ByteBuffer API cannot
            // work around: there is no long-indexed mapping in zstd-jni to fall
            // back to, unlike the FFM MemorySegment path above
            assertThatThrownBy(byteBufferMap).isInstanceOf(IllegalArgumentException.class);
        }

        // When zstd-jni instead falls back to its classic stream API, copying
        // through a heap byte[] buffer rather than mapping the file at all
        byte[] frame = compressWithZstdJni(file);

        // Then it does successfully compress the whole file, and the frame it
        // produced decodes correctly through this library's own zero-copy
        // streaming decompressor — proving real format interop at this size
        try (Arena arena = Arena.ofConfined()) {
            long produced = verifyDecompress(arena, frame);
            assertThat(produced).isEqualTo(FILE_SIZE);
        }
    }

    /// Compresses `file` with the reference `zstd-jni` binding via its classic
    /// [ZstdOutputStream], reading through a reused heap `byte[]` buffer — the
    /// heap-copying path zstd-jni falls back to since it has no `long`-indexed
    /// zero-copy alternative.
    private static byte[] compressWithZstdJni(Path file) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), CHUNK);
             ZstdOutputStream out = new ZstdOutputStream(frame, 1)) {
            byte[] buffer = new byte[CHUNK];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
        }
        return frame.toByteArray();
    }

    /// Writes [#FILE_SIZE] bytes to `file` in [#CHUNK]-sized batches. Each byte is
    /// [#expectedByteAt(long)] of its absolute offset, so no random source and no
    /// full-file buffer are needed.
    private static void writeDeterministicFile(Path file) throws IOException {
        byte[] chunk = new byte[CHUNK];
        try (FileChannel channel = FileChannel.open(
                file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            long written = 0;
            while (written < FILE_SIZE) {
                int len = (int) Math.min(CHUNK, FILE_SIZE - written);
                for (int i = 0; i < len; i++) {
                    chunk[i] = expectedByteAt(written + i);
                }
                ByteBuffer buffer = ByteBuffer.wrap(chunk, 0, len);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                written += len;
            }
        }
    }

    /// Streams the whole `source` segment through [ZstdCompressStream], always
    /// under [ZstdEndDirective#END], slicing from a running offset to the end of
    /// the segment each call and looping until the frame is complete — the same
    /// shape as `ZstdSegmentStreamTest.Chunked`. `ZstdStreamBuffer#set` only
    /// points a native struct at `src`; it never copies, so handing the entire
    /// remaining (multi-gigabyte) slice in each call is free — consumption per
    /// call is bounded by `dst`'s capacity, not by the slice length. The payload
    /// is highly compressible, so the resulting frame is small enough to
    /// accumulate in memory.
    private static byte[] compressFromSegment(Arena arena, MemorySegment source) {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        MemorySegment dst = arena.allocate(CHUNK);
        byte[] scratch = new byte[CHUNK];
        try (ZstdCompressStream cs = new ZstdCompressStream(new ZstdCompressionLevel(1))) {
            long srcOff = 0;
            long total = source.byteSize();
            ZstdStreamResult r;
            do {
                MemorySegment srcSlice = source.asSlice(srcOff, total - srcOff);
                r = cs.compress(dst, srcSlice, ZstdEndDirective.END);
                srcOff += r.bytesConsumed();
                drain(dst, r.bytesProduced(), scratch, frame);
            } while (!r.isComplete());
        }
        return frame.toByteArray();
    }

    /// Copies the first `produced` bytes of `dst` into `frame` via a reused
    /// `scratch` array, avoiding a fresh allocation per streaming step.
    private static void drain(MemorySegment dst, long produced, byte[] scratch, ByteArrayOutputStream frame) {
        int n = (int) produced;
        MemorySegment.copy(dst, JAVA_BYTE, 0, scratch, 0, n);
        frame.write(scratch, 0, n);
    }

    /// Decompresses `frame` through [ZstdDecompressStream], verifying each output
    /// chunk in place against [#expectedByteAt(long)] using a running absolute
    /// output offset, so the ~2.25 GiB decompressed stream is never held in full.
    ///
    /// @return the total number of decompressed bytes produced
    private static long verifyDecompress(Arena arena, byte[] frame) {
        MemorySegment src = arena.allocate(frame.length);
        MemorySegment.copy(frame, 0, src, JAVA_BYTE, 0, frame.length);
        MemorySegment dst = arena.allocate(CHUNK);

        long outOff = 0;
        try (ZstdDecompressStream ds = new ZstdDecompressStream()) {
            long srcOff = 0;
            ZstdStreamResult r;
            do {
                MemorySegment srcSlice = src.asSlice(srcOff, frame.length - srcOff);
                r = ds.decompress(dst, srcSlice);
                srcOff += r.bytesConsumed();
                outOff += verifyChunk(dst, r.bytesProduced(), outOff);
            } while (srcOff < frame.length || !r.isComplete());
        }
        return outOff;
    }

    /// Checks that `produced` bytes at the front of `dst` match the deterministic
    /// content starting at absolute output offset `outOff`.
    ///
    /// @return `produced`, so callers can advance the running offset
    private static long verifyChunk(MemorySegment dst, long produced, long outOff) {
        for (long i = 0; i < produced; i++) {
            byte actual = dst.get(JAVA_BYTE, i);
            byte expected = expectedByteAt(outOff + i);
            if (actual != expected) {
                assertThat(actual)
                        .as("byte at output offset %d", outOff + i)
                        .isEqualTo(expected);
            }
        }
        return produced;
    }
}
