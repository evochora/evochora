package org.evochora.compiler.features.label;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.Molecule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A label is identified by its path, the levels it stands in followed by its name: one label
 * name in two procedures is two labels, each with a cell of its own and a value of its own, and
 * every jump to it carries the value of the label on its own level. A label name that an
 * enclosing level already has is reported instead.
 */
@Tag("unit")
class QualifiedNamesTest {

    private static final EnvironmentProperties ENV = new EnvironmentProperties(new int[]{100, 100}, true);

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void aLabelNameInTwoProceduresIsTwoLabels() throws Exception {
        ProgramArtifact artifact = new Compiler().compile(List.of(
                "START:",
                "  CALL A",
                "  CALL B",
                ".PROC A",
                "  JMPI DONE",
                "DONE:",
                "  RET",
                ".ENDPROC",
                ".PROC B",
                "  JMPI DONE",
                "DONE:",
                "  RET",
                ".ENDPROC"), "main.evo", ENV);

        assertTwoLabels(artifact, "A.DONE", "B.DONE");
    }

    @Test
    void aLabelInAProcedureNamedLikeALaterModuleLevelLabelIsReported() {
        assertThatThrownBy(() -> new Compiler().compile(List.of(
                "START:",
                "  CALL P",
                "  JMPI DONE",
                ".PROC P",
                "  JMPI DONE",
                "DONE:",
                "  RET",
                ".ENDPROC",
                "DONE:",
                "  NOP"), "main.evo", ENV))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("'DONE' is already defined at ")
                .hasMessageContaining("main.evo:9, on an enclosing level");
    }

    /**
     * A constant of a procedure reached from the module by its path is followed in the procedure
     * that defines it: the constant it is defined as is a name of that procedure, visible there
     * without being exported. The token of the path is filed under the procedure's scope.
     */
    @Test
    void aProcedureConstantReachedByItsPathIsFollowedInTheProcedure() throws Exception {
        ProgramArtifact artifact = new Compiler().compile(List.of(
                "EXPORT .PROC CLAMP",
                "  .CONST LIMIT DATA:7",
                "  EXPORT .CONST N LIMIT",
                "  RET",
                ".ENDPROC",
                "  SETI %DR0 CLAMP.N"), "main.evo", ENV);

        assertThat(artifact.tokenMap().values())
                .filteredOn(token -> token.tokenText().equals("CLAMP.N"))
                .singleElement()
                .satisfies(token -> {
                    assertThat(token.scope()).isEqualTo("CLAMP");
                    assertThat(token.qualifiedName()).isEqualTo("CLAMP.N");
                });
    }

    /**
     * Each of the two paths has a value of its own, one label cell carries it at a coordinate of
     * its own, and the jumps to the two labels carry the two values.
     */
    private static void assertTwoLabels(ProgramArtifact artifact, String first, String second) {
        assertThat(artifact.labelNameToValue()).containsKeys(first, second);
        int firstValue = artifact.labelNameToValue().get(first);
        int secondValue = artifact.labelNameToValue().get(second);
        assertThat(firstValue).isNotEqualTo(secondValue);

        List<int[]> firstCells = cellsOf(artifact, Config.TYPE_LABEL, firstValue);
        List<int[]> secondCells = cellsOf(artifact, Config.TYPE_LABEL, secondValue);
        assertThat(firstCells).hasSize(1);
        assertThat(secondCells).hasSize(1);
        assertThat(firstCells.get(0)).isNotEqualTo(secondCells.get(0));

        assertThat(cellsOf(artifact, Config.TYPE_LABELREF, firstValue)).isNotEmpty();
        assertThat(cellsOf(artifact, Config.TYPE_LABELREF, secondValue)).isNotEmpty();
    }

    /**
     * The coordinates of the cells of the machine code that hold a molecule of the given type and
     * value. The value is compared in the bits a molecule holds, since a label value may exceed
     * the signed range of a molecule's value.
     */
    private static List<int[]> cellsOf(ProgramArtifact artifact, int type, int value) {
        List<int[]> cells = new ArrayList<>();
        for (Map.Entry<int[], Integer> cell : artifact.machineCodeLayout().entrySet()) {
            Molecule molecule = Molecule.fromInt(cell.getValue());
            if (molecule.type() == type && (molecule.value() & Config.VALUE_MASK) == (value & Config.VALUE_MASK)) {
                cells.add(cell.getKey());
            }
        }
        return cells;
    }
}
