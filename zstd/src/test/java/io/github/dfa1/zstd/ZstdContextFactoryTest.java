package io.github.dfa1.zstd;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZstdContextFactoryTest {

    private static final byte[] PAYLOAD = "context factory ".repeat(200).getBytes(StandardCharsets.UTF_8);

    @Nested
    class NewCompressContext {

        @Test
        void returnsOpenContextAtDefaultLevel() {
            // Given
            byte[] expected = Zstd.compress(PAYLOAD, ZstdCompressionLevel.DEFAULT);

            // When
            byte[] result;
            try (ZstdCompressContext sut = Zstd.newCompressContext()) {
                result = sut.compress(PAYLOAD);
            }

            // Then
            assertThat(result).isEqualTo(expected);
            assertThat(Zstd.decompress(result)).isEqualTo(PAYLOAD);
        }

        @Test
        void compressesEmptyInput() {
            // Given
            byte[] empty = new byte[0];

            // When
            byte[] result;
            try (ZstdCompressContext sut = Zstd.newCompressContext()) {
                result = sut.compress(empty);
            }

            // Then
            assertThat(Zstd.decompress(result)).isEmpty();
        }

        @Test
        void returnsDistinctContexts() {
            // Given
            ZstdCompressContext first = Zstd.newCompressContext();

            // When
            try (ZstdCompressContext sut = Zstd.newCompressContext()) {
                closeQuietly(first);

                // Then
                assertThat(sut).isNotSameAs(first);
                assertThat(Zstd.decompress(sut.compress(PAYLOAD))).isEqualTo(PAYLOAD);
            }
        }

        @Test
        void closeIsIdempotent() {
            // Given
            ZstdCompressContext sut = Zstd.newCompressContext();
            sut.close();

            // When
            ThrowingCallable result = sut::close;

            // Then
            assertThatCode(result).doesNotThrowAnyException();
        }

        @Test
        void useAfterCloseFails() {
            // Given
            ZstdCompressContext sut = Zstd.newCompressContext();
            sut.close();

            // When
            ThrowingCallable result = () -> sut.compress(PAYLOAD);

            // Then
            assertThatThrownBy(result)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
        }

        @Test
        void hasNoPublicConstructor() {
            // Given
            Class<ZstdCompressContext> type = ZstdCompressContext.class;

            // When
            boolean result = Arrays.stream(type.getDeclaredConstructors())
                    .anyMatch(c -> Modifier.isPublic(c.getModifiers()));

            // Then
            assertThat(result).isFalse();
        }
    }

    @Nested
    class NewDecompressContext {

        @Test
        void returnsOpenContext() {
            // Given
            byte[] compressed = Zstd.compress(PAYLOAD);

            // When
            byte[] result;
            try (ZstdDecompressContext sut = Zstd.newDecompressContext()) {
                result = sut.decompress(compressed);
            }

            // Then
            assertThat(result).isEqualTo(PAYLOAD);
        }

        @Test
        void decompressesEmptyFrame() {
            // Given
            byte[] compressed = Zstd.compress(new byte[0]);

            // When
            byte[] result;
            try (ZstdDecompressContext sut = Zstd.newDecompressContext()) {
                result = sut.decompress(compressed);
            }

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        void returnsDistinctContexts() {
            // Given
            byte[] compressed = Zstd.compress(PAYLOAD);
            ZstdDecompressContext first = Zstd.newDecompressContext();

            // When
            try (ZstdDecompressContext sut = Zstd.newDecompressContext()) {
                closeQuietly(first);

                // Then
                assertThat(sut).isNotSameAs(first);
                assertThat(sut.decompress(compressed)).isEqualTo(PAYLOAD);
            }
        }

        @Test
        void closeIsIdempotent() {
            // Given
            ZstdDecompressContext sut = Zstd.newDecompressContext();
            sut.close();

            // When
            ThrowingCallable result = sut::close;

            // Then
            assertThatCode(result).doesNotThrowAnyException();
        }

        @Test
        void useAfterCloseFails() {
            // Given
            ZstdDecompressContext sut = Zstd.newDecompressContext();
            byte[] compressed = Zstd.compress(PAYLOAD);
            sut.close();

            // When
            ThrowingCallable result = () -> sut.decompress(compressed);

            // Then
            assertThatThrownBy(result)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
        }

        @Test
        void hasNoPublicConstructor() {
            // Given
            Class<ZstdDecompressContext> type = ZstdDecompressContext.class;

            // When
            boolean result = Arrays.stream(type.getDeclaredConstructors())
                    .anyMatch(c -> Modifier.isPublic(c.getModifiers()));

            // Then
            assertThat(result).isFalse();
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try (closeable) {
            // closed by try-with-resources: the other context must stay usable
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
