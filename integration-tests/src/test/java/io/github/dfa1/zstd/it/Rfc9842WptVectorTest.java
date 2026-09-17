package io.github.dfa1.zstd.it;

import io.github.dfa1.zstd.ZstdByteSize;
import io.github.dfa1.zstd.ZstdDecompressContext;
import io.github.dfa1.zstd.ZstdDecompressDictionary;
import io.github.dfa1.zstd.ZstdDictionary;
import io.github.dfa1.zstd.ZstdFrame;
import io.github.dfa1.zstd.rfc9842.AvailableDictionaryHeader;
import io.github.dfa1.zstd.rfc9842.Rfc9842Exception;
import io.github.dfa1.zstd.rfc9842.Rfc9842Frame;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Decodes the `dcz` vectors from web-platform-tests — the only fixtures in
/// this repository produced by an RFC 9842 implementation we did not write,
/// and therefore the only real evidence that our `dcz` support interoperates.
///
/// Provenance: `fetch/compression-dictionary/resources/compressed-data.py` in
/// [web-platform-tests](https://github.com/web-platform-tests/wpt), the suite
/// browsers are tested against. The bytes were produced with the zstd CLI and
/// `openssl` following the recipe in the spec author's own notes
/// ([pmeenan/compression-dictionary-notes](https://github.com/pmeenan/compression-dictionary-notes/blob/main/cli.md)):
///
/// ```
/// echo "This is a test dictionary." > /tmp/dict
/// echo -n "This is compressed test data using a test dictionary" > /tmp/data
/// echo -en '\x5e\x2a\x4d\x18\x20\x00\x00\x00' > /tmp/out.dcz
/// openssl dgst -sha256 -binary /tmp/dict >> /tmp/out.dcz
/// zstd -D /tmp/dict -f -o /tmp/tmp.zstd /tmp/data
/// cat /tmp/tmp.zstd >> /tmp/out.dcz
/// ```
///
/// Passing this test means three independent things agree with the outside
/// world: the 8-byte skippable-frame header we expect, the SHA-256 we compute
/// over a dictionary (against one computed by `openssl`), and our
/// dictionary-based decode (against a frame produced by the zstd CLI).
///
/// The vectors are inlined rather than vendored as binary files because they
/// are under 100 bytes each and their provenance — the commands above — is the
/// part worth keeping next to them. WPT stores them inline for the same reason.
class Rfc9842WptVectorTest {

    /// `echo "This is a test dictionary." > /tmp/dict` — note the trailing
    /// newline `echo` adds, which is part of the hashed dictionary content.
    ///
    /// An ordinary text file: no ZDICT magic, so zstd's `dct_auto` loads it as
    /// raw content, which is what RFC 9842 §5 specifies ("a 'raw' dictionary
    /// type ... treated as a raw dictionary as per Section 5 of [ZSTD]").
    private static final byte[] DICTIONARY =
            "This is a test dictionary.\n".getBytes(StandardCharsets.UTF_8);

    private static final String DCZ =
            "5e2a4d1820000000"
            + "53969bcf5e960e0edbf0a4bdde6b0b3e9381e156de7f5b91ce8391624270f416"
            + "28b52ffd2434f5000098636f6d70726573736564617461207573696e67030059"
            + "f97354462726109e99f2bc";

    private static final String LARGE_DCZ =
            "5e2a4d1820000000"
            + "53969bcf5e960e0edbf0a4bdde6b0b3e9381e156de7f5b91ce8391624270f416"
            + "28b52ffd645c00fd0100f4022064646974696f6e616c617461207468617420616c"
            + "736f207265666572656e6365732074686520636f6e74656e742e0400602d72352b"
            + "bb3ca0ceed19040c4b9e2f";

    private static final String EXPECTED =
            "This is compressed test data using a test dictionary";

    private static final String LARGE_EXPECTED =
            "This is a test dictionary. ".repeat(10)
            + "This is additional test data that also references the test dictionary content.";

    private static Stream<Arguments> vectors() {
        return Stream.of(
                Arguments.of(Named.of("dcz_data", DCZ), EXPECTED),
                Arguments.of(Named.of("large_dcz_data", LARGE_DCZ), LARGE_EXPECTED));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    void decodesDczProducedByTheZstdCli(String hex, String expected) {
        // Given
        byte[] dcz = HexFormat.of().parseHex(hex);
        ZstdDictionary dictionary = ZstdDictionary.of(DICTIONARY);
        AvailableDictionaryHeader hash = AvailableDictionaryHeader.of(dictionary);

        // When
        byte[] frame = Rfc9842Frame.unwrap(dcz, hash);
        byte[] payload;
        try (ZstdDecompressContext dctx = new ZstdDecompressContext();
             ZstdDecompressDictionary dict = dictionary.decompressDict()) {
            payload = dctx.decompress(frame, ZstdByteSize.ofKiB(64), dict);
        }

        // Then
        assertThat(new String(payload, StandardCharsets.UTF_8)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    void carriesNoDictionaryIdBecauseTheDictionaryIsRawContent(String hex, String ignoredExpected) {
        // Given
        byte[] dcz = HexFormat.of().parseHex(hex);
        AvailableDictionaryHeader hash = AvailableDictionaryHeader.of(ZstdDictionary.of(DICTIONARY));

        // When
        byte[] frame = Rfc9842Frame.unwrap(dcz, hash);

        // Then: a raw-content dictionary has no id, so neither does the frame.
        // A ZDICT-trained dictionary would stamp its id here, and a peer that
        // loaded the same bytes as raw content could not then decode the frame.
        assertThat(ZstdFrame.dictId(frame).raw()).isZero();
    }

    @Test
    void rejectsAVectorWhoseDictionaryHashWasTampered() {
        // Given: the WPT suite's own `hash_mismatch` case — the 32 hash bytes
        // after the 8-byte magic zeroed out.
        byte[] dcz = HexFormat.of().parseHex(DCZ);
        java.util.Arrays.fill(dcz, 8, 40, (byte) 0);
        AvailableDictionaryHeader hash = AvailableDictionaryHeader.of(ZstdDictionary.of(DICTIONARY));

        // When
        ThrowingCallable result = () -> Rfc9842Frame.unwrap(dcz, hash);

        // Then
        assertThatThrownBy(result)
                .isInstanceOf(Rfc9842Exception.class)
                .hasMessageContaining("hash does not match");
    }

    @Test
    void rejectsAVectorWhoseMagicWasTampered() {
        // Given: a valid vector with the skippable-frame magic variant changed
        // from 14 to 13, which is no longer a dcz header.
        byte[] dcz = HexFormat.of().parseHex(DCZ);
        dcz[0] = 0x5d;
        AvailableDictionaryHeader hash = AvailableDictionaryHeader.of(ZstdDictionary.of(DICTIONARY));

        // When
        ThrowingCallable result = () -> Rfc9842Frame.unwrap(dcz, hash);

        // Then
        assertThatThrownBy(result)
                .isInstanceOf(Rfc9842Exception.class)
                .hasMessageContaining("not a dcz frame");
    }
}
