package org.evochora.compiler.features.control;

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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compiles control blocks through the whole compiler. A block is labels and nothing else: each
 * pattern of control flow written with a block compiles to the cells its hand-written twin
 * compiles to, the twin being the same code with an ordinary label wherever a block word stands.
 * The artifact names the block's labels by their paths.
 */
@Tag("unit")
class ControlBlockCompileTest {

    private static final EnvironmentProperties ENV = new EnvironmentProperties(new int[]{100, 100}, true);

    /** A while loop with a break in its body and one case, the place the loop leaves to when blocked. */
    private static final List<String> WHILE_BLOCK = List.of(
            "  SETI %DR0 DATA:0",
            ".CONTROL WALK",
            "  INPI 1|0",
            "  JMPI BLOCKED",
            "  IFI %DR0 DATA:5",
            "  JMPI END",
            "  INCR %DR0",
            "  JMPI WALK",
            ".CASE BLOCKED",
            "  NOP",
            ".ENDCONTROL",
            "  NOP");

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void ifThenElseifElseCompilesAsItsHandWrittenTwin() throws Exception {
        assertSameCells(List.of(
                ".CONTROL ENERGY",
                "  LTI %DR0 DATA:1000",
                "  JMPI STARVING",
                "  JMPI TEST_RICH",
                ".CASE STARVING",
                "  NOP",
                "  JMPI END",
                ".CASE TEST_RICH",
                "  GTI %DR0 DATA:50000",
                "  JMPI RICH",
                "  JMPI NORMAL",
                ".CASE RICH",
                "  INCR %DR1",
                "  JMPI END",
                ".CASE NORMAL",
                "  INCR %DR2",
                ".ENDCONTROL"), List.of(
                "ENERGY:",
                "  LTI %DR0 DATA:1000",
                "  JMPI ENERGY_STARVING",
                "  JMPI ENERGY_TEST_RICH",
                "ENERGY_STARVING:",
                "  NOP",
                "  JMPI ENERGY_END",
                "ENERGY_TEST_RICH:",
                "  GTI %DR0 DATA:50000",
                "  JMPI ENERGY_RICH",
                "  JMPI ENERGY_NORMAL",
                "ENERGY_RICH:",
                "  INCR %DR1",
                "  JMPI ENERGY_END",
                "ENERGY_NORMAL:",
                "  INCR %DR2",
                "ENERGY_END:"));
    }

    @Test
    void anOrChainCompilesAsItsHandWrittenTwin() throws Exception {
        assertSameCells(List.of(
                ".CONTROL MOVE",
                "  IFPI 1|0",
                "  JMPI STEP",
                "  IFPI 0|1",
                "  JMPI STEP",
                "  JMPI BLOCKED",
                ".CASE STEP",
                "  INCR %DR0",
                "  JMPI END",
                ".CASE BLOCKED",
                "  NOP",
                ".ENDCONTROL"), List.of(
                "MOVE:",
                "  IFPI 1|0",
                "  JMPI MOVE_STEP",
                "  IFPI 0|1",
                "  JMPI MOVE_STEP",
                "  JMPI MOVE_BLOCKED",
                "MOVE_STEP:",
                "  INCR %DR0",
                "  JMPI MOVE_END",
                "MOVE_BLOCKED:",
                "  NOP",
                "MOVE_END:"));
    }

    @Test
    void anAndChainCompilesAsItsHandWrittenTwin() throws Exception {
        assertSameCells(List.of(
                ".CONTROL BUILD",
                "  IFFR %DR1",
                "  JMPI END",
                "  IFBI 0|0",
                "  JMPI END",
                "  INCR %DR0",
                ".ENDCONTROL"), List.of(
                "BUILD:",
                "  IFFR %DR1",
                "  JMPI BUILD_END",
                "  IFBI 0|0",
                "  JMPI BUILD_END",
                "  INCR %DR0",
                "BUILD_END:"));
    }

    @Test
    void aSwitchWithAFallThroughCaseCompilesAsItsHandWrittenTwin() throws Exception {
        assertSameCells(List.of(
                ".CONTROL BEHAVIOUR",
                "  IFI %DR0 DATA:0",
                "  JMPI HARVEST",
                "  IFI %DR0 DATA:1",
                "  JMPI REPRODUCE",
                "  JMPI EXPLORE",
                ".CASE HARVEST",
                "  INCR %DR1",
                ".CASE REPRODUCE",
                "  INCR %DR2",
                "  JMPI END",
                ".CASE EXPLORE",
                "  INCR %DR3",
                ".ENDCONTROL"), List.of(
                "BEHAVIOUR:",
                "  IFI %DR0 DATA:0",
                "  JMPI BEHAVIOUR_HARVEST",
                "  IFI %DR0 DATA:1",
                "  JMPI BEHAVIOUR_REPRODUCE",
                "  JMPI BEHAVIOUR_EXPLORE",
                "BEHAVIOUR_HARVEST:",
                "  INCR %DR1",
                "BEHAVIOUR_REPRODUCE:",
                "  INCR %DR2",
                "  JMPI BEHAVIOUR_END",
                "BEHAVIOUR_EXPLORE:",
                "  INCR %DR3",
                "BEHAVIOUR_END:"));
    }

    @Test
    void aWhileLoopWithABreakCompilesAsItsHandWrittenTwin() throws Exception {
        assertSameCells(WHILE_BLOCK, List.of(
                "  SETI %DR0 DATA:0",
                "WALK:",
                "  INPI 1|0",
                "  JMPI WALK_BLOCKED",
                "  IFI %DR0 DATA:5",
                "  JMPI WALK_END",
                "  INCR %DR0",
                "  JMPI WALK",
                "WALK_BLOCKED:",
                "  NOP",
                "WALK_END:",
                "  NOP"));
    }

    @Test
    void anUntilLoopCompilesAsItsHandWrittenTwin() throws Exception {
        assertSameCells(List.of(
                ".CONTROL RETRY",
                "  INCR %DR0",
                "  IFER",
                "  JMPI RETRY",
                ".ENDCONTROL"), List.of(
                "RETRY:",
                "  INCR %DR0",
                "  IFER",
                "  JMPI RETRY",
                "RETRY_END:"));
    }

    @Test
    void aForLoopCompilesAsItsHandWrittenTwin() throws Exception {
        assertSameCells(List.of(
                "  SETI %DR0 DATA:0",
                ".CONTROL BUILD",
                "  GETI %DR0 DATA:10",
                "  JMPI END",
                "  INCR %DR1",
                "  INCR %DR0",
                "  JMPI BUILD",
                ".ENDCONTROL"), List.of(
                "  SETI %DR0 DATA:0",
                "BUILD:",
                "  GETI %DR0 DATA:10",
                "  JMPI BUILD_END",
                "  INCR %DR1",
                "  INCR %DR0",
                "  JMPI BUILD",
                "BUILD_END:"));
    }

    @Test
    void leavingAnOuterLoopFromANestedBlockCompilesAsItsHandWrittenTwin() throws Exception {
        assertSameCells(List.of(
                ".CONTROL WALK",
                "  INPI 1|0",
                "  JMPI END",
                "  .CONTROL CHECK",
                "    IFI %DR0 DATA:0",
                "    JMPI WALK.END",
                "    IFI %DR0 DATA:1",
                "    JMPI END",
                "    INCR %DR1",
                "  .ENDCONTROL",
                "  JMPI WALK",
                ".ENDCONTROL"), List.of(
                "WALK:",
                "  INPI 1|0",
                "  JMPI WALK_END",
                "WALK_CHECK:",
                "    IFI %DR0 DATA:0",
                "    JMPI WALK_END",
                "    IFI %DR0 DATA:1",
                "    JMPI WALK_CHECK_END",
                "    INCR %DR1",
                "WALK_CHECK_END:",
                "  JMPI WALK",
                "WALK_END:"));
    }

    /** A part that begins on a new row has its {@code .ORG} before the {@code .CASE}, as the twin has it before the label. */
    @Test
    void anOrgBeforeACaseCompilesAsAnOrgBeforeTheTwinsLabel() throws Exception {
        assertSameCells(List.of(
                ".CONTROL WALK",
                "  INPI 1|0",
                "  JMPI BLOCKED",
                "  JMPI WALK",
                ".ORG 0|1",
                ".CASE BLOCKED",
                "  NOP",
                ".ENDCONTROL"), List.of(
                "WALK:",
                "  INPI 1|0",
                "  JMPI WALK_BLOCKED",
                "  JMPI WALK",
                ".ORG 0|1",
                "WALK_BLOCKED:",
                "  NOP",
                "WALK_END:"));
    }

    @Test
    void theBlockItsCaseAndItsEndAreThreeLabelsNamedByTheirPaths() throws Exception {
        ProgramArtifact artifact = compile(WHILE_BLOCK);

        Map<String, Integer> labels = artifact.labelNameToValue();
        assertThat(labels).containsKeys("WALK", "WALK.BLOCKED", "WALK.END");
        assertThat(List.of(labels.get("WALK"), labels.get("WALK.BLOCKED"), labels.get("WALK.END")))
                .doesNotHaveDuplicates();
        for (String path : List.of("WALK", "WALK.BLOCKED", "WALK.END")) {
            assertThat(cellsOf(artifact, Config.TYPE_LABEL, labels.get(path))).as(path).hasSize(1);
        }
    }

    @Test
    void aBlockInsideACaseIsNamedByItsPathThroughTheOuterBlock() throws Exception {
        ProgramArtifact artifact = compile(List.of(
                ".CONTROL WALK",
                "  JMPI BLOCKED",
                ".CASE BLOCKED",
                "  .CONTROL INNER",
                "    JMPI END",
                "  .ENDCONTROL",
                ".ENDCONTROL"));

        assertThat(artifact.labelNameToValue()).containsKeys("WALK.INNER", "WALK.INNER.END");
    }

    @Test
    void anExportedCaseIsReachedFromAfterTheBlock() throws Exception {
        ProgramArtifact artifact = compile(List.of(
                "EXPORT .CONTROL WALK",
                "  NOP",
                "EXPORT .CASE BLOCKED",
                "  NOP",
                "EXPORT .ENDCONTROL",
                "  JMPI WALK.BLOCKED"));

        assertThat(artifact.labelNameToValue()).containsKeys("WALK", "WALK.BLOCKED", "WALK.END");
        assertThat(cellsOf(artifact, Config.TYPE_LABELREF, artifact.labelNameToValue().get("WALK.BLOCKED")))
                .hasSize(1);
    }

    @Test
    void aMacroTakingTheBlockNameOpensOneBlockPerExpansion() throws Exception {
        ProgramArtifact artifact = compile(List.of(
                ".MACRO LOOP NAME",
                "  .CONTROL NAME",
                "    INCR %DR0",
                "    JMPI NAME",
                "  .ENDCONTROL",
                ".ENDMACRO",
                "  LOOP A",
                "  LOOP B"));

        assertThat(artifact.labelNameToValue()).containsKeys("A", "A.END", "B", "B.END");
    }

    private static ProgramArtifact compile(List<String> source) throws CompilationException {
        return new Compiler().compile(source, "main.evo", ENV);
    }

    /**
     * Compiles a program with control blocks and its hand-written twin and asserts that both
     * place the same molecules at the same coordinates. Label and label-reference cells are
     * compared by type only, since a label's value derives from its name and the names differ;
     * every other cell is compared by its whole value.
     */
    private static void assertSameCells(List<String> blockProgram, List<String> twinProgram) throws CompilationException {
        Map<List<Integer>, Integer> blockCells = cellsByCoordinate(compile(blockProgram));
        Map<List<Integer>, Integer> twinCells = cellsByCoordinate(compile(twinProgram));

        assertThat(blockCells.keySet()).isEqualTo(twinCells.keySet());
        for (Map.Entry<List<Integer>, Integer> cell : blockCells.entrySet()) {
            int blockValue = cell.getValue();
            int twinValue = twinCells.get(cell.getKey());
            int type = blockValue & Config.TYPE_MASK;
            assertThat(type).as("type at %s", cell.getKey()).isEqualTo(twinValue & Config.TYPE_MASK);
            if (type != Config.TYPE_LABEL && type != Config.TYPE_LABELREF) {
                assertThat(blockValue).as("molecule at %s", cell.getKey()).isEqualTo(twinValue);
            }
        }
    }

    /** The machine code of an artifact keyed by coordinate content rather than array identity. */
    private static Map<List<Integer>, Integer> cellsByCoordinate(ProgramArtifact artifact) {
        Map<List<Integer>, Integer> cells = new HashMap<>();
        for (Map.Entry<int[], Integer> cell : artifact.machineCodeLayout().entrySet()) {
            cells.put(Arrays.stream(cell.getKey()).boxed().toList(), cell.getValue());
        }
        return cells;
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
