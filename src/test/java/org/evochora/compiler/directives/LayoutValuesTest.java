package org.evochora.compiler.directives;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The named layout values of a single file: a label above a layout directive is the position of
 * its cell, and a constant only the layout computes has the value it got where it stands.
 * Programs are compiled through the whole pipeline and judged by the cells of the artifact.
 */
@Tag("integration")
class LayoutValuesTest {

    private static final EnvironmentProperties ENV = new EnvironmentProperties(new int[]{100, 100}, true);

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void aLabelAboveADirectiveIsThePositionOfItsCell() throws Exception {
        ProgramArtifact artifact = compile(
                ".ORG 2|2",
                "NW:",
                ".ORG (NW + (1|0))",
                "NOP",
                ".PLACE DATA:1 (NW + (0|1))");

        assertThat(codeAt(artifact, 3, 2)).as("the NOP one column right of the label").isTrue();
        assertThat(artifact.initialWorldObjects().keySet()).containsExactly(new int[]{2, 3});
    }

    @Test
    void aLabelBelowTheLineIsAForwardReference() {
        assertThatThrownBy(() -> compile(
                "# A comment line.",
                ".PLACE DATA:1 (NW + (0|1))",
                ".ORG 2|2",
                "NW:",
                "NOP"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("layout.evo:2: Forward reference to 'NW'.");
    }

    @Test
    void aConstantOfTheCursorIsThePositionWhereItStands() throws Exception {
        ProgramArtifact artifact = compile(
                ".ORG 2|2",
                ".CONST X (@ + (0|2))",
                ".ORG 7|7",
                "NOP",
                ".ORG X",
                "NOP");

        assertThat(codeAt(artifact, 2, 4)).isTrue();
        assertThat(artifact.constantValues()).containsEntry("X", "2|4");
    }

    @Test
    void anAliasOfALayoutValuedConstant() throws Exception {
        ProgramArtifact artifact = compile(
                ".ORG 1|1",
                ".CONST B (@ + (0|2))",
                ".CONST A B",
                ".ORG A",
                "NOP");

        assertThat(codeAt(artifact, 1, 3)).isTrue();
    }

    @Test
    void aTypedConstantOfTheCursorsRow() throws Exception {
        ProgramArtifact artifact = compile(
                ".ORG 0|2",
                ".CONST M (DATA:(@[1]))",
                ".PLACE M 0|0");

        var expected = compile(".PLACE DATA:2 0|0").initialWorldObjects().values().iterator().next();
        assertThat(artifact.initialWorldObjects().values()).containsExactly(expected);
        assertThat(artifact.constantValues()).containsEntry("M", "DATA:2");
    }

    private static boolean codeAt(ProgramArtifact artifact, int... coordinate) {
        return artifact.machineCodeLayout().keySet().stream().anyMatch(coord -> Arrays.equals(coord, coordinate));
    }

    private static ProgramArtifact compile(String... lines) throws Exception {
        return new Compiler().compile(List.of(lines), "layout.evo", ENV);
    }
}
