package org.evochora.compiler.features.importdir;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceFile;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.compiler.api.TokenKind;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A module declares a value it needs with {@code .REQUIRE VALUE}, and the importer supplies it
 * with {@code USING VALUE}, or the default holds. The value is a constant of the module's
 * placement: each placement has its own, the token map marks its uses as a constant, and the
 * artifact lists it under the placement's path. The main program, which nothing imports, takes
 * its values from the configuration through {@link CompilerOptions#values()}.
 */
@Tag("integration")
class ModuleValuesTest {

    private static final EnvironmentProperties ENV = new EnvironmentProperties(new int[]{100, 100}, true);

    @TempDir
    Path sourceRoot;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    /**
     * A module placed twice with different values: one placement keeps the default and binds a
     * literal, the other binds both, one of them from a constant of the importer.
     */
    @Test
    void eachPlacementCarriesItsOwnValues() throws Exception {
        writeExample();

        ProgramArtifact artifact = compile();

        assertThat(artifact.constantValues()).contains(
                Map.entry("WALK.STEPS", "DATA:10"), Map.entry("WALK.SPEED", "DATA:3"),
                Map.entry("SLOW.STEPS", "DATA:4"), Map.entry("SLOW.SPEED", "DATA:1"));
        TokenInfo use = artifact.tokenMap().get(new SourceInfo(resolved("walk.evo"), 5, 11, "WALK", 0));
        assertThat(use.tokenType()).isEqualTo(TokenKind.CONSTANT);
        assertThat(use.qualifiedName()).isEqualTo("WALK.STEPS");
    }

    /**
     * The value slot of a requirement names the requirement itself; following it leads to the
     * bound value or the default, not back to the name, so no circle is reported.
     */
    @Test
    void theSelfReferenceOfTheValueSlotIsNoCircle() throws Exception {
        writeExample();

        assertThatCode(this::compile).doesNotThrowAnyException();
        assertThat(compile().constantValues()).contains(
                Map.entry("WALK.STEPS", "DATA:10"), Map.entry("WALK.SPEED", "DATA:3"),
                Map.entry("SLOW.STEPS", "DATA:4"), Map.entry("SLOW.SPEED", "DATA:1"));
    }

    @Test
    void anExportedValueIsReachedByItsPath() throws Exception {
        write("walk.evo",
                "EXPORT .REQUIRE VALUE STEPS DATA:10",
                "  NOP");
        write("main.evo",
                ".IMPORT \"walk.evo\" AS WALK",
                "START:",
                "  SETI %DR0 WALK.STEPS");

        assertThatCode(this::compile).doesNotThrowAnyException();
    }

    @Test
    void anUnexportedValueIsNotReachedFromOutside() throws Exception {
        write("walk.evo",
                ".REQUIRE VALUE STEPS DATA:10",
                "  NOP");
        write("main.evo",
                ".IMPORT \"walk.evo\" AS WALK",
                "START:",
                "  SETI %DR0 WALK.STEPS");

        assertThatThrownBy(this::compile)
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("'STEPS' of WALK is not marked EXPORT.")
                .hasMessageContaining("main.evo:3");
    }

    /**
     * A value bound to a label is listed under the label's qualified name, as a constant that
     * names a label is.
     */
    @Test
    void aValueBoundToALabelIsListedLikeAConstantNamingALabel() throws Exception {
        write("walk.evo",
                ".REQUIRE VALUE TARGET",
                ".CONST LOCAL TARGET",
                "  JMPI TARGET",
                "  JMPI LOCAL");
        write("main.evo",
                ".IMPORT \"walk.evo\" AS WALK USING VALUE TARGET HOME",
                "HOME:",
                "  NOP");

        ProgramArtifact artifact = compile();

        assertThat(artifact.constantValues())
                .containsEntry("WALK.TARGET", "HOME")
                .containsEntry("WALK.LOCAL", "HOME");
    }

    /**
     * A value is no flag: {@code .IFDEF} on it leaves the block out, silently.
     */
    @Test
    void aValueIsNoFlag() throws Exception {
        write("walk.evo",
                ".REQUIRE VALUE STEPS DATA:10",
                "  NOP",
                ".IFDEF STEPS",
                "  NOP",
                ".ENDDEF",
                "  NOP");
        write("main.evo",
                ".IMPORT \"walk.evo\" AS WALK USING VALUE STEPS DATA:2",
                "START:",
                "  NOP");

        ProgramArtifact artifact = compile();

        var lines = artifact.sourceLineToInstructions().get("WALK").get(resolved("walk.evo")).get(0);
        assertThat(lines).containsKeys(2, 6).doesNotContainKey(4);
    }

    @Test
    void aBindingOfAValueTheModuleDoesNotRequireIsReported() throws Exception {
        write("walk.evo",
                ".REQUIRE VALUE STEPS DATA:10",
                "  NOP");
        write("main.evo",
                "START:",
                ".IMPORT \"walk.evo\" AS WALK USING VALUE NOPE DATA:1",
                "  NOP");

        assertThatThrownBy(this::compile)
                .isInstanceOf(CompilationException.class)
                .satisfies(e -> assertThat(e.getMessage().lines().toList()).containsExactly(
                        "[ERROR] " + resolved("main.evo")
                                + ":2: USING VALUE 'NOPE' does not match any .REQUIRE VALUE in the imported module."));
    }

    @Test
    void aValueWithoutDefaultThatTheImportDoesNotBindIsReported() throws Exception {
        write("walk.evo",
                ".REQUIRE VALUE SPEED",
                "  NOP");
        write("main.evo",
                "START:",
                ".IMPORT \"walk.evo\" AS WALK",
                "  NOP");

        assertThatThrownBy(this::compile)
                .isInstanceOf(CompilationException.class)
                .satisfies(e -> assertThat(e.getMessage().lines().toList()).containsExactly(
                        "[ERROR] " + resolved("main.evo")
                                + ":2: Cannot import WALK: it requires the value 'SPEED'; add USING VALUE SPEED <value> to the import."));
    }

    @Test
    void aValueBoundTwiceIsReported() throws Exception {
        write("walk.evo",
                ".REQUIRE VALUE SPEED",
                "  NOP");
        write("main.evo",
                "START:",
                ".IMPORT \"walk.evo\" AS WALK USING VALUE SPEED DATA:1 VALUE SPEED DATA:2",
                "  NOP");

        assertThatThrownBy(this::compile)
                .isInstanceOf(CompilationException.class)
                .satisfies(e -> assertThat(e.getMessage().lines().toList()).containsExactly(
                        "[ERROR] " + resolved("main.evo")
                                + ":2: Cannot bind 'SPEED' twice in the USING clauses of one import."));
    }

    @Test
    void aBoundNameTheImporterHasNotDefinedIsReported() throws Exception {
        write("walk.evo",
                ".REQUIRE VALUE SPEED",
                "  SETI %DR0 SPEED");
        write("main.evo",
                ".CONST SLOW_SPEED DATA:1",
                ".IMPORT \"walk.evo\" AS WALK USING VALUE SPEED SLOW_SPEDD",
                "START:",
                "  NOP");

        assertThatThrownBy(this::compile)
                .isInstanceOf(CompilationException.class)
                .satisfies(e -> assertThat(e.getMessage().lines().toList()).containsExactly(
                        "[ERROR] " + resolved("main.evo")
                                + ":2: Cannot bind VALUE SPEED from 'SLOW_SPEDD': the name is not defined."));
    }

    @Test
    void aDefaultTheModuleHasNotDefinedIsReported() throws Exception {
        write("walk.evo",
                ".REQUIRE VALUE STEPS NOPE",
                "  SETI %DR0 STEPS");
        write("main.evo",
                ".IMPORT \"walk.evo\" AS WALK",
                "START:",
                "  NOP");

        assertThatThrownBy(this::compile)
                .isInstanceOf(CompilationException.class)
                .satisfies(e -> assertThat(e.getMessage().lines().toList()).containsExactly(
                        "[ERROR] " + resolved("walk.evo")
                                + ":1: Cannot require value 'STEPS' from 'NOPE': the name is not defined."));
    }

    @Test
    void anExportedModuleRequirementIsReportedByItsHandler() throws Exception {
        write("math.evo",
                "EXPORT .PROC CLAMP",
                "  RET",
                ".ENDPROC");
        write("walk.evo",
                "  NOP",
                "EXPORT .REQUIRE MODULE \"math.evo\" AS CALC",
                "  NOP");
        write("main.evo",
                ".IMPORT \"math.evo\" AS MATH",
                ".IMPORT \"walk.evo\" AS WALK USING MODULE MATH AS CALC",
                "START:",
                "  NOP");

        assertThatThrownBy(this::compile)
                .isInstanceOf(CompilationException.class)
                .satisfies(e -> assertThat(e.getMessage().lines().toList()).containsExactly(
                        "[ERROR] " + resolved("walk.evo")
                                + ":2: EXPORT is not supported before '.REQUIRE MODULE': the importer supplies the module and already knows it under its own name."));
    }

    @Test
    void aValueRequirementUnderATakenNameIsReported() throws Exception {
        write("walk.evo",
                ".CONST STEPS DATA:1",
                ".REQUIRE VALUE STEPS DATA:10",
                "  NOP");
        write("main.evo",
                ".IMPORT \"walk.evo\" AS WALK",
                "START:",
                "  NOP");

        assertThatThrownBy(this::compile)
                .isInstanceOf(CompilationException.class)
                .satisfies(e -> assertThat(e.getMessage().lines().toList()).containsExactly(
                        "[ERROR] " + resolved("walk.evo")
                                + ":2: Cannot require value 'STEPS': the name is already used at " + resolved("walk.evo") + ":1."));
    }

    @Test
    void aValueOfTheMainProgramWithoutDefaultNamesTheConfiguration() throws Exception {
        write("main.evo",
                "START:",
                ".REQUIRE VALUE X",
                "  SETI %DR0 X");

        assertThatThrownBy(this::compile)
                .isInstanceOf(CompilationException.class)
                .satisfies(e -> assertThat(e.getMessage().lines().toList()).containsExactly(
                        "[ERROR] " + resolved("main.evo")
                                + ":2: The value 'X' has no default and the configuration sets none; set it with values { X = \"<value>\" } in the organism's entry or --value X=<value>."));
    }

    @Test
    void aValueRequirementInsideAProcedureIsReported() throws Exception {
        write("main.evo",
                "START:",
                "  CALL P",
                ".PROC P",
                "  .REQUIRE VALUE X DATA:1",
                "  RET",
                ".ENDPROC");

        assertThatThrownBy(this::compile)
                .isInstanceOf(CompilationException.class)
                .satisfies(e -> assertThat(e.getMessage().lines().toList()).containsExactly(
                        "[ERROR] " + resolved("main.evo")
                                + ":4: .REQUIRE may stand only at the module level."));
    }

    @Test
    void aValueRequirementStandsAloneOnItsLine() throws Exception {
        write("main.evo",
                ".REQUIRE VALUE STEPS DATA:1 EXTRA",
                "START:",
                "  SETI %DR0 STEPS");

        assertThatThrownBy(this::compile)
                .isInstanceOf(CompilationException.class)
                .satisfies(e -> assertThat(e.getMessage().lines().toList()).containsExactly(
                        "[ERROR] " + resolved("main.evo")
                                + ":1: .REQUIRE must stand alone on its line; found 'EXTRA' after the value and its default."));
    }

    @Test
    void aConfiguredValueTakesThePlaceOfTheMainProgramsDefault() throws Exception {
        write("main.evo",
                ".REQUIRE VALUE STEPS DATA:10",
                "START:",
                "  SETI %DR0 STEPS");

        assertThat(compile(Map.of("STEPS", "DATA:20")).constantValues()).containsEntry("STEPS", "DATA:20");
    }

    @Test
    void withoutAConfiguredValueTheMainProgramsDefaultHolds() throws Exception {
        write("main.evo",
                ".REQUIRE VALUE STEPS DATA:10",
                "START:",
                "  SETI %DR0 STEPS");

        assertThat(compile(Map.of()).constantValues()).containsEntry("STEPS", "DATA:10");
    }

    /**
     * A value requirement without default is bound by the configuration; the name of the
     * configured value is case-insensitive.
     */
    @Test
    void aConfiguredValueBindsAValueWithoutDefault() throws Exception {
        write("main.evo",
                "START:",
                ".REQUIRE VALUE STEPS",
                "  SETI %DR0 STEPS");

        assertThat(compile(Map.of("steps", "DATA:20")).constantValues()).containsEntry("STEPS", "DATA:20");
    }

    @Test
    void aConfiguredValueThatIsANameIsLookedUpInTheMainProgram() throws Exception {
        write("main.evo",
                ".CONST LIMIT DATA:7",
                ".REQUIRE VALUE STEPS DATA:10",
                "START:",
                "  SETI %DR0 STEPS");

        ProgramArtifact artifact = compile(Map.of("STEPS", "LIMIT"));

        assertThat(artifact.constantValues()).containsEntry("STEPS", "DATA:7");
        assertThat(artifact.sources()).extracting(SourceFile::path)
                .noneMatch(path -> path.startsWith("<value"));
        assertThat(artifact.sources()).extracting(SourceFile::resolvedPath)
                .noneMatch(path -> path.startsWith("<value"));
    }

    /**
     * A configured value that is a name is a use of that name in the main program, and reported
     * there when the main program has not defined it.
     */
    @Test
    void aConfiguredNameTheMainProgramHasNotDefinedIsReportedAtTheRequirement() throws Exception {
        write("main.evo",
                ".REQUIRE VALUE STEPS DATA:10",
                "START:",
                "  SETI %DR0 STEPS");

        assertThatThrownBy(() -> compile(Map.of("STEPS", "NOPE")))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Cannot require value 'STEPS' from 'NOPE': the name is not defined.")
                .hasMessageContaining("main.evo:1");
    }

    /**
     * One configuration serves several programs, so a value no requirement names is ignored.
     */
    @Test
    void aConfiguredValueNothingRequiresIsNotReported() throws Exception {
        write("main.evo",
                "START:",
                "  NOP");

        assertThatCode(() -> compile(Map.of("STEPS", "DATA:20"))).doesNotThrowAnyException();
    }

    @Test
    void aConfiguredValueOfTwoOperandsIsReported() throws Exception {
        assertConfiguredValueIsNoOperand("DATA:1 DATA:2");
    }

    @Test
    void aConfiguredValueWithASemicolonIsReported() throws Exception {
        assertConfiguredValueIsNoOperand("DATA:1; NOP");
    }

    @Test
    void aConfiguredValueTheLexerRejectsIsReported() throws Exception {
        assertConfiguredValueIsNoOperand("DATA:2\"0");
    }

    /**
     * Only the main program's placement reads the configuration; a module's requirement of the
     * same name keeps what its importer binds, or its default.
     */
    @Test
    void aConfiguredValueIsNotTakenByAModule() throws Exception {
        write("walk.evo",
                ".REQUIRE VALUE STEPS DATA:10",
                "  NOP");
        write("main.evo",
                ".IMPORT \"walk.evo\" AS WALK",
                "START:",
                "  NOP");

        assertThat(compile(Map.of("STEPS", "DATA:20")).constantValues()).containsEntry("WALK.STEPS", "DATA:10");
    }

    private void assertConfiguredValueIsNoOperand(String text) throws Exception {
        write("main.evo",
                "START:",
                ".REQUIRE VALUE STEPS DATA:10",
                "  SETI %DR0 STEPS");

        assertThatThrownBy(() -> compile(Map.of("STEPS", text)))
                .isInstanceOf(CompilationException.class)
                .satisfies(e -> assertThat(e.getMessage().lines().toList()).containsExactly(
                        "[ERROR] " + resolved("main.evo")
                                + ":2: Configured value 'STEPS' is not an operand; a value is written as a .CONST takes it: a literal, a vector or a name."));
    }

    /**
     * The example of the proposal: walk.evo needs a module and two values, main.evo places it
     * twice.
     */
    private void writeExample() throws Exception {
        write("math.evo",
                "EXPORT .PROC CLAMP REF X VAL LO HI",
                "  LTR X LO",
                "  JMPI TO_LO",
                "  LTR HI X",
                "  JMPI DONE",
                "  SETR X HI",
                "  RET",
                "TO_LO:",
                "  SETR X LO",
                "DONE:",
                "  RET",
                ".ENDPROC");
        write("walk.evo",
                ".REQUIRE MODULE \"math.evo\" AS CALC",
                ".REQUIRE VALUE STEPS DATA:10",
                ".REQUIRE VALUE SPEED",
                ".REG %I %DR0",
                "  GETI %I STEPS",
                "  CALL CALC.CLAMP REF %I VAL DATA:0 SPEED");
        write("main.evo",
                ".CONST SLOW_SPEED DATA:1",
                ".IMPORT \"math.evo\" AS MATH",
                ".IMPORT \"walk.evo\" AS WALK USING MODULE MATH AS CALC VALUE SPEED DATA:3",
                ".IMPORT \"walk.evo\" AS SLOW USING MODULE MATH AS CALC VALUE STEPS DATA:4 VALUE SPEED SLOW_SPEED",
                "START:",
                "  NOP");
    }

    private String resolved(String fileName) {
        return sourceRoot.resolve(fileName).toString().replace('\\', '/');
    }

    private void write(String fileName, String... lines) throws Exception {
        Files.writeString(sourceRoot.resolve(fileName), String.join("\n", lines) + "\n");
    }

    private ProgramArtifact compile() throws Exception {
        CompilerOptions options = new CompilerOptions(List.of(new SourceRoot(sourceRoot.toString(), null)));
        return new Compiler().compile("main.evo", ENV, options);
    }

    private ProgramArtifact compile(Map<String, String> values) throws Exception {
        CompilerOptions options = new CompilerOptions(List.of(new SourceRoot(sourceRoot.toString(), null)), Map.of(), values);
        return new Compiler().compile("main.evo", ENV, options);
    }
}
