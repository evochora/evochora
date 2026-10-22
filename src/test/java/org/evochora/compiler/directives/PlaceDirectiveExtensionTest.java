package org.evochora.compiler.directives;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class PlaceDirectiveExtensionTest {

    @BeforeAll
    static void init() {
        Instruction.init();
    }

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
            .hasMessageContaining("'*' in .PLACE needs a world shape.");
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

    @Test
    @Tag("unit")
    void testPlaceWithAnUndefinedNameBeforeTheColon() {
        String source = ".PLACE FOOD:1 5|5";
        Compiler compiler = new Compiler();
        assertThatThrownBy(() -> compiler.compile(List.of(source), "test.s", testEnvProps))
            .isInstanceOf(CompilationException.class)
            .hasMessageContaining("'FOOD' is not defined.");
    }

    @Test
    @Tag("unit")
    void testPlaceWithANumberBeforeTheColon() {
        List<String> source = List.of(".CONST FOOD 5", ".PLACE FOOD:1 5|5");
        Compiler compiler = new Compiler();
        assertThatThrownBy(() -> compiler.compile(source, "test.s", testEnvProps))
            .isInstanceOf(CompilationException.class)
            .hasMessageContaining("Invalid operands for ':': number and number.");
    }

    /** A toroidal world of 128 cells along each axis. */
    private static final EnvironmentProperties TORUS = new EnvironmentProperties(new int[]{128, 128}, true);

    /** A bounded world of 128 cells along each axis. */
    private static final EnvironmentProperties BOUNDED = new EnvironmentProperties(new int[]{128, 128}, false);


    @Test
    @Tag("unit")
    void cellsThatPassOneAnotherAcrossTheWrapAreAccepted() throws CompilationException {
        ProgramArtifact artifact = new Compiler().compile(List.of(".PLACE DATA:1 0:3:200|0"), "test.s", TORUS);

        assertThat(artifact.initialWorldObjects()).hasSize(67);
    }


    /**
     * A range far longer than the world stops at the first cell that cannot be placed, on either
     * kind of world, and a range up to the largest coordinate does not count past it.
     */
    @Test
    @Tag("unit")
    @Timeout(10)
    void aRangeLongerThanTheWorldStopsAtItsFirstImpossibleCell() {
        Compiler compiler = new Compiler();

        assertThatThrownBy(() -> compiler.compile(List.of(".PLACE DATA:1 0..2147483647|0"), "test.s", TORUS))
            .isInstanceOf(CompilationException.class)
            .hasMessageContaining("Coordinate [128, 0] lands on the cell of [0, 0]");
        assertThatThrownBy(() -> compiler.compile(List.of(".PLACE DATA:1 0..2000000000|0"), "test.s", BOUNDED))
            .isInstanceOf(CompilationException.class)
            .hasMessageContaining("World size exceeded: Coordinate [128, 0] lies 128 cells from [0, 0]");
    }
}
