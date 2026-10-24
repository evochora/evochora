package org.evochora.compiler.e2e;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilationException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * A constant, a register alias and a value a module requires are known anywhere on their level,
 * above or below the line that defines them. A definition whose value is a computation is
 * computed when a name first needs it, and its errors are reported once, at the definition. The
 * layout keeps the text order: a label it computes with must stand above the line that computes
 * with it.
 * <p>
 * Each test writes a small program to disk and compiles it through the whole pipeline.
 */
@Tag("integration")
class ForwardReferenceTest {

    private static final EnvironmentProperties ENV = new EnvironmentProperties(new int[]{100, 100}, true);

    @TempDir
    Path sourceRoot;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    /**
     * An instruction may use a literal constant defined below it.
     */
    @Test
    void aLiteralConstantIsUsedAboveItsLine() throws Exception {
        write("main.evo",
                "START:",
                "  SETI %DR0 LATE",
                ".CONST LATE DATA:7");

        ProgramArtifact artifact = compile("main.evo");

        assertThat(cellAt(artifact, 3, 0)).isEqualTo(new Molecule(Config.TYPE_DATA, 7, 0));
    }

    /**
     * A computation above the constants it names is computed from them: the computed constant
     * and the operand that uses it take the values of definitions further below.
     */
    @Test
    void aComputedConstantIsComputedFromConstantsDefinedBelow() throws Exception {
        write("main.evo",
                "START:",
                "  SETI %DR0 DATA:(A+1)",
                ".CONST A (B*2)",
                ".CONST B 5");

        ProgramArtifact artifact = compile("main.evo");

        assertThat(artifact.constantValues()).contains(Map.entry("A", "10"), Map.entry("B", "5"));
        assertThat(cellAt(artifact, 3, 0)).isEqualTo(new Molecule(Config.TYPE_DATA, 11, 0));
    }

    /**
     * A chain of names that runs through constants below the use ends at the value of the last.
     */
    @Test
    void aChainOfNamesRunsThroughLaterConstants() throws Exception {
        write("main.evo",
                "START:",
                "  SETI %DR0 A",
                ".CONST A B",
                ".CONST B DATA:3");

        ProgramArtifact artifact = compile("main.evo");

        assertThat(artifact.constantValues()).containsEntry("A", "DATA:3");
        assertThat(cellAt(artifact, 3, 0)).isEqualTo(new Molecule(Config.TYPE_DATA, 3, 0));
    }

    /**
     * Inside a procedure, a constant of the procedure is known above its line as well.
     */
    @Test
    void aConstantOfAProcedureIsUsedAboveItsLine() throws Exception {
        write("main.evo",
                ".PROC P",
                "  SETI %PDR0 LIMIT",
                "  .CONST LIMIT DATA:23",
                "  RET",
                ".ENDPROC",
                "START:",
                "  CALL P");

        ProgramArtifact artifact = compile("main.evo");

        assertThat(artifact.machineCodeLayout().values()).extracting(Molecule::fromInt)
                .contains(new Molecule(Config.TYPE_DATA, 23, 0));
    }

    /**
     * An instruction may use a register alias defined below it.
     */
    @Test
    void aRegisterAliasIsUsedAboveItsLine() throws Exception {
        write("main.evo",
                "START:",
                "  SETI %TMP DATA:1",
                ".REG %TMP %DR3");

        ProgramArtifact artifact = compile("main.evo");

        assertThat(artifact.registerAliasMap()).containsKey("%TMP");
    }

    /**
     * A module may use a value it requires above the {@code .REQUIRE VALUE} line; without a
     * binding the default holds.
     */
    @Test
    void aRequiredValueIsUsedAboveItsLineWithItsDefault() throws Exception {
        writeWalk();
        write("main.evo",
                ".IMPORT \"walk.evo\" AS W",
                "START:",
                "  CALL W.GO REF %DR0");

        ProgramArtifact artifact = compile("main.evo");

        assertThat(artifact.constantValues()).containsEntry("W.STEPS", "DATA:10");
    }

    /**
     * A module may use a value it requires above the {@code .REQUIRE VALUE} line; the importer's
     * binding holds there.
     */
    @Test
    void aRequiredValueIsUsedAboveItsLineWithTheImportersBinding() throws Exception {
        writeWalk();
        write("main.evo",
                ".IMPORT \"walk.evo\" AS W USING VALUE STEPS DATA:4",
                "START:",
                "  CALL W.GO REF %DR0");

        ProgramArtifact artifact = compile("main.evo");

        assertThat(artifact.constantValues()).containsEntry("W.STEPS", "DATA:4");
    }

    /**
     * The importer may bind a required value to a computation of constants it defines below the
     * import line.
     */
    @Test
    void aRequiredValueIsBoundToAComputationOfLaterImporterConstants() throws Exception {
        writeWalk();
        write("main.evo",
                ".IMPORT \"walk.evo\" AS W USING VALUE STEPS DATA:(A+B)",
                ".CONST A 3",
                ".CONST B 4",
                "START:",
                "  CALL W.GO REF %DR0");

        ProgramArtifact artifact = compile("main.evo");

        assertThat(artifact.constantValues()).containsEntry("W.STEPS", "DATA:7");
    }

    /**
     * The importer may use an exported value of a module above the import that binds it, and the
     * binding may compute from an importer constant defined below the import.
     */
    @Test
    void anExportedModuleValueIsUsedAboveTheImportThatBindsIt() throws Exception {
        write("lib.evo",
                "EXPORT .REQUIRE VALUE X DATA:0",
                "  NOP");
        write("main.evo",
                "START:",
                "  SETI %DR0 M.X",
                ".IMPORT \"lib.evo\" AS M USING VALUE X DATA:(A+1)",
                ".CONST A 4");

        ProgramArtifact artifact = compile("main.evo");

        assertThat(artifact.constantValues()).containsEntry("M.X", "DATA:5");
        assertThat(cellAt(artifact, 3, 0)).isEqualTo(new Molecule(Config.TYPE_DATA, 5, 0));
    }

    /**
     * Two computations that need each other's value are reported once, as a circle, at the
     * definition the circle was entered through.
     */
    @Test
    void aCircleOfTwoComputationsIsReportedOnce() throws Exception {
        write("main.evo",
                "# A comment line.",
                ".CONST A (B+1)",
                ".CONST B (A+1)",
                "START:",
                "  SETI %DR0 DATA:(A)");

        assertSingleError("main.evo:2: Definition of A is circular: A -> B -> A.");
    }

    /**
     * A circle of a name and a computation is reported once, naming the circle from the
     * definition it was entered through.
     */
    @Test
    void aCircleOfANameAndAComputationIsReportedOnce() throws Exception {
        write("main.evo",
                "# A comment line.",
                ".CONST A B",
                ".CONST B (A+1)",
                "START:",
                "  SETI %DR0 DATA:(A)");

        assertSingleError("main.evo:3: Definition of B is circular: B -> A -> B.");
    }

    /**
     * A circle through the default of a required value is reported at the requirement, the
     * definition the walk entered it through, as a circle of two constants is.
     */
    @Test
    void aCircleThroughARequiredDefaultIsReportedAtTheRequirement() throws Exception {
        write("main.evo",
                "# A comment line.",
                ".REQUIRE VALUE X DATA:(Y+1)",
                ".CONST Y (X+1)",
                "START:",
                "  NOP");

        assertSingleError("main.evo:2: Definition of X is circular: X -> Y -> X.");
    }

    /**
     * A computation that needs its own value is a circle of one.
     */
    @Test
    void aComputationOfItselfIsReportedOnce() throws Exception {
        write("main.evo",
                "# A comment line.",
                ".CONST A (A+1)",
                "START:",
                "  SETI %DR0 DATA:(A)");

        assertSingleError("main.evo:2: Definition of A is circular: A -> A.");
    }

    /**
     * A computation is evaluated once, however often its name is used above it, so its error is
     * reported once, at the definition, and not at the uses.
     */
    @Test
    void anErrorOfALaterConstantIsReportedOnceAtItsLine() throws Exception {
        write("main.evo",
                "START:",
                "  SETI %DR0 BAD",
                "  SETI %DR1 BAD",
                ".CONST BAD DATA:(1/0)");

        assertSingleError("main.evo:4: Division by zero.");
    }

    /**
     * The layout keeps the text order: a constant the layout computes is computed at its line,
     * and a label below that line is a forward reference there.
     */
    @Test
    void aLabelBelowAConstantTheLayoutComputesIsAForwardReference() throws Exception {
        write("main.evo",
                ".CONST P (TARGET + (0|1))",
                ".PLACE DATA:1 P",
                ".ORG 5|5",
                "TARGET:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:1: Forward reference to 'TARGET'.");
    }

    /**
     * A constant the layout computes is known to the layout from its line on: a placement above
     * the constant is a forward reference to it.
     */
    @Test
    void aConstantTheLayoutComputesBelowAPlacementIsAForwardReference() throws Exception {
        write("main.evo",
                ".ORG 5|5",
                "TARGET:",
                "  NOP",
                ".PLACE DATA:1 P",
                ".CONST P (TARGET + (0|1))");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:4: Forward reference to 'P'.");
    }

    /**
     * A constant of a procedure shadows a constant of the module for the whole procedure,
     * above its line as well.
     */
    @Test
    void aConstantOfAProcedureShadowsTheModuleAboveItsLine() throws Exception {
        write("main.evo",
                ".CONST STEP DATA:41",
                ".PROC P",
                "  SETI %PDR0 STEP",
                "  .CONST STEP DATA:42",
                "  RET",
                ".ENDPROC",
                "START:",
                "  CALL P");

        ProgramArtifact artifact = compile("main.evo");

        assertThat(artifact.machineCodeLayout().values()).extracting(Molecule::fromInt)
                .contains(new Molecule(Config.TYPE_DATA, 42, 0))
                .doesNotContain(new Molecule(Config.TYPE_DATA, 41, 0));
    }

    /**
     * A procedure is a level of its own, and a name is defined once per level there as well.
     */
    @Test
    void aSecondConstantOfTheSameNameInAProcedureNamesTheFirst() throws Exception {
        write("main.evo",
                ".PROC P",
                "  .CONST MAX DATA:1",
                "  .CONST MAX DATA:2",
                "  RET",
                ".ENDPROC",
                "START:",
                "  CALL P");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageFindingMatch("main\\.evo:3: Cannot define constant 'MAX': the name is already used at \\S*main\\.evo:2\\.");
    }

    /**
     * A module that uses its required value in a procedure above the {@code .REQUIRE VALUE} line.
     */
    private void writeWalk() throws Exception {
        write("walk.evo",
                "EXPORT .PROC GO REF X",
                "  ADDI X STEPS",
                "  RET",
                ".ENDPROC",
                ".REQUIRE VALUE STEPS DATA:10");
    }

    /**
     * Asserts that the compilation fails with exactly one error, the message at its line.
     */
    private void assertSingleError(String located) {
        CompilationException failure = catchThrowableOfType(CompilationException.class, () -> compile("main.evo"));
        assertThat(failure).as("the compilation fails").isNotNull();
        assertThat(failure.getMessage().lines().toList()).singleElement()
                .satisfies(line -> assertThat(line).endsWith(located));
    }

    /**
     * The molecule the program places at a coordinate relative to its origin.
     */
    private static Molecule cellAt(ProgramArtifact artifact, int... coordinate) {
        return artifact.machineCodeLayout().entrySet().stream()
                .filter(cell -> Arrays.equals(cell.getKey(), coordinate))
                .map(cell -> Molecule.fromInt(cell.getValue()))
                .findFirst().orElseThrow();
    }

    private void write(String fileName, String... lines) throws Exception {
        Files.writeString(sourceRoot.resolve(fileName), String.join("\n", lines) + "\n");
    }

    private ProgramArtifact compile(String fileName) throws Exception {
        CompilerOptions options = new CompilerOptions(List.of(new SourceRoot(sourceRoot.toString(), null)));
        return new Compiler().compile(fileName, ENV, options);
    }
}
