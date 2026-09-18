package io.github.dfa1.zstd.rfc9842;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import static org.assertj.core.api.Assertions.assertThatCode;

class ArchitectureTest {

    /// This package (layer 1, not [io.github.dfa1.zstd.rfc9842.demo]) is a
    /// [sans-io](https://sans-io.readthedocs.io/) `dcz` codec: framing,
    /// header parsing, and hash verification over `byte[]`/`MemorySegment`,
    /// with no socket of its own — see [Rfc9842Frame]. Nothing else stops a
    /// future change from reaching for `java.net.http.HttpClient` here
    /// instead of in a caller, so it is enforced rather than just
    /// documented.
    @Test
    void mainSourcesPerformNoIo() {
        // Given
        JavaClasses productionClasses = new ClassFileImporter().importPath(Path.of("target/classes"));
        ArchRule sansIo = noClasses()
                .should().dependOnClassesThat()
                .resideInAnyPackage("java.net..", "java.nio.channels..", "javax.net..");

        // When
        ThrowingCallable result = () -> sansIo.check(productionClasses);

        // Then
        assertThatCode(result).doesNotThrowAnyException();
    }
}
