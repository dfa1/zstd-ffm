package io.github.dfa1.zstd.rfc9842.demo;

import io.github.dfa1.zstd.Zstd;
import io.github.dfa1.zstd.ZstdByteSize;
import io.github.dfa1.zstd.ZstdCompressContext;
import io.github.dfa1.zstd.ZstdCompressDictionary;
import io.github.dfa1.zstd.ZstdDecompressContext;
import io.github.dfa1.zstd.ZstdDecompressDictionary;
import io.github.dfa1.zstd.ZstdFrame;
import io.github.dfa1.zstd.rfc9842.AvailableDictionaryHeader;
import io.github.dfa1.zstd.rfc9842.Rfc9842Frame;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/// Compresses/decompresses the `dcz` wire format against a pre-digested
/// dictionary, each touching native memory once and the JVM heap once via a
/// single shared `Arena` — not the extra native round trip the shorter
/// byte[]-overload chain (`Rfc9842Frame.wrap`/`unwrap(byte[], ...)` +
/// `ZstdCompressContext.compress`/`ZstdDecompressContext.decompress(byte[], ...)`)
/// would pay per call, each doing its own internal copy-in/copy-out.
///
/// [DczTestServer]'s dictionary side and [Rfc9842ClientDemo]/[PerfTestDemo]'s
/// client side all call this instead of each keeping their own copy — this
/// class used to be three (one `compressDcz` in `DczTestServer`, one
/// `decodeDcz` duplicated byte-for-byte in both clients), with the
/// compress/decompress halves neither named nor shaped as counterparts of
/// each other.
final class DczCodec {

    private DczCodec() {
    }

    /// @param payload             the plaintext to compress
    /// @param cctx                the compression context to compress with
    /// @param dictionary          the pre-digested dictionary to compress against
    /// @param availableDictionary the dictionary's hash, embedded in the `dcz` header
    /// @return the `dcz`-framed, compressed body
    static byte[] compress(byte[] payload, ZstdCompressContext cctx, ZstdCompressDictionary dictionary,
                            AvailableDictionaryHeader availableDictionary) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment in = arena.allocate(payload.length);
            MemorySegment.copy(payload, 0, in, JAVA_BYTE, 0, payload.length);

            ZstdByteSize bound = Zstd.compressBound(new ZstdByteSize(payload.length));
            MemorySegment dst = arena.allocate(Rfc9842Frame.HEADER_SIZE + bound.value());
            MemorySegment frameOut = dst.asSlice(Rfc9842Frame.HEADER_SIZE, bound.value());
            long written = cctx.compress(frameOut, in, dictionary);
            long total = Rfc9842Frame.wrap(dst, frameOut.asSlice(0, written), availableDictionary);

            byte[] body = new byte[(int) total];
            MemorySegment.copy(dst, JAVA_BYTE, 0, body, 0, body.length);
            return body;
        }
    }

    /// @param body                a `dcz`-framed, compressed body produced by [#compress]
    /// @param dctx                the decompression context to decompress with
    /// @param dictionary          the pre-digested dictionary to decompress against
    /// @param availableDictionary the dictionary's hash, verified against the `dcz` header
    /// @return the decompressed plaintext
    static byte[] decompress(byte[] body, ZstdDecompressContext dctx, ZstdDecompressDictionary dictionary,
                              AvailableDictionaryHeader availableDictionary) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dcz = arena.allocate(body.length);
            MemorySegment.copy(body, 0, dcz, JAVA_BYTE, 0, body.length);

            MemorySegment frame = Rfc9842Frame.unwrap(dcz, availableDictionary);
            ZstdByteSize size = ZstdFrame.decompressedSize(frame);
            MemorySegment out = arena.allocate(size.value());
            long written = dctx.decompress(out, frame, dictionary);

            byte[] payload = new byte[(int) written];
            MemorySegment.copy(out, JAVA_BYTE, 0, payload, 0, payload.length);
            return payload;
        }
    }
}
