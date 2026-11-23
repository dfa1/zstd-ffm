package io.github.dfa1.zstd.rfc9842;

import io.github.dfa1.zstd.ZstdDictionary;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/// Value semantics of the former rfc9842 records, now hand-written `final`
/// classes built through `of(...)` (#143).
class Rfc9842ValueClassContractTest {

    private static final ZstdDictionary DICT =
            ZstdDictionary.of("dictionary sample payload ".repeat(64).getBytes(StandardCharsets.UTF_8));

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

    @Test
    void negotiationToStringListsComponents() {
        // Given
        Rfc9842Negotiation sut = Rfc9842Negotiation.from(DICT.toByteArray(), "match=\"/a/*\"");

        // When
        String result = sut.toString();

        // Then
        assertThat(result)
                .startsWith("Rfc9842Negotiation[dictionary=")
                .contains(", useAsDictionary=UseAsDictionaryHeader[match=/a/*, id=, type=raw]")
                .endsWith(", dictionaryId=Optional.empty]");
    }

    @Test
    void useAsDictionaryShortFactoriesDefaultIdAndType() {
        // Given
        String match = "/app/*";

        // When
        UseAsDictionaryHeader sut = UseAsDictionaryHeader.of(match);

        // Then
        assertThat(sut).isEqualTo(UseAsDictionaryHeader.of(match, "", UseAsDictionaryHeader.TYPE_RAW));
        assertThat(UseAsDictionaryHeader.of(match, "v1"))
                .isEqualTo(UseAsDictionaryHeader.of(match, "v1", UseAsDictionaryHeader.TYPE_RAW));
    }

    @ParameterizedTest
    @ValueSource(classes = {DictionaryIdHeader.class, UseAsDictionaryHeader.class, Rfc9842Negotiation.class,
            AvailableDictionaryHeader.class})
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
        UseAsDictionaryHeader use = UseAsDictionaryHeader.of("/a/*", "id1");
        AvailableDictionaryHeader available = AvailableDictionaryHeader.of(DICT);
        return Stream.of(
                valueCase("DictionaryIdHeader", () -> DictionaryIdHeader.of("v1"), DictionaryIdHeader.of("v2"),
                        "DictionaryIdHeader[value=v1]"),
                valueCase("DictionaryIdHeader empty", () -> DictionaryIdHeader.of(""), DictionaryIdHeader.of("x"),
                        "DictionaryIdHeader[value=]"),
                valueCase("UseAsDictionaryHeader", () -> UseAsDictionaryHeader.of("/a/*", "id1", "raw"),
                        UseAsDictionaryHeader.of("/a/*", "id1", "other"),
                        "UseAsDictionaryHeader[match=/a/*, id=id1, type=raw]"),
                valueCase("Rfc9842Negotiation",
                        () -> Rfc9842Negotiation.of(DICT, use, available, DictionaryIdHeader.of("id1")),
                        Rfc9842Negotiation.of(DICT, use, available, null),
                        "Rfc9842Negotiation[dictionary=" + DICT + ", useAsDictionary=" + use
                                + ", availableDictionary=" + available
                                + ", dictionaryId=Optional[DictionaryIdHeader[value=id1]]]"));
    }

    /// One case per component of each multi-component type, differing in exactly
    /// that component, so dropping any clause of `equals` fails a case.
    static Stream<Arguments> componentCases() {
        UseAsDictionaryHeader use = UseAsDictionaryHeader.of("/a/*", "id1", "raw");
        AvailableDictionaryHeader available = AvailableDictionaryHeader.of(DICT);
        ZstdDictionary otherDict = ZstdDictionary.of("another dictionary ".repeat(64).getBytes(StandardCharsets.UTF_8));
        DictionaryIdHeader id = DictionaryIdHeader.of("id1");
        Rfc9842Negotiation negotiation = Rfc9842Negotiation.of(DICT, use, available, id);
        return Stream.of(
                componentCase("UseAsDictionaryHeader.match", use, UseAsDictionaryHeader.of("/b/*", "id1", "raw")),
                componentCase("UseAsDictionaryHeader.id", use, UseAsDictionaryHeader.of("/a/*", "id2", "raw")),
                componentCase("UseAsDictionaryHeader.type", use, UseAsDictionaryHeader.of("/a/*", "id1", "other")),
                componentCase("Rfc9842Negotiation.dictionary", negotiation,
                        Rfc9842Negotiation.of(otherDict, use, available, id)),
                componentCase("Rfc9842Negotiation.useAsDictionary", negotiation,
                        Rfc9842Negotiation.of(DICT, UseAsDictionaryHeader.of("/b/*", "id1"), available, id)),
                componentCase("Rfc9842Negotiation.availableDictionary", negotiation,
                        Rfc9842Negotiation.of(DICT, use, AvailableDictionaryHeader.of(otherDict), id)),
                componentCase("Rfc9842Negotiation.dictionaryId", negotiation,
                        Rfc9842Negotiation.of(DICT, use, available, null)));
    }

    private static Arguments componentCase(String name, Object base, Object different) {
        return arguments(Named.of(name, base), different);
    }

    private static Arguments valueCase(String name, Supplier<Object> factory, Object different, String toString) {
        return arguments(Named.of(name, factory), different, toString);
    }
}
