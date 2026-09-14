package io.github.dfa1.zstd.rfc9842;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
            assertThat(sut.id()).isEmpty();
            assertThat(sut.type()).isEqualTo(UseAsDictionary.TYPE_RAW);
            assertThat(sut.toHeaderValue()).isEqualTo(header);
        }

        @Test
        void parsesAcceptingButNotModelingMatchDest() {
            // Given a header a browser-facing server might also send — match-dest
            // is not modeled (see class doc), just tolerated so parsing doesn't fail
            String header = "match=\"/product/*\", match-dest=(\"document\")";

            // When
            UseAsDictionary sut = UseAsDictionary.parse(header);

            // Then
            assertThat(sut.match()).isEqualTo("/product/*");
            assertThat(sut.toHeaderValue()).isEqualTo("match=\"/product/*\"");
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
        void parsesMatchIdAndType() {
            // Given
            String header = "match=\"/x/*\", id=\"abc\", type=custom";

            // When
            UseAsDictionary sut = UseAsDictionary.parse(header);

            // Then
            assertThat(sut.match()).isEqualTo("/x/*");
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

            assertThat(sut.id()).isEmpty();
            assertThat(sut.type()).isEqualTo(UseAsDictionary.TYPE_RAW);
        }

        @Test
        void matchAndIdConvenienceConstructorUsesDefaults() {
            UseAsDictionary sut = new UseAsDictionary("/x/*", "abc");

            assertThat(sut.id()).isEqualTo("abc");
            assertThat(sut.type()).isEqualTo(UseAsDictionary.TYPE_RAW);
        }

        @Test
        void rejectsAnEmptyMatch() {
            ThrowingCallable result = () -> new UseAsDictionary("");
            assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsAnIdOverTheMaximumLength() {
            ThrowingCallable result = () -> new UseAsDictionary("/x", "a".repeat(1025), "raw");
            assertThatThrownBy(result).isInstanceOf(IllegalArgumentException.class);
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
}
