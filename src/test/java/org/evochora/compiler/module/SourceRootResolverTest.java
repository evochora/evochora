package org.evochora.compiler.module;

import org.evochora.compiler.api.SourceRoot;
import org.evochora.compiler.frontend.module.SourceRootResolver;
import org.evochora.compiler.frontend.module.SourceRootResolver.ParsedPath;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SourceRootResolver}.
 */
public class SourceRootResolverTest {

    @TempDir
    Path tempDir;

    // --- parsePath tests ---

    @Test
    @Tag("unit")
    void parsePath_noPrefix() {
        ParsedPath result = SourceRootResolver.parsePath("lib/energy.evo");
        assertThat(result.prefix()).isNull();
        assertThat(result.filePath()).isEqualTo("lib/energy.evo");
    }

    @Test
    @Tag("unit")
    void parsePath_validPrefix() {
        ParsedPath result = SourceRootResolver.parsePath("PRED:lib/move.evo");
        assertThat(result.prefix()).isEqualTo("PRED");
        assertThat(result.filePath()).isEqualTo("lib/move.evo");
    }

    @Test
    @Tag("unit")
    void parsePath_httpUrl_passesThrough() {
        ParsedPath result = SourceRootResolver.parsePath("https://example.com/lib.evo");
        assertThat(result.prefix()).isNull();
        assertThat(result.filePath()).isEqualTo("https://example.com/lib.evo");
    }

    @Test
    @Tag("unit")
    void parsePath_windowsDrive_notTreatedAsPrefix() {
        // Lowercase drive letter doesn't match [A-Z] prefix pattern
        ParsedPath result = SourceRootResolver.parsePath("c:\\path\\file.evo");
        assertThat(result.prefix()).isNull();
        assertThat(result.filePath()).isEqualTo("c:\\path\\file.evo");
    }

    @Test
    @Tag("unit")
    void parsePath_prefixWithUnderscore() {
        ParsedPath result = SourceRootResolver.parsePath("MY_LIB:file.evo");
        assertThat(result.prefix()).isEqualTo("MY_LIB");
        assertThat(result.filePath()).isEqualTo("file.evo");
    }

    // --- resolve tests ---

    @Test
    @Tag("unit")
    void resolve_defaultRoot() {
        SourceRootResolver resolver = new SourceRootResolver(
                List.of(new SourceRoot(".", null)), tempDir);
        String result = resolver.resolve("lib/energy.evo", "main.evo");
        assertThat(result).endsWith("lib/energy.evo");
        assertThat(result).startsWith(tempDir.toString().replace('\\', '/'));
    }

    @Test
    @Tag("unit")
    void resolve_namedRoot() {
        Path predDir = tempDir.resolve("predator");
        SourceRootResolver resolver = new SourceRootResolver(
                List.of(new SourceRoot(".", null), new SourceRoot(predDir.toString(), "PRED")),
                tempDir);
        String result = resolver.resolve("PRED:main.evo", "");
        assertThat(result).endsWith("main.evo");
        assertThat(result).contains("predator");
    }

    @Test
    @Tag("unit")
    void resolve_unknownPrefix_throwsError() {
        SourceRootResolver resolver = new SourceRootResolver(
                List.of(new SourceRoot(".", null)), tempDir);
        assertThatThrownBy(() -> resolver.resolve("UNKNOWN:main.evo", ""))
                .isInstanceOf(SourceRootResolver.UnknownPrefixException.class)
                .hasMessageContaining("UNKNOWN");
    }

    @Test
    @Tag("unit")
    void resolve_noDefaultRoot_throwsError() {
        SourceRootResolver resolver = new SourceRootResolver(
                List.of(new SourceRoot("./pred", "PRED")), tempDir);
        assertThatThrownBy(() -> resolver.resolve("lib/energy.evo", "main.evo"))
                .isInstanceOf(SourceRootResolver.UnknownPrefixException.class)
                .hasMessageContaining("unprefixed");
    }

    @Test
    @Tag("unit")
    void resolve_httpUrl_passesThrough() {
        SourceRootResolver resolver = new SourceRootResolver(
                List.of(new SourceRoot(".", null)), tempDir);
        String result = resolver.resolve("https://example.com/lib.evo", "main.evo");
        assertThat(result).isEqualTo("https://example.com/lib.evo");
    }

    @Test
    @Tag("unit")
    void resolve_httpSourceRoot_resolvesRelativePath() {
        SourceRootResolver resolver = new SourceRootResolver(
                List.of(new SourceRoot("https://example.com/organisms/predator", "PRED")),
                tempDir);
        String result = resolver.resolve("PRED:lib/move.evo", "");
        assertThat(result).isEqualTo("https://example.com/organisms/predator/lib/move.evo");
    }

    @Test
    @Tag("unit")
    void resolve_httpSourceRoot_defaultPrefix() {
        SourceRootResolver resolver = new SourceRootResolver(
                List.of(new SourceRoot("https://example.com/organisms", null)),
                tempDir);
        String result = resolver.resolve("main.evo", "");
        assertThat(result).isEqualTo("https://example.com/organisms/main.evo");
    }

    @Test
    @Tag("unit")
    void resolve_httpSourceRoot_trailingSlash() {
        SourceRootResolver resolver = new SourceRootResolver(
                List.of(new SourceRoot("https://example.com/organisms/", "PRED")),
                tempDir);
        String result = resolver.resolve("PRED:main.evo", "");
        assertThat(result).isEqualTo("https://example.com/organisms/main.evo");
    }
}
