package io.github.dfa1.zstd;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

/// Shared low-level helpers for the segment-based tests: copy a `byte[]` into a
/// native segment, read it back out, and train a small dictionary from a common
/// structured schema. Kept in one place so the helpers cannot drift apart.
final class ZstdTestSupport {

    private static final long SEED = 0xC0FFEEL;

    /// Copy `bytes` into a freshly allocated native segment in `arena`.
    /// Allocates at least one byte so a zero-length payload still yields a valid
    /// (non-null) segment.
    static MemorySegment segmentOf(Arena arena, byte[] bytes) {
        MemorySegment seg = arena.allocate(Math.max(bytes.length, 1));
        MemorySegment.copy(bytes, 0, seg, JAVA_BYTE, 0, bytes.length);
        return seg;
    }

    /// Read the first `len` bytes out of `seg` into a heap `byte[]`.
    static byte[] bytesOf(MemorySegment seg, long len) {
        byte[] out = new byte[Math.toIntExact(len)];
        MemorySegment.copy(seg, JAVA_BYTE, 0, out, 0, out.length);
        return out;
    }

    /// `int`-length overload of [ZstdTestSupport#bytesOf(MemorySegment,long)].
    static byte[] bytesOf(MemorySegment seg, int len) {
        return bytesOf(seg, (long) len);
    }

    /// A small structured record for index `i` — the common schema dictionary
    /// tests train on and compress against. Deterministic in `i` so the same
    /// index always yields the same bytes.
    static byte[] sample(int i) {
        return ("{\"id\":" + i
                + ",\"user\":\"user_" + (i % 50)
                + "\",\"active\":" + (i % 2 == 0)
                + ",\"score\":" + (i * 7 % 1000)
                + ",\"tag\":\"event\"}").getBytes(StandardCharsets.UTF_8);
    }

    /// Train a dictionary from `sampleCount` structured [ZstdTestSupport#sample(int)]
    /// records using an 8 KiB dictionary buffer.
    static ZstdDictionary trainDictionary(int sampleCount) {
        List<byte[]> samples = new ArrayList<>();
        for (int i = 0; i < sampleCount; i++) {
            samples.add(sample(i));
        }
        return ZstdDictionary.train(samples, ZstdByteSize.ofKiB(8));
    }

    /// A dictionary that claims to be a full zstd dictionary (it starts with the
    /// dictionary magic number) but whose entropy tables are garbage, so any
    /// decoder that digests it eagerly rejects it as corrupted.
    static ZstdDictionary corruptDictionary() {
        byte[] raw = new byte[64];
        Arrays.fill(raw, (byte) 0xFF);
        raw[0] = 0x37;
        raw[1] = (byte) 0xA4;
        raw[2] = 0x30;
        raw[3] = (byte) 0xEC;
        return ZstdDictionary.of(raw);
    }

    /// `n` pseudo-random bytes from a fixed `seed`, so a failure reproduces.
    static byte[] randomBytes(long seed, int n) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    /// A spread of payloads for `@MethodSource`: boundaries (empty, single byte),
    /// incompressible random data, and highly compressible repeated data, across
    /// sizes. Counts are kept low (each case crosses the JNI/FFM boundary) and the
    /// seed is fixed so failures reproduce.
    static Stream<byte[]> bytes() {
        Random r = new Random(SEED);
        return Stream.of(
                new byte[0],
                new byte[]{0},
                new byte[]{(byte) 0xFF},
                random(r, 16),
                random(r, 1024),
                random(r, 64 * 1024),
                compressible(r, 1),
                compressible(r, 512),
                compressible(r, 4096),
                compressible(r, 64 * 1024),
                random(r, 1 + r.nextInt(8192)),
                compressible(r, 1 + r.nextInt(8192)));
    }

    /// The compression levels worth exercising: both extremes, default, and a low one.
    static Stream<ZstdCompressionLevel> levels() {
        return Stream.of(
                ZstdCompressionLevel.FASTEST,
                new ZstdCompressionLevel(1),
                ZstdCompressionLevel.DEFAULT,
                ZstdCompressionLevel.MAX);
    }

    private static byte[] random(Random r, int size) {
        byte[] b = new byte[size];
        r.nextBytes(b);
        return b;
    }

    /// Repetitive data with a little noise — the realistic "compresses well" case.
    private static byte[] compressible(Random r, int size) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            b[i] = (byte) ((i % 17 == 0) ? r.nextInt(256) : 'a' + (i % 8));
        }
        return b;
    }

    private ZstdTestSupport() {
        // no instances
    }
}
