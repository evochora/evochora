package org.evochora.compiler.directives;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The layout directives {@code .ORG}, {@code .DIR} and {@code .PLACE} take operands and compute
 * them where they stand: the cursor {@code @}, the component of a position relative to the line's
 * origin, and {@code POSITION(v)}, a place of the program that no origin is added to. Programs are
 * compiled through the whole pipeline and judged by the cells of the artifact or the message.
 */
@Tag("integration")
class LayoutComputationTest {

    private static final EnvironmentProperties ENV = new EnvironmentProperties(new int[]{100, 100}, true);

    @TempDir
    Path sourceRoot;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void anOriginAtTheCursorPlusAVectorContinuesTwoRowsBelow() throws Exception {
        ProgramArtifact artifact = compile(
                ".ORG 0|0",
                "NOP",
                ".ORG (@ + (0|2))",
                "NOP");

        // The first NOP took 0|0, so the cursor stood at 1|0.
        assertThat(codeAt(artifact, 1, 2)).isTrue();
    }

    @Test
    void theComponentOfTheCursorIsANumber() throws Exception {
        ProgramArtifact artifact = compile(
                ".ORG 0|3",
                "NOP",
                ".ORG 4|(@[1] + 1)",
                "NOP");

        assertThat(codeAt(artifact, 4, 4)).isTrue();
    }

    /**
     * In a module imported at 5|5, a vector counts from the module's origin and a position is a
     * place of the program: {@code POSITION(v)} is the place {@code v} from the module's origin,
     * and the cursor stays where it is, no origin added to it.
     */
    @Test
    void aPositionInAModuleIsAPlaceOfTheProgram() throws Exception {
        write("lib.evo",
                ".ORG POSITION(1|1)",
                "NOP",
                ".ORG 1|2",
                "NOP",
                ".ORG @",
                "NOP");
        write("main.evo",
                ".ORG 5|5",
                ".IMPORT \"lib.evo\" AS LIB");

        ProgramArtifact artifact = compileFile("main.evo");

        assertThat(codeAt(artifact, 6, 6)).as("POSITION(1|1) from the module's origin").isTrue();
        assertThat(codeAt(artifact, 6, 7)).as("the vector 1|2 from the module's origin").isTrue();
        assertThat(codeAt(artifact, 7, 7)).as("the cursor, after the NOP at 6|7").isTrue();
    }

    @Test
    void aRangeOfComputedBoundsPlacesACellPerNumber() throws Exception {
        ProgramArtifact artifact = compile(
                ".CONST W 3",
                ".PLACE STRUCTURE:1 (W-1)..(W+1)|0");

        assertThat(artifact.initialWorldObjects().keySet()).containsExactlyInAnyOrder(
                new int[]{2, 0}, new int[]{3, 0}, new int[]{4, 0});
    }

    @Test
    void aPlacementOfOneVectorConstantCountsFromTheOrigin() throws Exception {
        ProgramArtifact artifact = compile(
                ".CONST CORNER 2|3",
                ".PLACE STRUCTURE:1 CORNER, (CORNER + (1|0))");

        assertThat(artifact.initialWorldObjects().keySet()).containsExactlyInAnyOrder(
                new int[]{2, 3}, new int[]{3, 3});
    }

    @Test
    void aDirectionGivenByAVectorConstant() throws Exception {
        ProgramArtifact artifact = compile(
                ".CONST V 0|1",
                ".ORG 0|0",
                ".DIR V",
                "NOP",
                "NOP");

        assertThat(codeAt(artifact, 0, 0)).isTrue();
        assertThat(codeAt(artifact, 0, 1)).isTrue();
    }

    @Test
    void aVectorWhereANumberIsExpectedIsReported() {
        assertThatThrownBy(() -> compile(
                ".CONST V 1|1",
                ".ORG V|0",
                "NOP"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Expected a number.");
    }

    @Test
    void aNumberWhereAVectorIsExpectedIsReported() {
        assertThatThrownBy(() -> compile(
                ".CONST N DATA:5",
                ".DIR N",
                "NOP"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Expected a vector.");
    }

    /**
     * A label reaches the layout by its name; the layout does not know the names yet, so every
     * name there is a forward reference.
     */
    @Test
    void aLabelInAnOriginIsAForwardReference() {
        assertThatThrownBy(() -> compile(
                ".ORG NW",
                "NOP",
                "NW:",
                "NOP"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Forward reference to 'NW'.");
    }

    /**
     * A coordinate is addressed with an {@code int}; a component beyond that is reported at the
     * directive when the layout turns it into a coordinate.
     */
    @Test
    void aComponentBeyondACoordinateIsReported() {
        assertThatThrownBy(() -> compile(".PLACE DATA:1 4294967296|0"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Value 4294967296 out of range for a coordinate.");
    }

    /**
     * A single number is a vector of one component, the coordinate of a one-dimensional world.
     */
    @Test
    void aSingleNumberIsAnOriginInAOneDimensionalWorld() throws Exception {
        ProgramArtifact artifact = new Compiler().compile(List.of(".ORG 5", "NOP"), "line.evo",
                new EnvironmentProperties(new int[]{20}, true));

        assertThat(codeAt(artifact, 5)).isTrue();
    }

    @Test
    void aSingleNumberIsOneComponentTooFewInATwoDimensionalWorld() {
        assertThatThrownBy(() -> compile(".ORG 5", "NOP"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Origin [5] has 1 components, the world has 2 dimensions.");
    }

    @Test
    void theMoleculeOfAPlacementMayBeATypedConstantOrAComputation() throws Exception {
        ProgramArtifact artifact = compile(
                ".CONST WALL STRUCTURE:100",
                ".PLACE WALL 1..3|0",
                ".PLACE (WALL) 0|1");

        // The molecule a written STRUCTURE:100 places
        var written = compile(".PLACE STRUCTURE:100 0|0").initialWorldObjects().values().iterator().next();
        assertThat(artifact.initialWorldObjects()).hasSize(4);
        assertThat(artifact.initialWorldObjects().values()).allSatisfy(molecule -> assertThat(molecule).isEqualTo(written));
        assertThat(written.value()).isEqualTo(100);
    }

    @Test
    void aMoleculeThatIsANumberIsReported() {
        assertThatThrownBy(() -> compile(
                ".CONST WALL 100",
                ".PLACE WALL 1|0"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Expected a typed literal (e.g. DATA:5) for .PLACE.");
    }

    /**
     * The molecule may be a value only the layout knows: the cursor's column at the line.
     */
    @Test
    void theMoleculeOfAPlacementMayReadTheCursor() throws Exception {
        ProgramArtifact artifact = compile(
                ".ORG 3|0",
                ".PLACE DATA:(@[0]) 1|0");

        var expected = compile(".PLACE DATA:3 1|0").initialWorldObjects().values().iterator().next();
        assertThat(artifact.initialWorldObjects()).hasSize(1);
        assertThat(artifact.initialWorldObjects().keySet()).containsExactly(new int[]{1, 0});
        assertThat(artifact.initialWorldObjects().values()).containsExactly(expected);
    }

    @Test
    void aMoleculeWhoseLayoutValueIsANumberIsReported() {
        assertThatThrownBy(() -> compile(".PLACE (@[0]) 0|0"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Expected a typed literal (e.g. DATA:5) for .PLACE.");
    }

    private static boolean codeAt(ProgramArtifact artifact, int... coordinate) {
        return artifact.machineCodeLayout().keySet().stream().anyMatch(coord -> Arrays.equals(coord, coordinate));
    }

    private static ProgramArtifact compile(String... lines) throws Exception {
        return new Compiler().compile(List.of(lines), "layout.evo", ENV);
    }

    private void write(String fileName, String... lines) throws Exception {
        Files.writeString(sourceRoot.resolve(fileName), String.join("\n", lines) + "\n");
    }

    private ProgramArtifact compileFile(String fileName) throws Exception {
        CompilerOptions options = new CompilerOptions(List.of(new SourceRoot(sourceRoot.toString(), null)), Map.of(), Map.of());
        return new Compiler().compile(fileName, ENV, options);
    }
}
