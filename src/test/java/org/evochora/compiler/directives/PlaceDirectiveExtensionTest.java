package org.evochora.compiler.directives;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class PlaceDirectiveExtensionTest {

    private final EnvironmentProperties testEnvProps = new EnvironmentProperties(new int[]{100, 100}, true);

    @Test
    @Tag("unit")
    void testPlaceWithSimpleRange() throws CompilationException {
        String source = ".PLACE STRUCTURE:1 1..3|5";
        Compiler compiler = new Compiler();
        ProgramArtifact artifact = compiler.compile(List.of(source), "test.s", testEnvProps);

        assertThat(artifact.initialWorldObjects()).hasSize(3);
        assertThat(artifact.initialWorldObjects().keySet()).containsExactlyInAnyOrder(
            new int[]{1, 5},
            new int[]{2, 5},
            new int[]{3, 5}
        );
    }

    @Test
    @Tag("unit")
    void testPlaceWith2DRange() throws CompilationException {
        String source = ".PLACE STRUCTURE:1 1..2|10..11";
        Compiler compiler = new Compiler();
        ProgramArtifact artifact = compiler.compile(List.of(source), "test.s", testEnvProps);

        assertThat(artifact.initialWorldObjects()).hasSize(4);
        assertThat(artifact.initialWorldObjects().keySet()).containsExactlyInAnyOrder(
            new int[]{1, 10},
            new int[]{1, 11},
            new int[]{2, 10},
            new int[]{2, 11}
        );
    }

    @Test
    @Tag("unit")
    void testPlaceWithSteppedRange() throws CompilationException {
        String source = ".PLACE STRUCTURE:1 10:2:14|5";
        Compiler compiler = new Compiler();
        ProgramArtifact artifact = compiler.compile(List.of(source), "test.s", testEnvProps);

        assertThat(artifact.initialWorldObjects()).hasSize(3);
        assertThat(artifact.initialWorldObjects().keySet()).containsExactlyInAnyOrder(
            new int[]{10, 5},
            new int[]{12, 5},
            new int[]{14, 5}
        );
    }

    @Test
    @Tag("unit")
    void testPlaceWithWildcard() throws CompilationException {
        String source = ".PLACE STRUCTURE:1 *|2";
        Compiler compiler = new Compiler();
        EnvironmentProperties envProps = new EnvironmentProperties(new int[]{4, 3}, true);
        ProgramArtifact artifact = compiler.compile(List.of(source), "test.s", envProps);

        assertThat(artifact.initialWorldObjects()).hasSize(4);
        assertThat(artifact.initialWorldObjects().keySet()).containsExactlyInAnyOrder(
            new int[]{0, 2},
            new int[]{1, 2},
            new int[]{2, 2},
            new int[]{3, 2}
        );
    }

    @Test
    @Tag("unit")
    void testPlaceWithWildcardAndRange() throws CompilationException {
        String source = ".PLACE STRUCTURE:1 *|1..2";
        Compiler compiler = new Compiler();
        EnvironmentProperties envProps = new EnvironmentProperties(new int[]{3, 4}, true);
        ProgramArtifact artifact = compiler.compile(List.of(source), "test.s", envProps);

        assertThat(artifact.initialWorldObjects()).hasSize(6);
        assertThat(artifact.initialWorldObjects().keySet()).containsExactlyInAnyOrder(
            new int[]{0, 1}, new int[]{0, 2},
            new int[]{1, 1}, new int[]{1, 2},
            new int[]{2, 1}, new int[]{2, 2}
        );
    }

    @Test
    @Tag("unit")
    void testPlaceWithWildcardNoContext() {
        String source = ".PLACE STRUCTURE:1 *|2";
        Compiler compiler = new Compiler();
        assertThatThrownBy(() -> compiler.compile(List.of(source), "test.s", null))
            .isInstanceOf(CompilationException.class)
            .hasMessageContaining("Use of '*' in .PLACE requires a compilation context");
    }

    @Test
    @Tag("unit")
    void testPlaceWithMultiplePlacements() throws CompilationException {
        String source = ".PLACE STRUCTURE:1 1|1, 2..3|4";
        Compiler compiler = new Compiler();
        ProgramArtifact artifact = compiler.compile(List.of(source), "test.s", testEnvProps);

        assertThat(artifact.initialWorldObjects()).hasSize(3);
        assertThat(artifact.initialWorldObjects().keySet()).containsExactlyInAnyOrder(
            new int[]{1, 1},
            new int[]{2, 4},
            new int[]{3, 4}
        );
    }

    @Test
    @Tag("unit")
    void testPlaceWithLegacyVectorSyntax() throws CompilationException {
        String source = ".PLACE STRUCTURE:1 10|20";
        Compiler compiler = new Compiler();
        ProgramArtifact artifact = compiler.compile(List.of(source), "test.s", testEnvProps);
        assertThat(artifact.initialWorldObjects()).hasSize(1);
        assertThat(artifact.initialWorldObjects().keySet()).containsExactly(new int[]{10, 20});
    }
}
