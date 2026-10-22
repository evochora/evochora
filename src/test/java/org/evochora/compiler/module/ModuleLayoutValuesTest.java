package org.evochora.compiler.module;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.Molecule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The named layout values across modules: a position is a place of the program wherever it is
 * used, the component of a position counts from the origin of the line that takes it, and a
 * value only the layout computes is computed where its definition stands, at the import line for
 * a bound computation. Programs are written to disk, compiled through the whole pipeline and
 * judged by the cells of the artifact.
 */
@Tag("integration")
class ModuleLayoutValuesTest {

    private static final EnvironmentProperties ENV = new EnvironmentProperties(new int[]{100, 100}, true);

    @TempDir
    Path sourceRoot;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    /**
     * The main file hands a module imported at 10|10 two places of its own frame; the module
     * writes the north edge of a shell around them, puts a corner label on the first, and returns
     * to its own origin. The main file's code continues where the module left the cursor.
     */
    @Test
    void aModuleBuildsAShellAroundThePositionsItIsGiven() throws Exception {
        write("shell.evo",
                ".REQUIRE VALUE BODY_NW",
                ".REQUIRE VALUE BODY_SE",
                ".CONST OUT_NW (BODY_NW - (1|1))",
                ".CONST OUT_SE (BODY_SE + (1|1))",
                ".PLACE STRUCTURE:100 OUT_NW[0]..OUT_SE[0]|OUT_NW[1]",
                ".ORG BODY_NW",
                "CNW:",
                ".ORG 0|0",
                "NOP");
        write("main.evo",
                ".CONST WIDTH 4",
                ".CONST HEIGHT 3",
                ".ORG 10|10",
                ".IMPORT \"shell.evo\" AS SHELL USING VALUE BODY_NW POSITION(0|0) VALUE BODY_SE POSITION(WIDTH-1|HEIGHT-1)",
                "NOP");

        ProgramArtifact artifact = compile(Map.of());

        assertThat(artifact.initialWorldObjects().keySet()).containsExactlyInAnyOrder(
                new int[]{-1, -1}, new int[]{0, -1}, new int[]{1, -1},
                new int[]{2, -1}, new int[]{3, -1}, new int[]{4, -1});
        assertThat(labelAt(artifact, "SHELL.CNW", 0, 0)).as("the corner label at the main file's 0|0").isTrue();
        assertThat(codeAt(artifact, 10, 10)).as("the module's NOP at its own origin").isTrue();
        assertThat(codeAt(artifact, 11, 10)).as("the main file's NOP where the module left the cursor").isTrue();
        assertThat(artifact.machineCodeLayout()).hasSize(3);
        assertThat(artifact.constantValues())
                .containsEntry("SHELL.BODY_NW", "0|0")
                .containsEntry("SHELL.BODY_SE", "3|2")
                .containsEntry("SHELL.OUT_NW", "-1|-1")
                .containsEntry("SHELL.OUT_SE", "4|3");
    }

    @Test
    void aPositionHandedDownTwoLevelsIsTheSamePlace() throws Exception {
        write("sub.evo",
                ".REQUIRE VALUE P",
                ".ORG P",
                "NOP");
        write("mid.evo",
                ".REQUIRE VALUE BODY_NW",
                ".ORG 3|3",
                ".IMPORT \"sub.evo\" AS SUB USING VALUE P BODY_NW");
        write("main.evo",
                ".ORG 5|5",
                ".IMPORT \"mid.evo\" AS MID USING VALUE BODY_NW POSITION(1|1)");

        ProgramArtifact artifact = compile(Map.of());

        assertThat(codeAt(artifact, 1, 1)).isTrue();
        assertThat(artifact.machineCodeLayout()).hasSize(1);
    }

    @Test
    void anExportedPositionIsRightInTheImporter() throws Exception {
        write("lib.evo",
                "NOP",
                "EXPORT .CONST CORNER (@ + (1|1))");
        write("main.evo",
                ".ORG 5|5",
                ".IMPORT \"lib.evo\" AS LIB",
                ".ORG LIB.CORNER",
                "NOP");

        ProgramArtifact artifact = compile(Map.of());

        // The module's NOP took 5|5, so the cursor stood at 6|5 where the constant is defined.
        assertThat(codeAt(artifact, 7, 6)).isTrue();
    }

    /**
     * A constant of the cursor in a module imported below 0|0 is a position of the program: the
     * cursor goes two rows below where the constant stands, whatever the module's origin.
     */
    @Test
    void aConstantOfTheCursorInAModuleIsAPositionOfTheProgram() throws Exception {
        write("lib.evo",
                "NOP",
                ".CONST X (@ + (0|2))",
                ".ORG X",
                "NOP");
        write("main.evo",
                ".ORG 5|5",
                ".IMPORT \"lib.evo\" AS LIB");

        ProgramArtifact artifact = compile(Map.of());

        assertThat(codeAt(artifact, 6, 7)).isTrue();
        assertThat(artifact.constantValues()).containsEntry("LIB.X", "6|7");
    }

    /**
     * A placement of one single value in a module imported at 5|5: a vector is that place counted
     * from the module's origin, a given position is that place of the program.
     */
    @Test
    void aSinglePlacedVectorCountsFromTheModulesOriginAndAPositionDoesNot() throws Exception {
        write("lib.evo",
                ".REQUIRE VALUE P",
                ".CONST V 1|2",
                ".PLACE DATA:1 V",
                ".PLACE DATA:2 P",
                "NOP");
        write("main.evo",
                ".ORG 5|5",
                ".IMPORT \"lib.evo\" AS LIB USING VALUE P POSITION(30|40)");

        ProgramArtifact artifact = compile(Map.of());

        assertThat(artifact.initialWorldObjects().entrySet().stream()
                .map(entry -> Arrays.toString(entry.getKey()) + "=" + entry.getValue().value()))
                .containsExactlyInAnyOrder("[6, 7]=1", "[30, 40]=2");
    }

    /**
     * A {@code .SOURCE}d file keeps the placement of the file that takes it in: its label is a
     * position of the program, while a vector {@code .ORG} in it counts from the file's own origin.
     */
    @Test
    void aSourcedFileUsesALabelAGivenPositionAndAVectorOrigin() throws Exception {
        write("part.evo",
                "HERE:",
                ".ORG 0|2",
                "NOP",
                ".ORG (HERE + (2|0))",
                "NOP");
        write("main.evo",
                ".ORG 5|5",
                ".SOURCE \"part.evo\"",
                ".ORG (HERE + (1|0))",
                "NOP");

        ProgramArtifact artifact = compile(Map.of());

        assertThat(labelAt(artifact, "HERE", 5, 5)).isTrue();
        assertThat(codeAt(artifact, 5, 7)).as("the vector 0|2 from the file's origin").isTrue();
        assertThat(codeAt(artifact, 7, 5)).as("two columns right of the label, in the file").isTrue();
        assertThat(codeAt(artifact, 6, 5)).as("one column right of the label, in the main file").isTrue();
    }

    @Test
    void aBoundComputationOfTheCursorIsComputedAtTheImportLine() throws Exception {
        write("lib.evo",
                ".REQUIRE VALUE X",
                ".ORG X",
                "NOP");
        write("main.evo",
                ".ORG 5|5",
                "NOP",
                ".IMPORT \"lib.evo\" AS LIB USING VALUE X (@ + (0|2))");

        ProgramArtifact artifact = compile(Map.of());

        // The main file's NOP took 5|5, so the cursor stood at 6|5 at the import line.
        assertThat(codeAt(artifact, 6, 7)).isTrue();
        assertThat(artifact.constantValues()).containsEntry("LIB.X", "6|7");
    }

    @Test
    void aDefaultOfTheCursorIsComputedAtTheRequirementsLine() throws Exception {
        write("lib.evo",
                ".REQUIRE VALUE X (@ + (0|2))",
                ".ORG X",
                "NOP");
        write("main.evo",
                ".ORG 5|5",
                ".IMPORT \"lib.evo\" AS LIB");

        ProgramArtifact artifact = compile(Map.of());

        assertThat(codeAt(artifact, 5, 7)).isTrue();
        assertThat(artifact.constantValues()).containsEntry("LIB.X", "5|7");
    }

    @Test
    void aConfiguredValueOfTheCursorIsComputedAtTheRequirementsLine() throws Exception {
        write("main.evo",
                ".ORG 3|3",
                ".REQUIRE VALUE X",
                ".ORG X",
                "NOP");

        ProgramArtifact artifact = compile(Map.of("X", "(@ + (0|1))"));

        assertThat(codeAt(artifact, 3, 4)).isTrue();
        assertThat(artifact.constantValues()).containsEntry("X", "3|4");
    }

    private static boolean codeAt(ProgramArtifact artifact, int... coordinate) {
        return artifact.machineCodeLayout().keySet().stream().anyMatch(coord -> Arrays.equals(coord, coordinate));
    }

    /**
     * Whether the cell at the coordinate holds the label of the given name.
     */
    private static boolean labelAt(ProgramArtifact artifact, String name, int... coordinate) {
        Integer value = artifact.labelNameToValue().get(name);
        return value != null && artifact.machineCodeLayout().entrySet().stream()
                .filter(entry -> Arrays.equals(entry.getKey(), coordinate))
                .map(entry -> Molecule.fromInt(entry.getValue()))
                .anyMatch(molecule -> molecule.type() == Config.TYPE_LABEL && molecule.value() == value);
    }

    private ProgramArtifact compile(Map<String, String> values) throws Exception {
        CompilerOptions options = new CompilerOptions(List.of(new SourceRoot(sourceRoot.toString(), null)), Map.of(), values);
        return new Compiler().compile("main.evo", ENV, options);
    }

    private void write(String fileName, String... lines) throws Exception {
        Files.writeString(sourceRoot.resolve(fileName), String.join("\n", lines) + "\n");
    }
}
