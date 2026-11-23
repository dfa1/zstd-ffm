package io.github.dfa1.zstd;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/// Value semantics of the former records, now hand-written `final` classes:
/// `equals`/`hashCode` over every component and the record-style `toString`.
/// Plus the invariant behind #143 — no public type exposes a public constructor.
class ZstdValueClassContractTest {

    @ParameterizedTest
    @MethodSource("valueCases")
    void equalComponentsAreEqual(Supplier<Object> factory, Object different, String expectedToString) {
        // Given
        Object sut = factory.get();
        Object same = factory.get();

        // When
        boolean result = sut.equals(same);

        // Then
        assertThat(result).isTrue();
        assertThat(same).isEqualTo(sut);
        assertThat(sut).hasSameHashCodeAs(same);
    }

    @ParameterizedTest
    @MethodSource("valueCases")
    void differentComponentsAreNotEqual(Supplier<Object> factory, Object different, String expectedToString) {
        // Given
        Object sut = factory.get();

        // When
        boolean result = sut.equals(different);

        // Then
        assertThat(result).isFalse();
        assertThat(sut)
                .isNotEqualTo(null)
                .isNotEqualTo(new Object())
                .isEqualTo(sut);
    }

    @ParameterizedTest
    @MethodSource("valueCases")
    void toStringListsComponents(Supplier<Object> factory, Object different, String expectedToString) {
        // Given
        Object sut = factory.get();

        // When
        String result = sut.toString();

        // Then
        assertThat(result).isEqualTo(expectedToString);
    }

    @ParameterizedTest
    @MethodSource("componentCases")
    void everyComponentTakesPartInEquality(Object base, Object differsInOneComponent) {
        // Given
        Object sut = base;

        // When
        boolean result = sut.equals(differsInOneComponent);

        // Then
        assertThat(result).isFalse();
        assertThat(differsInOneComponent).isNotEqualTo(sut);
    }

    @ParameterizedTest
    @ValueSource(classes = {
            ZstdCompressContext.class, ZstdDecompressContext.class,
            ZstdCompressDictionary.class, ZstdDecompressDictionary.class,
            ZstdCompressStream.class, ZstdDecompressStream.class,
            ZstdOutputStream.class, ZstdInputStream.class,
            ZstdCompressionLevel.class, ZstdByteSize.class, ZstdWindowLog.class, ZstdMagicVariant.class,
            ZstdVersion.class, ZstdDictionaryId.class, ZstdBounds.class, ZstdFrameHeader.class,
            ZstdFrameProgression.class, ZstdStreamResult.class})
    void hasNoPublicConstructorAndIsNotARecord(Class<?> type) {
        // Given
        Class<?> sut = type;

        // When
        boolean result = Arrays.stream(sut.getDeclaredConstructors())
                .anyMatch(c -> Modifier.isPublic(c.getModifiers()));

        // Then
        assertThat(result).isFalse();
        assertThat(sut.isRecord()).isFalse();
        assertThat(Modifier.isFinal(sut.getModifiers())).isTrue();
    }

    static Stream<Arguments> valueCases() {
        ZstdByteSize size = ZstdByteSize.of(1);
        return Stream.of(
                valueCase("ZstdCompressionLevel", () -> ZstdCompressionLevel.of(3), ZstdCompressionLevel.of(4),
                        "ZstdCompressionLevel[value=3]"),
                valueCase("ZstdByteSize zero", () -> ZstdByteSize.of(0), ZstdByteSize.of(1),
                        "ZstdByteSize[value=0]"),
                valueCase("ZstdByteSize max", () -> ZstdByteSize.of(Long.MAX_VALUE), ZstdByteSize.of(0),
                        "ZstdByteSize[value=" + Long.MAX_VALUE + "]"),
                valueCase("ZstdWindowLog", () -> ZstdWindowLog.of(20), ZstdWindowLog.AUTO,
                        "ZstdWindowLog[value=20]"),
                valueCase("ZstdMagicVariant", () -> ZstdMagicVariant.of(15), ZstdMagicVariant.of(0),
                        "ZstdMagicVariant[value=15]"),
                valueCase("ZstdVersion", () -> ZstdVersion.of(1, 5, 7), ZstdVersion.of(1, 5, 6), "1.5.7"),
                valueCase("ZstdDictionaryId unsigned", () -> ZstdDictionaryId.of(-1), ZstdDictionaryId.NONE,
                        "ZstdDictionaryId[raw=-1]"),
                valueCase("ZstdBounds", () -> ZstdBounds.of(-5, 22), ZstdBounds.of(-5, 23),
                        "ZstdBounds[lowerBound=-5, upperBound=22]"),
                valueCase("ZstdStreamResult", () -> ZstdStreamResult.of(1, 2, 0), ZstdStreamResult.of(1, 2, 3),
                        "ZstdStreamResult[bytesConsumed=1, bytesProduced=2, remaining=0]"),
                valueCase("ZstdFrameProgression", () -> ZstdFrameProgression.of(1, 2, 3, 4, 5, 6),
                        ZstdFrameProgression.of(1, 2, 3, 4, 5, 7),
                        "ZstdFrameProgression[ingested=1, consumed=2, produced=3, flushed=4, currentJobId=5,"
                                + " activeWorkers=6]"),
                valueCase("ZstdFrameHeader",
                        () -> ZstdFrameHeader.of(Optional.of(size), Optional.empty(), size, ZstdFrameType.STANDARD,
                                size, ZstdDictionaryId.NONE, true),
                        ZstdFrameHeader.of(Optional.of(size), Optional.empty(), size, ZstdFrameType.STANDARD,
                                size, ZstdDictionaryId.NONE, false),
                        "ZstdFrameHeader[contentSize=Optional[ZstdByteSize[value=1]], windowSize=Optional.empty,"
                                + " blockSizeMax=ZstdByteSize[value=1], frameType=STANDARD,"
                                + " headerSize=ZstdByteSize[value=1], dictId=ZstdDictionaryId[raw=0],"
                                + " hasChecksum=true]"));
    }

    /// One case per component of each multi-component type: `base` and a copy
    /// differing in exactly that component, so dropping any clause of `equals`
    /// fails a case.
    static Stream<Arguments> componentCases() {
        ZstdByteSize one = ZstdByteSize.of(1);
        ZstdByteSize two = ZstdByteSize.of(2);
        Optional<ZstdByteSize> none = Optional.empty();
        ZstdFrameHeader header = ZstdFrameHeader.of(Optional.of(one), Optional.of(one), one, ZstdFrameType.STANDARD,
                one, ZstdDictionaryId.NONE, true);
        ZstdFrameProgression progression = ZstdFrameProgression.of(1, 2, 3, 4, 5, 6);
        ZstdBounds bounds = ZstdBounds.of(1, 2);
        ZstdStreamResult streamResult = ZstdStreamResult.of(1, 2, 3);
        ZstdVersion version = ZstdVersion.of(1, 2, 3);
        return Stream.of(
                componentCase("ZstdFrameHeader.contentSize", header, ZstdFrameHeader.of(none, Optional.of(one), one,
                        ZstdFrameType.STANDARD, one, ZstdDictionaryId.NONE, true)),
                componentCase("ZstdFrameHeader.windowSize", header, ZstdFrameHeader.of(Optional.of(one), none, one,
                        ZstdFrameType.STANDARD, one, ZstdDictionaryId.NONE, true)),
                componentCase("ZstdFrameHeader.blockSizeMax", header, ZstdFrameHeader.of(Optional.of(one),
                        Optional.of(one), two, ZstdFrameType.STANDARD, one, ZstdDictionaryId.NONE, true)),
                componentCase("ZstdFrameHeader.frameType", header, ZstdFrameHeader.of(Optional.of(one),
                        Optional.of(one), one, ZstdFrameType.SKIPPABLE, one, ZstdDictionaryId.NONE, true)),
                componentCase("ZstdFrameHeader.headerSize", header, ZstdFrameHeader.of(Optional.of(one),
                        Optional.of(one), one, ZstdFrameType.STANDARD, two, ZstdDictionaryId.NONE, true)),
                componentCase("ZstdFrameHeader.dictId", header, ZstdFrameHeader.of(Optional.of(one),
                        Optional.of(one), one, ZstdFrameType.STANDARD, one, ZstdDictionaryId.of(7), true)),
                componentCase("ZstdFrameHeader.hasChecksum", header, ZstdFrameHeader.of(Optional.of(one),
                        Optional.of(one), one, ZstdFrameType.STANDARD, one, ZstdDictionaryId.NONE, false)),
                componentCase("ZstdFrameProgression.ingested", progression, ZstdFrameProgression.of(9, 2, 3, 4, 5, 6)),
                componentCase("ZstdFrameProgression.consumed", progression, ZstdFrameProgression.of(1, 9, 3, 4, 5, 6)),
                componentCase("ZstdFrameProgression.produced", progression, ZstdFrameProgression.of(1, 2, 9, 4, 5, 6)),
                componentCase("ZstdFrameProgression.flushed", progression, ZstdFrameProgression.of(1, 2, 3, 9, 5, 6)),
                componentCase("ZstdFrameProgression.currentJobId", progression,
                        ZstdFrameProgression.of(1, 2, 3, 4, 9, 6)),
                componentCase("ZstdFrameProgression.activeWorkers", progression,
                        ZstdFrameProgression.of(1, 2, 3, 4, 5, 9)),
                componentCase("ZstdBounds.lowerBound", bounds, ZstdBounds.of(0, 2)),
                componentCase("ZstdBounds.upperBound", bounds, ZstdBounds.of(1, 3)),
                componentCase("ZstdStreamResult.bytesConsumed", streamResult, ZstdStreamResult.of(9, 2, 3)),
                componentCase("ZstdStreamResult.bytesProduced", streamResult, ZstdStreamResult.of(1, 9, 3)),
                componentCase("ZstdStreamResult.remaining", streamResult, ZstdStreamResult.of(1, 2, 9)),
                componentCase("ZstdVersion.major", version, ZstdVersion.of(9, 2, 3)),
                componentCase("ZstdVersion.minor", version, ZstdVersion.of(1, 9, 3)),
                componentCase("ZstdVersion.patch", version, ZstdVersion.of(1, 2, 9)));
    }

    private static Arguments componentCase(String name, Object base, Object different) {
        return arguments(Named.of(name, base), different);
    }

    private static Arguments valueCase(String name, Supplier<Object> factory, Object different, String toString) {
        return arguments(Named.of(name, factory), different, toString);
    }
}
