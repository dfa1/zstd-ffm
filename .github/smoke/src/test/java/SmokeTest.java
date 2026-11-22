// Dependencies (io.github.dfa1.zstd:zstd and the per-arch native jar) are
// resolved by ../pom.xml from -Dzstd.version/-Dzstd.classifier, so the
// workflow can pin the released version and the matrix's native jar.

import io.github.dfa1.zstd.Zstd;
import io.github.dfa1.zstd.ZstdByteSize;
import io.github.dfa1.zstd.ZstdCompressContext;
import io.github.dfa1.zstd.ZstdCompressDictionary;
import io.github.dfa1.zstd.ZstdCompressionLevel;
import io.github.dfa1.zstd.ZstdCompressParameter;
import io.github.dfa1.zstd.ZstdCompressStream;
import io.github.dfa1.zstd.ZstdDecompressContext;
import io.github.dfa1.zstd.ZstdDecompressDictionary;
import io.github.dfa1.zstd.ZstdDecompressParameter;
import io.github.dfa1.zstd.ZstdDecompressStream;
import io.github.dfa1.zstd.ZstdDictionary;
import io.github.dfa1.zstd.ZstdEndDirective;
import io.github.dfa1.zstd.ZstdErrorCode;
import io.github.dfa1.zstd.ZstdException;
import io.github.dfa1.zstd.ZstdFrame;
import io.github.dfa1.zstd.ZstdFrameHeader;
import io.github.dfa1.zstd.ZstdFrameProgression;
import io.github.dfa1.zstd.ZstdFrameType;
import io.github.dfa1.zstd.ZstdInputStream;
import io.github.dfa1.zstd.ZstdMagicVariant;
import io.github.dfa1.zstd.ZstdOutputStream;
import io.github.dfa1.zstd.ZstdResetDirective;
import io.github.dfa1.zstd.ZstdSkippableContent;
import io.github.dfa1.zstd.ZstdStreamResult;
import io.github.dfa1.zstd.ZstdWindowLog;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/// Smoke test for a published zstd-ffm release: proves the bundled native
/// library loads and links correctly on the host OS/arch/libc, and that
/// off-heap `MemorySegment` interop and native struct layouts (streaming
/// buffers, frame progression) work there.
/// Run against a release from Maven Central, one arch per CI matrix leg, via
/// `mvn test` — a JUnit failure here fails just that one check, so a single
/// broken symbol doesn't hide every other result behind it.
///
/// This deliberately does *not* re-derive full API correctness — that's the
/// job of the module's JUnit suite, which runs once per PR and fails with a
/// precise, single-platform stack trace. A check only belongs here if a local
/// build passing it would NOT prove the released native artifact behaves the
/// same way on this specific platform (link failure, ABI/struct-layout
/// mismatch, native error-code marshaling). Pure-Java logic identical on every
/// platform (enum bounds sweeps, dictionary-training algorithm variants,
/// record equals/hashCode) belongs in the unit suite, not here.
class SmokeTest {

    private static final String PLATFORM = System.getProperty("os.name") + "/" + System.getProperty("os.arch");

    private static ZstdDictionary dict;
    private static byte[] message;

    @BeforeAll
    static void trainDictionary() {
        List<byte[]> samples = jsonSamples();
        dict = ZstdDictionary.train(samples, ZstdByteSize.ofKiB(8));
        message = samples.get(7);
    }

    @Test
    void versionAndSizing() {
        check(!Zstd.version().toString().isBlank(), "version() returned blank");
        check(Zstd.version().number() > 0, "version().number() not positive");

        int min = ZstdCompressionLevel.FASTEST.value();
        int max = ZstdCompressionLevel.MAX.value();
        int def = ZstdCompressionLevel.DEFAULT.value();
        check(min < max, "FASTEST >= MAX");
        check(def >= min && def <= max, "DEFAULT out of [FASTEST, MAX]");

        check(Zstd.compressBound(ZstdByteSize.of(1000)).value() >= 1000, "compressBound() below input size");
        check(Zstd.estimateCompressContextSize(ZstdCompressionLevel.DEFAULT).value() > 0,
                "estimateCompressContextSize() not positive");
        check(Zstd.estimateDecompressContextSize().value() > 0, "estimateDecompressContextSize() not positive");
        check(Zstd.estimateCompressDictSize(ZstdByteSize.of(4096), ZstdCompressionLevel.DEFAULT).value() > 0,
                "estimateCompressDictSize() not positive");
        check(Zstd.estimateDecompressDictSize(ZstdByteSize.of(4096)).value() > 0, "estimateDecompressDictSize() not positive");
    }

    @Test
    void coreRoundTrip() {
        byte[] original = sampleText();

        byte[] compressedDefault = Zstd.compress(original);
        checkArrayEquals(original, Zstd.decompress(compressedDefault), "compress(byte[]) round-trip mismatch");

        byte[] compressed = Zstd.compress(original, ZstdCompressionLevel.MAX);
        checkArrayEquals(original, Zstd.decompress(compressed), "compress(byte[], level) round-trip mismatch");
        check(compressed.length < original.length, "expected compression to shrink the input");
    }

    @Test
    void explicitBoundDecompress() {
        byte[] original = sampleText();
        byte[] compressed = Zstd.compress(original);

        byte[] restored = Zstd.decompress(compressed, ZstdByteSize.of(original.length));
        checkArrayEquals(original, restored, "decompress(byte[], maxSize) mismatch");

        try {
            Zstd.decompress(compressed, ZstdByteSize.of(1));
            throw new AssertionError("expected ZstdException for an undersized maxSize on " + PLATFORM);
        } catch (ZstdException expected) {
            check(expected.code() == ZstdErrorCode.DST_SIZE_TOO_SMALL,
                    "expected DST_SIZE_TOO_SMALL, got " + expected.code());
        }
    }

    @Test
    void zeroCopyFrameSize() {
        byte[] original = sampleText();
        byte[] compressed = Zstd.compress(original);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment frame = toNative(arena, compressed);
            check(Zstd.decompressedSize(frame).value() == original.length, "decompressedSize(MemorySegment) mismatch");
        }
    }

    @Test
    void corruptAndInvalidInput() {
        byte[] original = sampleText();
        try (ZstdCompressContext cctx = Zstd.newCompressContext().checksum(true)) {
            byte[] framed = cctx.compress(original);
            byte[] corrupted = framed.clone();
            // The last byte is part of the 4-byte content checksum trailer, so
            // flipping it corrupts the checksum itself rather than the payload —
            // deterministically triggers CHECKSUM_WRONG regardless of how the
            // payload happens to compress on a given platform.
            corrupted[corrupted.length - 1] ^= 0xFF;
            try {
                Zstd.decompress(corrupted, ZstdByteSize.of(original.length));
                throw new AssertionError("expected ZstdException for a corrupted checksum on " + PLATFORM);
            } catch (ZstdException expected) {
                check(expected.code() == ZstdErrorCode.CHECKSUM_WRONG,
                        "expected CHECKSUM_WRONG, got " + expected.code());
            }
        }

        try {
            Zstd.decompress("not a zstd frame".getBytes(StandardCharsets.UTF_8));
            throw new AssertionError("expected ZstdException for non-zstd input on " + PLATFORM);
        } catch (ZstdException expected) {
            check(expected.getMessage() != null && !expected.getMessage().isBlank(),
                    "ZstdException message was blank for non-zstd input");
        }
    }

    @Test
    void frameIntrospection() {
        byte[] original = sampleText();
        byte[] compressed = Zstd.compress(original);

        check(ZstdFrame.isZstdFrame(compressed), "isZstdFrame(byte[]) false for a real frame");
        check(!ZstdFrame.isZstdFrame("plain text, not zstd".getBytes(StandardCharsets.UTF_8)),
                "isZstdFrame(byte[]) true for non-zstd data");
        check(ZstdFrame.compressedSize(compressed).value() == compressed.length, "compressedSize(byte[]) mismatch");
        check(ZstdFrame.decompressedSize(compressed).value() == original.length, "decompressedSize(byte[]) mismatch");
        check(ZstdFrame.decompressedBound(compressed).value() >= original.length, "decompressedBound(byte[]) too small");
        check(ZstdFrame.decompressionMargin(compressed).value() >= 0, "decompressionMargin(byte[]) negative");
        check(!ZstdFrame.dictId(compressed).isPresent(), "dictId(byte[]) expected NONE for a non-dictionary frame");
        check(!ZstdFrame.isSkippableFrame(compressed), "isSkippableFrame(byte[]) true for a standard frame");

        long headerSize = ZstdFrame.headerSize(compressed).value();
        check(headerSize > 0 && headerSize <= compressed.length, "headerSize(byte[]) out of range");

        ZstdFrameHeader header = ZstdFrame.header(compressed);
        check(header.contentSize().isPresent() && header.contentSize().get().value() == original.length,
                "header(byte[]).contentSize() mismatch");
        check(header.frameType() == ZstdFrameType.STANDARD, "header(byte[]).frameType() expected STANDARD");
        check(header.headerSize().value() == headerSize, "header(byte[]).headerSize() disagreed with headerSize(byte[])");
        check(!header.hasChecksum(), "header(byte[]).hasChecksum() expected false (checksum not enabled)");
    }

    @Test
    void skippableFrames() {
        byte[] payload = "smoke-test skippable payload".getBytes(StandardCharsets.UTF_8);
        byte[] frame = ZstdFrame.writeSkippableFrame(payload, ZstdMagicVariant.of(3));

        check(ZstdFrame.isSkippableFrame(frame), "isSkippableFrame(byte[]) false for a written skippable frame");
        check(ZstdFrame.isZstdFrame(frame), "isZstdFrame(byte[]) should be true for a skippable frame too");

        ZstdSkippableContent read = ZstdFrame.readSkippableFrame(frame);
        checkArrayEquals(read.content(), payload, "readSkippableFrame() content mismatch");
        check(read.magicVariant().value() == 3, "readSkippableFrame() magicVariant mismatch");
    }

    @Test
    void compressContextAdvancedParameters() {
        byte[] original = sampleText();
        try (ZstdCompressContext cctx = Zstd.newCompressContext()) {
            cctx.level(ZstdCompressionLevel.of(5))
                    .checksum(true)
                    .longDistanceMatching(true)
                    .windowLog(ZstdWindowLog.of(20))
                    .parameter(ZstdCompressParameter.STRATEGY, 3);

            byte[] compressed = cctx.compress(original);
            checkArrayEquals(original, Zstd.decompress(compressed, ZstdByteSize.of(original.length)),
                    "advanced-parameter round-trip mismatch");
            check(cctx.sizeOf().value() > 0, "cctx.sizeOf() not positive");

            cctx.reset(ZstdResetDirective.SESSION_AND_PARAMETERS);
            byte[] afterReset = cctx.level(ZstdCompressionLevel.of(3)).compress(original);
            checkArrayEquals(original, Zstd.decompress(afterReset, ZstdByteSize.of(original.length)),
                    "compress after SESSION_AND_PARAMETERS reset mismatch");
        }
    }

    @Test
    void decompressContextAdvanced() {
        byte[] original = sampleText();
        try (ZstdCompressContext cctx = Zstd.newCompressContext().windowLog(ZstdWindowLog.of(23));
             ZstdDecompressContext dctx = Zstd.newDecompressContext()) {
            dctx.windowLogMax(ZstdWindowLog.of(24));
            byte[] restored = dctx.decompress(cctx.compress(original), ZstdByteSize.of(original.length));
            checkArrayEquals(original, restored, "windowLogMax() decompress round-trip mismatch");
            check(dctx.sizeOf().value() > 0, "dctx.sizeOf() not positive");

            dctx.reset(ZstdResetDirective.PARAMETERS);
            dctx.parameter(ZstdDecompressParameter.WINDOW_LOG_MAX, 24);
            byte[] restoredAfterReset = dctx.decompress(cctx.compress(original), ZstdByteSize.of(original.length));
            checkArrayEquals(original, restoredAfterReset, "decompress after PARAMETERS reset + parameter() mismatch");
        }
    }

    @Test
    void prefixCompression() {
        byte[] previousVersion = sampleText();
        byte[] newVersion = (new String(previousVersion, StandardCharsets.UTF_8) + " and a tiny delta at the end")
                .getBytes(StandardCharsets.UTF_8);

        try (Arena arena = Arena.ofConfined();
             ZstdCompressContext cctx = Zstd.newCompressContext();
             ZstdDecompressContext dctx = Zstd.newDecompressContext()) {
            MemorySegment prefix = toNative(arena, previousVersion);

            cctx.refPrefix(prefix);
            byte[] delta = cctx.compress(newVersion);
            check(delta.length < newVersion.length, "prefix-compressed delta not smaller than the input");

            dctx.refPrefix(prefix);
            byte[] restored = dctx.decompress(delta, ZstdByteSize.of(newVersion.length));
            checkArrayEquals(newVersion, restored, "prefix compression round-trip mismatch");
        }
    }

    @Test
    void multiThreadRoundTrip() {
        // Native worker-thread spawning is exactly the platform-specific
        // behavior this smoke suite exists for: pthreads on glibc/musl,
        // Win32 _beginthreadex on Windows. 2 MiB clears zstd's 512 KiB
        // job-size minimum so workers actually engage.
        byte[] original = new String(sampleText(), StandardCharsets.UTF_8).repeat(60)
                .getBytes(StandardCharsets.UTF_8);
        try (ZstdCompressContext cctx = Zstd.newCompressContext()) {
            cctx.parameter(ZstdCompressParameter.NB_WORKERS, 2);
            byte[] compressed = cctx.compress(original);
            checkArrayEquals(original, Zstd.decompress(compressed, ZstdByteSize.of(original.length)),
                    "multithreaded (NB_WORKERS=2) round-trip mismatch");
        }
    }

    @Test
    void contextDictionaryPaths() {
        try (ZstdCompressContext cctx = Zstd.newCompressContext();
             ZstdDecompressContext dctx = Zstd.newDecompressContext()) {
            byte[] compressed = cctx.compress(message, dict);
            byte[] restored = dctx.decompress(compressed, ZstdByteSize.of(message.length), dict);
            checkArrayEquals(message, restored, "per-call dictionary round-trip mismatch");
        }
    }

    @Test
    void digestedDictionaries() {
        try (ZstdCompressDictionary cdict = dict.compressDict();
             ZstdDecompressDictionary ddict = dict.decompressDict();
             ZstdCompressContext cctx = Zstd.newCompressContext();
             ZstdDecompressContext dctx = Zstd.newDecompressContext()) {
            byte[] compressed = cctx.compress(message, cdict);
            byte[] restored = dctx.decompress(compressed, ZstdByteSize.of(message.length), ddict);
            checkArrayEquals(message, restored, "digested-dictionary round-trip mismatch");
            check(cdict.sizeOf().value() > 0, "ZstdCompressDictionary.sizeOf() not positive");
            check(ddict.sizeOf().value() > 0, "ZstdDecompressDictionary.sizeOf() not positive");
        }
    }

    @Test
    void zeroCopyContextCompression() {
        try (Arena arena = Arena.ofConfined();
             ZstdCompressContext cctx = Zstd.newCompressContext();
             ZstdDecompressContext dctx = Zstd.newDecompressContext()) {
            byte[] original = sampleText();
            MemorySegment src = toNative(arena, original);

            MemorySegment dst = arena.allocate(Zstd.compressBound(ZstdByteSize.of(src.byteSize())).value());
            long written = cctx.compress(dst, src);
            MemorySegment restored = arena.allocate(original.length);
            long read = dctx.decompress(restored, dst.asSlice(0, written));
            checkArrayEquals(original, toByteArray(restored, read),
                    "compress/decompress(MemorySegment, MemorySegment) content mismatch");
        }
    }

    @Test
    void streamingZeroCopy() {
        try (ZstdCompressStream cs = Zstd.newCompressStream()) {
            check(cs.sizeOf().value() > 0, "no-arg ZstdCompressStream.sizeOf() not positive");
        }

        byte[] original = sampleText();
        try (Arena arena = Arena.ofConfined();
             ZstdCompressStream cs = Zstd.newCompressStream(ZstdCompressionLevel.DEFAULT)) {
            MemorySegment src = toNative(arena, original);
            MemorySegment dst = arena.allocate(Zstd.compressBound(ZstdByteSize.of(src.byteSize())).value());
            ZstdStreamResult result = cs.compress(dst, src, ZstdEndDirective.END);
            check(result.isComplete(), "single-shot ZstdCompressStream.compress(END) did not complete");
            check(result.bytesConsumed() == original.length, "ZstdCompressStream consumed byte count mismatch");
            check(cs.sizeOf().value() > 0, "ZstdCompressStream.sizeOf() not positive");

            // Reads a native struct (ZSTD_frameProgression) through fixed field
            // offsets — worth checking per platform since padding/alignment is
            // ABI-sensitive, unlike the rest of this method's plain size_t returns.
            ZstdFrameProgression progression = cs.progress();
            check(progression.produced() >= result.bytesProduced(),
                    "ZstdFrameProgression.produced() smaller than bytes actually produced");

            byte[] compressed = toByteArray(dst, result.bytesProduced());
            try (ZstdDecompressStream ds = Zstd.newDecompressStream()) {
                MemorySegment frameSeg = toNative(arena, compressed);
                MemorySegment out = arena.allocate(original.length);
                ZstdStreamResult decodeResult = ds.decompress(out, frameSeg);
                check(decodeResult.isComplete(), "ZstdDecompressStream.decompress() did not complete");
                checkArrayEquals(original, toByteArray(out, decodeResult.bytesProduced()),
                        "zero-copy streaming round-trip mismatch");
                check(ds.sizeOf().value() > 0, "ZstdDecompressStream.sizeOf() not positive");
            }
        }
    }

    @Test
    void streamingIo() throws IOException {
        byte[] original = sampleText();

        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        try (ZstdOutputStream zout = Zstd.newOutputStream(sink)) {
            zout.write(original);
        }
        try (ZstdInputStream zin = Zstd.newInputStream(new ByteArrayInputStream(sink.toByteArray()))) {
            checkArrayEquals(original, zin.readAllBytes(), "ZstdOutputStream/ZstdInputStream round-trip mismatch");
        }

        ByteArrayOutputStream sinkPledged = new ByteArrayOutputStream();
        try (ZstdOutputStream zout =
                     Zstd.newPledgedOutputStream(sinkPledged, ZstdCompressionLevel.of(5), ZstdByteSize.of(original.length))) {
            zout.write(original);
        }
        byte[] pledgedFrame = sinkPledged.toByteArray();
        check(ZstdFrame.header(pledgedFrame).contentSize().orElseThrow().value() == original.length,
                "newPledgedOutputStream() did not stamp the declared content size into the frame header");
        checkArrayEquals(original, Zstd.decompress(pledgedFrame), "newPledgedOutputStream() round-trip mismatch");
    }

    private static byte[] sampleText() {
        return "the quick brown fox ".repeat(2000).getBytes(StandardCharsets.UTF_8);
    }

    private static List<byte[]> jsonSamples() {
        List<byte[]> samples = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            samples.add(("{\"id\":" + i + ",\"user\":\"user_" + (i % 100) + "\",\"event\":\"click\"}")
                    .getBytes(StandardCharsets.UTF_8));
        }
        return samples;
    }

    private static MemorySegment toNative(Arena arena, byte[] data) {
        MemorySegment seg = arena.allocate(Math.max(data.length, 1));
        MemorySegment.copy(data, 0, seg, JAVA_BYTE, 0, data.length);
        return seg;
    }

    private static byte[] toByteArray(MemorySegment seg, long len) {
        byte[] out = new byte[Math.toIntExact(len)];
        MemorySegment.copy(seg, JAVA_BYTE, 0, out, 0, out.length);
        return out;
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what + " on " + PLATFORM);
        }
    }

    private static void checkArrayEquals(byte[] expected, byte[] actual, String what) {
        check(Arrays.equals(expected, actual), what);
    }
}
