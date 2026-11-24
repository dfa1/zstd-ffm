package io.github.dfa1.zstd;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.StructLayout;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins the named struct layouts to the C definitions in `zstd.h`: zstd writes
/// these structs by its own `sizeof`/`offsetof`, so a drifting Java layout would
/// read the wrong fields or overrun the allocation.
class BindingsLayoutTest {

    static Stream<Arguments> fieldOffsets() {
        StructLayout fh = Bindings.FRAME_HEADER_LAYOUT;
        StructLayout fp = Bindings.FRAME_PROGRESSION_LAYOUT;
        return Stream.of(
                Arguments.of(fh, "frameContentSize", 0L),
                Arguments.of(fh, "windowSize", 8L),
                Arguments.of(fh, "blockSizeMax", 16L),
                Arguments.of(fh, "frameType", 20L),
                Arguments.of(fh, "headerSize", 24L),
                Arguments.of(fh, "dictID", 28L),
                Arguments.of(fh, "checksumFlag", 32L),
                Arguments.of(fh, "_reserved1", 36L),
                Arguments.of(fh, "_reserved2", 40L),
                Arguments.of(fp, "ingested", 0L),
                Arguments.of(fp, "consumed", 8L),
                Arguments.of(fp, "produced", 16L),
                Arguments.of(fp, "flushed", 24L),
                Arguments.of(fp, "currentJobID", 32L),
                Arguments.of(fp, "nbActiveWorkers", 36L));
    }

    static Stream<Arguments> structSizes() {
        return Stream.of(
                Arguments.of(Bindings.FRAME_HEADER_LAYOUT, 48L),
                Arguments.of(Bindings.FRAME_PROGRESSION_LAYOUT, 40L));
    }

    @ParameterizedTest(name = "{1} at {2}")
    @MethodSource("fieldOffsets")
    void fieldSitsAtItsCOffset(StructLayout sut, String field, long expectedOffset) {
        // Given a named struct layout and one of its C fields

        // When resolving the field's offset
        long offset = sut.byteOffset(PathElement.groupElement(field));

        // Then it matches offsetof() in zstd.h
        assertThat(offset).isEqualTo(expectedOffset);
    }

    @ParameterizedTest(name = "{1} bytes")
    @MethodSource("structSizes")
    void sizeMatchesCSizeof(StructLayout sut, long expectedSize) {
        // Given a named struct layout

        // When reading its total size
        long size = sut.byteSize();

        // Then it matches sizeof() in zstd.h, so the allocation covers every byte zstd writes
        assertThat(size).isEqualTo(expectedSize);
    }
}
