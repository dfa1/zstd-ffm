package io.github.dfa1.zstd.rfc9842;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UseAsDictionaryTest {

    @Nested
    class Parsing {

        @Test
        void parsesAMatchOnlyHeader() {
            // Given the RFC 9842 example header value
            String header = "match=\"/app/*/main.js\"";

            // When
            UseAsDictionary sut = UseAsDictionary.parse(header);

            // Then
            assertThat(sut.match()).isEqualTo("/app/*/main.js");
            assertThat(sut.matchDest()).isEmpty();
            assertThat(sut.id()).isEmpty();
            assertThat(sut.type()).isEqualTo(UseAsDictionary.TYPE_RAW);
            assertThat(sut.toHeaderValue()).isEqualTo(header);
        }

        @Test
        void parsesMatchWithMatchDest() {
            // Given the RFC 9842 example header value
            String header = "match=\"/product/*\", match-dest=(\"document\")";

            // When
            UseAsDictionary sut = UseAsDictionary.parse(header);

            // Then
            assertThat(sut.match()).isEqualTo("/product/*");
            assertThat(sut.matchDest()).containsExactly("document");
            assertThat(sut.toHeaderValue()).isEqualTo(header);
        }

        @Test
        void parsesMatchWithId() {
            // Given the RFC 9842 example header value
            String header = "match=\"/app/*/main.js\", id=\"dictionary-12345\"";

            // When
            UseAsDictionary sut = UseAsDictionary.parse(header);

            // Then
            assertThat(sut.id()).isEqualTo("dictionary-12345");
            assertThat(sut.toHeaderValue()).isEqualTo(header);
        }

        @Test
        void parsesAllFourMembers() {
            // Given
            String header = "match=\"/x/*\", match-dest=(\"document\" \"worker\"), id=\"abc\", type=custom";

            // When
            UseAsDictionary sut = UseAsDictionary.parse(header);

            // Then
            assertThat(sut.match()).isEqualTo("/x/*");
            assertThat(sut.matchDest()).containsExactly("document", "worker");
            assertThat(sut.id()).isEqualTo("abc");
            assertThat(sut.type()).isEqualTo("custom");
            assertThat(sut.toHeaderValue()).isEqualTo(header);
        }

        @Test
        void rejectsAHeaderMissingMatch() {
            ThrowingCallable result = () -> UseAsDictionary.parse("id=\"abc\"");
            assertThatThrownBy(result).isInstanceOf(Rfc9842Exception.class).hasMessageContaining("match");
        }

        @Test
        void rejectsAnUnrecognizedMember() {
            ThrowingCallable result = () -> UseAsDictionary.parse("match=\"/x\", bogus=\"y\"");
            assertThatThrownBy(result).isInstanceOf(Rfc9842Exception.class).hasMessageContaining("bogus");
        }

        @Test
        void rejectsATrailingComma() {
            ThrowingCallable result = () -> UseAsDictionary.parse("match=\"/x\",");
            assertThatThrownBy(result).isInstanceOf(Rfc9842Exception.class);
        }

        @Test
        void laterDuplicateMemberWins() {
            // Given a header repeating "match" — per RFC 8941, the later occurrence wins
            String header = "match=\"/first\", match=\"/second\"";

            UseAsDictionary sut = UseAsDictionary.parse(header);

            assertThat(sut.match()).isEqualTo("/second");
        }

        @Test
        void rejectsNullHeaderValue() {
            assertThatThrownBy(() -> UseAsDictionary.parse(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class Construction {

        @Test
        void matchOnlyConvenienceConstructorUsesDefaults() {
            UseAsDictionary sut = new UseAsDictionary("/x/*");

            assertThat(sut.matchDest()).isEmpty();
            assertThat(sut.id()).isEmpty();
            assertThat(sut.type()).isEqualTo(UseAsDictionary.TYPE_RAW);
        }

        @Test
        void matchAndIdConvenienceConstructorUsesDefaults() {
            UseAsDictionary sut = new UseAsDictionary("/x/*", "abc");

            assertThat(sut.id()).isEqualTo("abc");
            assertThat(sut.matchDest()).isEmpty();
            assertThat(sut.type()).isEqualTo(UseAsDictionary.TYPE_RAW);
        }

        @Test
        void rejectsAnEmptyMatch() {
            ThrowingCallable result = () -> new UseAsDictionary("");
            assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsAnIdOverTheMaximumLength() {
            ThrowingCallable result = () -> new UseAsDictionary("/x", List.of(), "a".repeat(1025), "raw");
            assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void matchDestIsDefensivelyCopiedAndImmutable() {
            List<String> mutable = new ArrayList<>(List.of("document"));
            UseAsDictionary sut = new UseAsDictionary("/x", mutable, "", "raw");
            mutable.add("worker"); // mutate the caller's list after construction

            assertThat(sut.matchDest()).containsExactly("document"); // unaffected

            ThrowingCallable result = () -> sut.matchDest().add("script");
            assertThatThrownBy(result).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    class PathMatching {

        @Test
        void matchesAWildcardInTheMiddleOfThePattern() {
            UseAsDictionary sut = new UseAsDictionary("/app/*/main.js");

            assertThat(sut.matchesPath("/app/v2/main.js")).isTrue();
            assertThat(sut.matchesPath("/app/main.js")).isFalse();
            assertThat(sut.matchesPath("/other/v2/main.js")).isFalse();
        }

        @Test
        void matchesATrailingWildcard() {
            UseAsDictionary sut = new UseAsDictionary("/product/*");

            assertThat(sut.matchesPath("/product/123")).isTrue();
            assertThat(sut.matchesPath("/product/")).isTrue();
            assertThat(sut.matchesPath("/product")).isFalse();
            assertThat(sut.matchesPath("/other")).isFalse();
        }

        @Test
        void aLiteralPatternWithNoWildcardMatchesOnlyItself() {
            UseAsDictionary sut = new UseAsDictionary("/exact/path");

            assertThat(sut.matchesPath("/exact/path")).isTrue();
            assertThat(sut.matchesPath("/exact/path/extra")).isFalse();
        }

        @Test
        void wildcardMatchesAcrossSlashes() {
            // Documented gap: this implementation's '*' matches any characters,
            // including further path separators — not segment-scoped like full
            // WHATWG URL Pattern semantics.
            UseAsDictionary sut = new UseAsDictionary("/a/*/z");

            assertThat(sut.matchesPath("/a/b/c/z")).isTrue();
        }
    }

    @Nested
    class DestinationMatching {

        @Test
        void emptyMatchDestAppliesToEveryDestination() {
            UseAsDictionary sut = new UseAsDictionary("/x");

            assertThat(sut.appliesToDestination("document")).isTrue();
            assertThat(sut.appliesToDestination("script")).isTrue();
        }

        @Test
        void nonEmptyMatchDestOnlyAppliesToListedDestinations() {
            UseAsDictionary sut = new UseAsDictionary("/x", List.of("document", "worker"), "", UseAsDictionary.TYPE_RAW);

            assertThat(sut.appliesToDestination("document")).isTrue();
            assertThat(sut.appliesToDestination("script")).isFalse();
        }

        @Test
        void matchesCombinesPathAndDestination() {
            UseAsDictionary sut = new UseAsDictionary("/x/*", List.of("document"), "", UseAsDictionary.TYPE_RAW);

            assertThat(sut.matches("/x/1", "document")).isTrue();
            assertThat(sut.matches("/x/1", "script")).isFalse();
            assertThat(sut.matches("/other", "document")).isFalse();
        }
    }
}
