package org.evochora.compiler.diagnostics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

/**
 * What the compiler tells a programmer when a program is wrong.
 * <p>
 * Each test writes a small program to disk, compiles it through the whole pipeline and checks
 * the message of the resulting {@link CompilationException}: that it names the mistake, and
 * that it names the file and the line the programmer has to look at. Tests here concern the
 * message, not the phase that produces it.
 */
@Tag("integration")
class CompilerDiagnosticsTest {

    private static final EnvironmentProperties ENV = new EnvironmentProperties(new int[]{100, 100}, true);

    @TempDir
    Path sourceRoot;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void referenceToLabelThatIsNotExportedNamesTheSymbolAndTheLine() throws Exception {
        write("lib.evo",
                "PRIVATE:",
                "  NOP");
        write("main.evo",
                ".IMPORT \"lib.evo\" AS LIB",
                "START:",
                "  JMPI LIB.PRIVATE");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:3: Cannot use 'LIB.PRIVATE' as an argument: 'PRIVATE' of LIB is not marked EXPORT.");
    }

    @Test
    void macroInvokedWithWrongArgumentCountNamesTheMacroAndTheLineOfTheInvocation() throws Exception {
        write("main.evo",
                ".MACRO INC REG",
                "  ADDI REG DATA:1",
                ".ENDMACRO",
                "START:",
                "  INC %DR0 %DR1");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:5: Macro 'INC' expects 1 arguments, but got 2");
    }

    @Test
    void secondDefinitionOfAMacroNamesBothPlaces() throws Exception {
        write("macros.evo",
                ".MACRO INC REG",
                "  ADDI REG DATA:1",
                ".ENDMACRO");
        write("main.evo",
                ".SOURCE \"macros.evo\"",
                ".MACRO INC REG",
                "  SUBI REG DATA:1",
                ".ENDMACRO",
                "START:",
                "  INC %DR0");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageFindingMatch("main\\.evo:2: Cannot define macro 'INC' differently at \\S*main\\.evo:2: first defined at \\S*macros\\.evo:1 with another body\\.");
    }

    @Test
    void importWithoutAliasNamesTheMissingAsAndTheLine() throws Exception {
        write("lib.evo",
                "  NOP");
        write("main.evo",
                "# A comment line.",
                ".IMPORT \"lib.evo\"",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:2: Expected AS after .IMPORT path.");
    }

    @Test
    void secondImportOfTheSameFileWithoutAliasNamesTheMissingAsAndItsLine() throws Exception {
        write("lib.evo",
                "  NOP");
        write("main.evo",
                ".IMPORT \"lib.evo\" AS LIB",
                ".IMPORT \"lib.evo\"",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:2: Expected AS after .IMPORT path.");
    }

    @Test
    void importOfAMissingFileNamesTheFileAndTheLine() throws Exception {
        write("main.evo",
                "# A comment line.",
                ".IMPORT \"nowhere.evo\" AS X",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:2: Module file not found: nowhere.evo");
    }

    @Test
    void sourceOfAMissingFileNamesTheFileAndTheLine() throws Exception {
        write("main.evo",
                "# A comment line.",
                ".SOURCE \"nowhere.evo\"",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:2: Source file not found: nowhere.evo");
    }

    /**
     * A dependency directive shares a line with other statements like any statement, each behind
     * a statement end: the lexer phase reads the same statement lines as the preprocessor, so
     * nothing requires it to stand first or alone. A label before it is a word before it, not a
     * statement end.
     */
    @Test
    void aDependencyDirectiveMayShareItsLineWithOtherStatements() throws Exception {
        write("lib.evo",
                "  NOP");
        write("x.evo",
                "  NOP");
        write("main.evo",
                ".IMPORT \"lib.evo\" AS LIB; NOP",
                "NOP; .SOURCE \"x.evo\"; NOP",
                "NOP; .REQUIRE VALUE STEPS DATA:1; SETI %DR0 STEPS",
                "START:",
                "  NOP");
        assertThatCode(() -> compile("main.evo")).doesNotThrowAnyException();
    }

    /**
     * A path the file system rejects, here one with a NUL character, is reported at the directive
     * like an unknown prefix; the reason behind the colon is the file system's and differs
     * between operating systems.
     */
    @Test
    void aPathTheFileSystemRejectsIsReportedAtTheDirective() throws Exception {
        write("main.evo",
                ".SOURCE \"lib\0.evo\"",
                "START:",
                "  NOP");
        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:1: Path 'lib\0.evo' cannot be resolved: ");
    }

    @Test
    void aWordBeforeADependencyDirectiveWithoutAStatementEndIsReported() throws Exception {
        write("lib.evo",
                "  NOP");
        write("x.evo",
                "  NOP");
        write("main.evo",
                "NOP .IMPORT \"lib.evo\" AS LIB",
                "NOP .SOURCE \"x.evo\"",
                "START:",
                "  NOP");
        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:1: Unexpected '.IMPORT' after 'NOP'.")
                .hasMessageContaining("main.evo:2: Unexpected '.SOURCE' after 'NOP'.");
    }

    /**
     * The module an import inlines ends with a marker the user never wrote; a statement that
     * follows the import on its line still begins after the import's statement end, in the
     * preprocessor as in the file, so a flag set there is set in both phases.
     */
    @Test
    void aDirectiveAfterAnImportOnTheSameLineBeginsAStatement() throws Exception {
        write("a.evo",
                "  NOP");
        write("b.evo",
                "  NOP");
        write("other.evo",
                "  NOP");
        write("main.evo",
                ".IMPORT \"a.evo\" AS A; .IMPORT \"b.evo\" AS B; .DEFINE X",
                ".IFNDEF X",
                "  .IMPORT \"other.evo\" AS O",
                ".ENDDEF",
                "START:",
                "  NOP");
        assertThatCode(() -> compile("main.evo")).doesNotThrowAnyException();
    }

    /**
     * The lexer phase follows the same statement rule as the preprocessor: a directive after a
     * word without a statement end loads no file and sets no flag there, so the scan neither
     * reports a missing file the preprocessor never asks for, nor takes a branch the preprocessor
     * does not take.
     */
    @Test
    void aWordBeforeADependencyDirectiveKeepsTheScanFromFollowingIt() throws Exception {
        write("flags.evo",
                ".DEFINE B");
        write("lib.evo",
                "L:",
                "  NOP");
        write("main.evo",
                "NOP .DEFINE A",
                ".IFDEF A",
                ".IMPORT \"missing.evo\" AS MISSING",
                ".ENDDEF",
                "NOP .SOURCE \"flags.evo\"",
                ".IFNDEF B",
                ".IMPORT \"lib.evo\" AS LIB",
                ".ENDDEF",
                "NOP .IMPORT \"missing.evo\" AS GONE",
                "START:",
                "  NOP");
        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:1: Unexpected '.DEFINE' after 'NOP'.")
                .hasMessageContaining("main.evo:5: Unexpected '.SOURCE' after 'NOP'.")
                .hasMessageContaining("main.evo:9: Unexpected '.IMPORT' after 'NOP'.")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("not found", "Internal error"));
    }

    @Test
    void aRepeatCountOutOfRangeIsReportedOnce() throws Exception {
        write("main.evo",
                "START:",
                ".REPEAT 3000000000",
                "  NOP",
                ".ENDREPEAT",
                "  NOP");
        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .satisfies(e -> assertThat(e.getMessage().lines().toList()).containsExactly(
                        "[ERROR] " + resolved("main.evo") + ":2: Value 3000000000 out of range for a count."));
    }

    @Test
    void anUntypedPlaceLiteralIsReportedOnce() throws Exception {
        write("main.evo",
                ".PLACE 5 1|2",
                "START:",
                "  NOP");
        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .satisfies(e -> assertThat(e.getMessage().lines().toList()).containsExactly(
                        "[ERROR] " + resolved("main.evo") + ":1: Expected a typed literal (e.g. DATA:5) for .PLACE."));
    }

    @Test
    void aSourceWithATokenAfterItsPathIsRejected() throws Exception {
        write("x.evo",
                "  NOP");
        write("main.evo",
                "START:",
                ".SOURCE \"x.evo\"^2",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:2: Unexpected '^' after the path of .SOURCE.")
                .hasMessageNotContaining("must be a literal");
    }

    @Test
    void anImportWithATokenAfterItsClausesIsRejected() throws Exception {
        write("lib.evo",
                "  NOP");
        write("main.evo",
                "START:",
                ".IMPORT \"lib.evo\" AS LIB^2",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:2: Unexpected '^' after the alias and its USING clauses of .IMPORT.")
                .hasMessageNotContaining("must be a literal")
                .hasMessageNotContaining("Unexpected token");
    }

    @Test
    void anExportedRequireGetsTheParsersMessageAlone() throws Exception {
        write("d.evo",
                "EXPORT .PROC WORK",
                "  RET",
                ".ENDPROC");
        write("main.evo",
                "EXPORT .REQUIRE MODULE \"d.evo\" AS D",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .satisfies(e -> assertThat(e.getMessage().lines().toList()).containsExactly(
                        "[ERROR] " + resolved("main.evo")
                                + ":1: EXPORT is not supported before '.REQUIRE MODULE'."));
    }

    @Test
    void anExportedImportIsStillAccepted() throws Exception {
        write("lib.evo",
                "EXPORT .PROC WORK",
                "  RET",
                ".ENDPROC");
        write("main.evo",
                "EXPORT .IMPORT \"lib.evo\" AS LIB",
                "START:",
                "  CALL LIB.WORK");

        assertThatCode(() -> compile("main.evo")).doesNotThrowAnyException();
    }

    @Test
    void aRequireWithATokenAfterItsAliasIsRejectedByTheLineRule() throws Exception {
        write("dep.evo",
                "D:",
                "  NOP");
        write("lib.evo",
                "# A comment line.",
                ".REQUIRE MODULE \"dep.evo\" AS DEP EXTRA",
                "L:",
                "  NOP");
        write("main.evo",
                ".IMPORT \"dep.evo\" AS DEP",
                ".IMPORT \"lib.evo\" AS LIB USING MODULE DEP AS DEP",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("lib.evo:2: Unexpected 'EXTRA' after the alias of .REQUIRE.");
    }

    @Test
    void aRequireWithoutItsKindWordNamesTheMissingModuleAndTheLine() throws Exception {
        write("main.evo",
                "START:",
                ".REQUIRE \"d.evo\" AS D",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .satisfies(e -> assertThat(e.getMessage().lines().toList()).containsExactly(
                        "[ERROR] " + resolved("main.evo")
                                + ":2: Expected MODULE or VALUE after .REQUIRE; found '\"d.evo\"'."));
    }

    @Test
    void importInsideAMacroBodyIsRejectedAtTheDefinition() throws Exception {
        write("lib.evo",
                "  NOP");
        write("main.evo",
                ".MACRO USE P",
                "  .IMPORT P AS X",
                ".ENDMACRO",
                "USE \"lib.evo\"",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageFindingMatch("main\\.evo:2: \\.IMPORT may not stand inside a \\.MACRO body; the body opened at \\S*main\\.evo:1");
    }

    @Test
    void aProcedureInsideAProcedureIsReported() throws Exception {
        write("main.evo",
                ".PROC OUTER",
                "  .PROC INNER",
                "    RET",
                "  .ENDPROC",
                "  RET",
                ".ENDPROC");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:2: .PROC may stand only at the module level.");
    }

    @Test
    void valueRequirementInsideASourcedFileNamesTheFileAndTheLine() throws Exception {
        write("lib.evo",
                "# text that is sourced, not a module",
                "  NOP",
                ".REQUIRE VALUE STEPS DATA:1");
        write("main.evo",
                ".SOURCE \"lib.evo\"",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("lib.evo:3: .REQUIRE not allowed in a .SOURCE file");
    }

    @Test
    void exportedValueRequirementInsideASourcedFileIsRejectedToo() throws Exception {
        write("lib.evo",
                "  NOP",
                "EXPORT .REQUIRE VALUE STEPS DATA:1");
        write("main.evo",
                ".SOURCE \"lib.evo\"",
                "START:",
                "  SETI %DR0 STEPS");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("lib.evo:2: .REQUIRE not allowed in a .SOURCE file");
    }

    @Test
    void importInsideASourcedFileNamesTheFileAndTheLineOfTheImport() throws Exception {
        write("x.evo",
                "  NOP");
        write("lib.evo",
                "# text that is sourced, not a module",
                "  NOP",
                ".IMPORT \"x.evo\" AS X");
        write("main.evo",
                ".SOURCE \"lib.evo\"",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("lib.evo:3: .IMPORT not allowed in a .SOURCE file");
    }

    @Test
    void repeatWithoutACountNamesTheDirectiveAndTheLine() throws Exception {
        write("main.evo",
                "START:",
                "  NOP",
                ".REPEAT",
                "  NOP",
                ".ENDREPEAT");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:3: Expected repeat count after .REPEAT");
    }

    @Test
    void registerIndexBeyondTheBankIsRejectedWhereTheRegisterIsWritten() throws Exception {
        write("main.evo",
                "START:",
                "  SETI %DR999 DATA:1");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:2: Register '%DR999' is out of bounds. Valid range: %DR0-%DR7.");
    }

    @Test
    void registerIndexBeyondTheBankIsRejectedInARegDirectiveToo() throws Exception {
        write("main.evo",
                "# A comment line.",
                ".REG %X %DR999",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:2: Register '%DR999' is out of bounds. Valid range: %DR0-%DR7.");
    }

    @Test
    void aParameterRegisterNamedInSourceIsRejectedWithTheWayOut() throws Exception {
        write("main.evo",
                "START:",
                "  SETI %FDR0 DATA:1");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:2: Register '%FDR0' is reserved for procedure parameters. Use the parameter's name instead.");
    }

    @Test
    void aProcedureScopedRegisterAliasedOutsideAProcedureNamesTheScope() throws Exception {
        write("main.evo",
                "# A comment line.",
                ".REG %X %PDR0",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:2: Register '%PDR0' is only available inside a procedure.");
    }

    @Test
    void twoPlacementsOnTheSameCellNameBothLines() throws Exception {
        write("main.evo",
                ".PLACE DATA:5 20|20",
                ".PLACE DATA:6 20|20",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Coordinate [20, 20] is already occupied by a .PLACE at")
                .hasMessageContaining("main.evo:1")
                .hasMessageContaining("main.evo:2");
    }

    @Test
    void codeLaidOverAPlacementNamesBothLines() throws Exception {
        write("main.evo",
                ".PLACE DATA:5 0|0",
                ".ORG 0|0",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Coordinate [0, 0] is already occupied by a .PLACE at")
                .hasMessageContaining("main.evo:1")
                .hasMessageContaining("main.evo:3");
    }

    @Test
    void codeLaidOverALabelNamesTheLabel() throws Exception {
        write("main.evo",
                ".ORG 0|0",
                "START:",
                "  NOP",
                ".ORG 0|0",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Coordinate [0, 0] is already occupied by a label at")
                .hasMessageContaining("main.evo:2")
                .hasMessageContaining("main.evo:5");
    }

    @Test
    void codeLaidOverCodeNamesBothLines() throws Exception {
        write("main.evo",
                ".ORG 0|0",
                "START:",
                "  NOP",
                ".ORG 1|0",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Coordinate [1, 0] is already occupied by an instruction at")
                .hasMessageContaining("main.evo:3")
                .hasMessageContaining("main.evo:5");
    }

    /**
     * On a toroidal world a coordinate a world size from another lands on its cell, wherever the
     * program starts: code laid over code across the wrap is a conflict like code laid over code.
     */
    @Test
    void codeLaidOverCodeAcrossTheWrapNamesBothLines() throws Exception {
        write("main.evo",
                ".ORG 0|0",
                "START:",
                "  NOP",
                ".ORG 100|0",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Coordinate [100, 0] lands on the cell of [0, 0], which is already occupied by a label at")
                .hasMessageFindingMatch("by a label at \\S*main\\.evo:2\\. Cannot place new item at \\S*main\\.evo:5\\.");
    }

    @Test
    void aPlacementLaidOverItselfAcrossTheWrapNamesItsLine() throws Exception {
        write("main.evo",
                ".PLACE DATA:5 0:2:100|5",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Coordinate [100, 5] lands on the cell of [0, 5], which is already occupied by a .PLACE at")
                .hasMessageFindingMatch("by a \\.PLACE at \\S*main\\.evo:1\\. Cannot place new item at \\S*main\\.evo:1\\.");
    }

    /**
     * On a bounded world two cells a world size or more apart along an axis cannot both lie in
     * it, wherever the program starts; cells one less apart can.
     */
    @Test
    void cellsTooFarApartForABoundedWorldNameBothLines() throws Exception {
        EnvironmentProperties bounded = new EnvironmentProperties(new int[]{100, 100}, false);
        write("main.evo",
                ".PLACE DATA:5 -1|0",
                "START:",
                "  NOP",
                ".ORG 99|0",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo", bounded))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("World size exceeded: Coordinate [99, 0] lies 100 cells from [-1, 0], occupied by a .PLACE at")
                .hasMessageFindingMatch("by a \\.PLACE at \\S*main\\.evo:1, along axis 0; "
                        + "the bounded world has 100 cells along it\\. Cannot place new item at \\S*main\\.evo:5\\.");

        write("main.evo",
                ".PLACE DATA:5 -1|0",
                "START:",
                "  NOP",
                ".ORG 98|0",
                "  NOP");
        compile("main.evo", bounded);
    }

    @Test
    void aMacroOfAnImportedModuleIsNotAMacroInTheImportingFile() throws Exception {
        write("lib.evo",
                ".MACRO INC REG",
                "  ADDI REG DATA:1",
                ".ENDMACRO",
                "HELPER:",
                "  NOP");
        write("main.evo",
                ".IMPORT \"lib.evo\" AS LIB",
                "START:",
                "  INC %DR0");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:3: Expected instruction or directive, but got 'INC'.");
    }

    @Test
    void aMacroOfTheImportingFileIsNotAMacroInTheImportedModule() throws Exception {
        write("lib.evo",
                "HELPER:",
                "  INC %DR0");
        write("main.evo",
                ".MACRO INC REG",
                "  ADDI REG DATA:1",
                ".ENDMACRO",
                ".IMPORT \"lib.evo\" AS LIB",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("lib.evo:2: Expected instruction or directive, but got 'INC'.");
    }

    @Test
    void twoModulesMayEachDefineAMacroOfTheSameName() throws Exception {
        write("a.evo",
                ".MACRO INC REG",
                "  ADDI REG DATA:1",
                ".ENDMACRO",
                "A:",
                "  INC %DR0");
        write("b.evo",
                ".MACRO INC REG",
                "  ADDI REG DATA:2",
                ".ENDMACRO",
                "B:",
                "  INC %DR0");
        write("main.evo",
                ".IMPORT \"a.evo\" AS A",
                ".IMPORT \"b.evo\" AS B",
                "START:",
                "  NOP");

        assertThatCode(() -> compile("main.evo")).doesNotThrowAnyException();
    }

    @Test
    void aSourcedMacroFileServesEveryModuleThatSourcesIt() throws Exception {
        write("macros.evo",
                ".MACRO INC REG",
                "  ADDI REG DATA:1",
                ".ENDMACRO");
        write("a.evo",
                ".SOURCE \"macros.evo\"",
                "A:",
                "  INC %DR0");
        write("b.evo",
                ".SOURCE \"macros.evo\"",
                "B:",
                "  INC %DR0");
        write("main.evo",
                ".IMPORT \"a.evo\" AS A",
                ".IMPORT \"b.evo\" AS B",
                "START:",
                "  NOP");

        assertThatCode(() -> compile("main.evo")).doesNotThrowAnyException();
    }

    @Test
    void aConstantWhereARegisterIsExpectedNamesTheKindItHas() throws Exception {
        write("main.evo",
                ".CONST LIMIT DATA:5",
                "START:",
                "  SETI LIMIT DATA:1");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:3: Argument 1 for instruction 'SETI' has the wrong type. Expected REGISTER, but got LITERAL.");
    }

    @Test
    void aRegisterAliasWhereALiteralIsExpectedNamesTheKindItHas() throws Exception {
        write("main.evo",
                ".REG %X %DR1",
                "START:",
                "  SETI %DR0 %X");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:3: Argument 2 for instruction 'SETI' has the wrong type. Expected LITERAL, but got REGISTER.");
    }

    @Test
    void aModuleAliasIsNoInstructionArgument() throws Exception {
        write("lib.evo",
                "HELPER:",
                "  NOP");
        write("main.evo",
                ".IMPORT \"lib.evo\" AS LIB",
                "START:",
                "  SETI LIB DATA:1");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:3: 'LIB' is not a value, a register or a label and cannot be an instruction argument.");
    }

    @Test
    void aLocationAliasPassedByRefNamesTheKindItHas() throws Exception {
        write("main.evo",
                ".PROC P REF X",
                "  RET",
                ".ENDPROC",
                ".REG %POS %LR0",
                "START:",
                "  CALL P REF %POS");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:6: Invalid argument: '%POS' for REF is a location register; expected a data register.");
    }

    @Test
    void aModuleAliasIsNoCallArgument() throws Exception {
        write("lib.evo",
                "HELPER:",
                "  NOP");
        write("main.evo",
                ".IMPORT \"lib.evo\" AS LIB",
                ".PROC P REF X",
                "  RET",
                ".ENDPROC",
                "START:",
                "  CALL P REF LIB");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:6: Invalid argument: 'LIB' for REF is not a register or a label; expected a data register.");
    }

    @Test
    void aCircularDefinitionNamesTheCircle() throws Exception {
        write("main.evo",
                "# A comment line.",
                ".CONST A B",
                ".CONST B A",
                "START:",
                "  SETI %DR0 A");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:2: Cannot define constant 'A' from 'B': the name is not defined.")
                .hasMessageContaining("main.evo:2: Definition of A is circular: A -> B -> A.");
    }

    @Test
    void aConstantNamingALaterConstantIsReportedAtItsLine() throws Exception {
        write("main.evo",
                "# A comment line.",
                ".CONST A B",
                ".CONST B DATA:1",
                "START:",
                "  SETI %DR0 A");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:2: Cannot define constant 'A' from 'B': the name is not defined.");
    }

    @Test
    void aConstantNamingAMissingPathSaysWhereThePathStopped() throws Exception {
        write("lib.evo",
                "HELPER:",
                "  NOP");
        write("main.evo",
                ".IMPORT \"lib.evo\" AS LIB",
                ".CONST A LIB.NOPE",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:2: Cannot define constant 'A' from 'LIB.NOPE': 'LIB' has no member 'NOPE'.");
    }

    @Test
    void aUsingClauseWithoutAsNamesTheMissingAsAndTheLine() throws Exception {
        write("lib.evo",
                "HELPER:",
                "  NOP");
        write("main.evo",
                "# A comment line.",
                ".IMPORT \"lib.evo\" AS LIB USING MODULE X Y AS Z",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:2: Expected AS after USING source alias.")
                .extracting(Throwable::getMessage, as(STRING))
                .containsOnlyOnce("[ERROR]");
    }

    @Test
    void anUndefinedNameBeforeTheColonOfAPlacementNamesTheNameAndTheLine() throws Exception {
        write("main.evo",
                "START:",
                "  NOP",
                ".PLACE FOOD:1 5|5");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:3: 'FOOD' is not defined.");
    }

    @Test
    void aNumberBeforeTheColonOfAPlacementNamesTheOperandsAndTheLine() throws Exception {
        write("main.evo",
                "START:",
                "  NOP",
                ".CONST FOOD 5",
                ".PLACE FOOD:1 5|5");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:4: Invalid operands for ':': number and number.");
    }

    @Test
    void aPlacementWithMoreDimensionsThanTheWorldNamesBothCounts() throws Exception {
        write("main.evo",
                "START:",
                "  NOP",
                ".PLACE DATA:1 5|5|*");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:3: .PLACE uses 3 components, the world has 2 dimensions.");
    }

    @Test
    void aPlacementCoordinateWithMoreDimensionsThanTheWorldIsRejected() throws Exception {
        write("main.evo",
                "START:",
                "  NOP",
                ".PLACE DATA:1 5|5|7");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:3: Coordinate [5, 5, 7] has 3 components, the world has 2 dimensions.");
    }

    @Test
    void anOriginWithMoreDimensionsThanTheWorldIsRejectedAtTheDirective() throws Exception {
        write("main.evo",
                "# A comment line.",
                ".ORG 1|2|3",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:2: Origin [1, 2, 3] has 3 components, the world has 2 dimensions.");
    }

    @Test
    void constantsOfTheSameNameInTwoModulesAreNoCircle() throws Exception {
        write("lib.evo",
                ".CONST A DATA:5",
                "EXPORT .CONST B A",
                "HELPER:",
                "  NOP");
        write("main.evo",
                ".IMPORT \"lib.evo\" AS LIB",
                ".CONST A LIB.B",
                "START:",
                "  SETI %DR0 A");

        assertThatCode(() -> compile("main.evo")).doesNotThrowAnyException();
    }

    @Test
    void aSecondLabelOfTheSameNameNamesTheFirst() throws Exception {
        write("main.evo",
                "START:",
                "  NOP",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageFindingMatch("main\\.evo:3: Cannot define label 'START': the name is already used at \\S*main\\.evo:1\\.");
    }

    @Test
    void aSecondProcedureOfTheSameNameNamesTheFirst() throws Exception {
        write("main.evo",
                ".PROC STEP",
                "  RET",
                ".ENDPROC",
                ".PROC STEP",
                "  RET",
                ".ENDPROC",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageFindingMatch("main\\.evo:4: Cannot define procedure 'STEP': the name is already used at \\S*main\\.evo:1\\.");
    }

    @Test
    void aSecondConstantOfTheSameNameNamesTheFirst() throws Exception {
        write("main.evo",
                ".CONST MAX DATA:1",
                ".CONST MAX DATA:2",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageFindingMatch("main\\.evo:2: Cannot define constant 'MAX': the name is already used at \\S*main\\.evo:1\\.");
    }

    @Test
    void aSecondRegisterAliasOfTheSameNameNamesTheFirst() throws Exception {
        write("main.evo",
                ".REG %TMP %DR0",
                ".REG %TMP %DR1",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageFindingMatch("main\\.evo:2: Cannot define register alias '%TMP': the name is already used at \\S*main\\.evo:1\\.");
    }

    @Test
    void aParameterDeclaredTwiceNamesTheProcedure() throws Exception {
        write("main.evo",
                "# A comment line.",
                ".PROC STEP REF X VAL X",
                "  RET",
                ".ENDPROC",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:2: Cannot declare parameter 'X' twice in procedure 'STEP'.");
    }

    @Test
    void aSecondImportUnderTheSameAliasNamesTheFirst() throws Exception {
        write("a.evo", "A:", "  NOP");
        write("b.evo", "B:", "  NOP");
        write("main.evo",
                ".IMPORT \"a.evo\" AS LIB",
                ".IMPORT \"b.evo\" AS LIB",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageFindingMatch("main\\.evo:2: Cannot import as 'LIB': the name is already used at \\S*main\\.evo:1\\.");
    }

    @Test
    void aSecondRequirementUnderTheSameAliasNamesTheFirst() throws Exception {
        write("lib.evo",
                ".REQUIRE MODULE \"a.evo\" AS DEP",
                ".REQUIRE MODULE \"b.evo\" AS DEP",
                "L:",
                "  NOP");
        write("a.evo", "A:", "  NOP");
        write("b.evo", "B:", "  NOP");
        write("main.evo",
                ".IMPORT \"a.evo\" AS A",
                ".IMPORT \"lib.evo\" AS LIB USING MODULE A AS DEP",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageFindingMatch("lib\\.evo:2: Cannot require as 'DEP': the name is already used at \\S*lib\\.evo:1\\.");
    }

    @Test
    void aUsingAliasBoundTwiceInOneImportIsRejected() throws Exception {
        write("lib.evo",
                ".REQUIRE MODULE \"a.evo\" AS DEP",
                "L:",
                "  NOP");
        write("a.evo", "A:", "  NOP");
        write("main.evo",
                ".IMPORT \"a.evo\" AS A",
                ".IMPORT \"lib.evo\" AS LIB USING MODULE A AS DEP MODULE A AS DEP",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:2: Cannot bind 'DEP' twice in the USING clauses of one import.");
    }

    @Test
    void callingThroughAnImportThatIsNotPassedOnNamesTheMissingExport() throws Exception {
        write("arith.evo",
                "EXPORT .PROC ADD_CLAMPED",
                "  RET",
                ".ENDPROC");
        write("nav.evo",
                ".IMPORT \"arith.evo\" AS ARITH",
                "NAV_START:",
                "  NOP");
        write("main.evo",
                ".IMPORT \"nav.evo\" AS NAV",
                "START:",
                "  CALL NAV.ARITH.ADD_CLAMPED");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:3: Cannot call 'NAV.ARITH.ADD_CLAMPED': alias 'ARITH' of NAV is not marked EXPORT.");
    }

    @Test
    void callingThroughARequirementOfAnotherModuleNamesTheRule() throws Exception {
        write("arith.evo",
                "EXPORT .PROC ADD",
                "  RET",
                ".ENDPROC");
        write("nav.evo",
                ".REQUIRE MODULE \"arith.evo\" AS ARITH",
                "NAV_START:",
                "  NOP");
        write("main.evo",
                ".IMPORT \"arith.evo\" AS M",
                ".IMPORT \"nav.evo\" AS NAV USING MODULE M AS ARITH",
                "START:",
                "  CALL NAV.ARITH.ADD");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:4: Cannot call 'NAV.ARITH.ADD': alias 'ARITH' of NAV is not marked EXPORT.");
    }

    @Test
    void aDottedImportAliasIsReportedOnceWithoutConsequences() throws Exception {
        write("lib.evo",
                "EXPORT READY:",
                "  NOP");
        write("main.evo",
                ".IMPORT \"lib.evo\" AS X.Y",
                "START:",
                "  JMPI X.Y.READY");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:1: Cannot define 'X.Y': a name is one segment and may not contain a dot.")
                .extracting(Throwable::getMessage, as(STRING))
                .containsOnlyOnce("[ERROR]");
    }

    @Test
    void callingThroughARequirementNoImportSuppliedNamesTheImportToFix() throws Exception {
        write("arith.evo",
                "EXPORT .PROC ADD",
                "  RET",
                ".ENDPROC");
        write("nav.evo",
                ".REQUIRE MODULE \"arith.evo\" AS ARITH",
                "EXPORT .PROC STEP",
                "  CALL ARITH.ADD",
                "  RET",
                ".ENDPROC");
        write("main.evo",
                ".IMPORT \"nav.evo\" AS NAV",
                "START:",
                "  CALL NAV.STEP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:1: Cannot import NAV: it requires 'ARITH'; add USING MODULE <module> AS ARITH to the import.")
                .hasMessageContaining("nav.evo:3: Cannot call 'ARITH.ADD': 'ARITH' leads to no module.");
    }

    @Test
    void aRequirementOfTheMainProgramIsReportedAtItsLineAndItsUseLeadsToNoModule() throws Exception {
        write("main.evo",
                ".REQUIRE MODULE \"d.evo\" AS D",
                "START:",
                "  JMPI D.X");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:1: The main program cannot require a module: nothing imports it, so nothing can supply 'D'; import \"d.evo\" instead.")
                .hasMessageContaining("main.evo:3: Cannot use 'D.X' as an argument: 'D' leads to no module.");
    }

    @Test
    void anUnusedRequirementOfTheMainProgramIsReportedAtItsLine() throws Exception {
        write("main.evo",
                ".REQUIRE MODULE \"d.evo\" AS D",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .satisfies(e -> assertThat(e.getMessage().lines().toList()).containsExactly(
                        "[ERROR] " + resolved("main.evo")
                                + ":1: The main program cannot require a module: nothing imports it, so nothing can supply 'D'; import \"d.evo\" instead."));
    }

    @Test
    void callingAProcedureAModuleDoesNotExportNamesTheMissingExport() throws Exception {
        write("nav.evo",
                ".PROC STEP",
                "  RET",
                ".ENDPROC");
        write("main.evo",
                ".IMPORT \"nav.evo\" AS NAV",
                "START:",
                "  CALL NAV.STEP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:3: Cannot call 'NAV.STEP': 'STEP' of NAV is not marked EXPORT.");
    }

    @Test
    void anExplanationQuotesTheNameAsTheProgramWroteIt() throws Exception {
        write("nav.evo",
                ".PROC STEP",
                "  RET",
                ".ENDPROC");
        write("main.evo",
                ".IMPORT \"nav.evo\" AS NAV",
                "START:",
                "  call nav.step");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:3: Cannot call 'nav.step': 'step' of nav is not marked EXPORT.");
    }

    @Test
    void callingAProcedureAModuleDoesNotHaveSaysSo() throws Exception {
        write("nav.evo",
                "NAV_START:",
                "  NOP");
        write("main.evo",
                ".IMPORT \"nav.evo\" AS NAV",
                "START:",
                "  CALL NAV.STEP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:3: Cannot call 'NAV.STEP': 'NAV' has no member 'STEP'.");
    }

    @Test
    void callingThroughAnUnknownAliasSaysWhatTheAliasIsNot() throws Exception {
        write("main.evo",
                "START:",
                "  CALL FOO.STEP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:2: Cannot call 'FOO.STEP': 'FOO' is neither a name nor an alias of this module.");
    }

    @Test
    void callingSomethingThatIsNotAProcedureSaysSo() throws Exception {
        write("main.evo",
                "START:",
                "  CALL START REF %DR0");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:2: Cannot call 'START': it is not a procedure.");
    }

    @Test
    void anUndefinedNameAsAnArgumentSaysSo() throws Exception {
        write("main.evo",
                "START:",
                "  SETI %DR0 MAX");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:2: Cannot use 'MAX' as an argument: the name is not defined.");
    }

    @Test
    void aLiteralPassedByRefSaysWhatRefTakes() throws Exception {
        write("main.evo",
                ".PROC P REF X",
                "  RET",
                ".ENDPROC",
                "START:",
                "  CALL P REF DATA:1");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:5: Invalid argument: 'DATA:1' for REF is a literal; expected a register.");
    }

    @Test
    void aDataRegisterPassedByLrefSaysItIsNoLocationRegister() throws Exception {
        write("main.evo",
                ".PROC P LREF X",
                "  RET",
                ".ENDPROC",
                "START:",
                "  CALL P LREF %DR0");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:5: Invalid argument: '%DR0' for LREF is not a location register; expected a location register.");
    }

    @Test
    void aVectorPassedByValIsAccepted() throws Exception {
        write("main.evo",
                ".PROC P VAL X",
                "  RET",
                ".ENDPROC",
                "START:",
                "  CALL P VAL 1|0");

        assertThatCode(() -> compile("main.evo")).doesNotThrowAnyException();
    }

    @Test
    void aLabelPassedByValSaysWhatValTakes() throws Exception {
        write("main.evo",
                ".PROC P VAL X",
                "  RET",
                ".ENDPROC",
                "START:",
                "  CALL P VAL START");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:5: Invalid argument: 'START' for VAL is a label; expected a register, a typed literal or a vector.");
    }

    @Test
    void aLiteralPassedByLvalSaysWhatLvalTakes() throws Exception {
        write("main.evo",
                ".PROC P LVAL X",
                "  RET",
                ".ENDPROC",
                "START:",
                "  CALL P LVAL DATA:1");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:5: Invalid argument: 'DATA:1' for LVAL is a literal; expected a location register or a label.");
    }

    @Test
    void anUnexpectedWordInAProcDeclarationNamesWhatIsExpected() throws Exception {
        write("main.evo",
                "# A comment line.",
                ".PROC P FOO X",
                "  RET",
                ".ENDPROC",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:2: Unexpected 'FOO' in .PROC: expected REF, VAL, LREF or LVAL.");
    }

    @Test
    void anUnclosedRepeatIsReportedAtTheRepeat() throws Exception {
        write("main.evo",
                "START:",
                "  NOP",
                ".REPEAT 2",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageFindingMatch("main\\.evo:3: \\.REPEAT opened at \\S*main\\.evo:3 is not closed before the end of the input");
    }

    @Test
    void anUnclosedProcIsReportedAtTheProc() throws Exception {
        write("main.evo",
                "START:",
                "  NOP",
                ".PROC STEP",
                "  RET");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageFindingMatch("main\\.evo:3: \\.PROC opened at \\S*main\\.evo:3 is not closed before the end of the input");
    }

    @Test
    void anImportMissingAUsingClauseSaysWhichToAdd() throws Exception {
        write("dep.evo", "D:", "  NOP");
        write("lib.evo",
                ".REQUIRE MODULE \"dep.evo\" AS DEP",
                "L:",
                "  NOP");
        write("main.evo",
                "# A comment line.",
                ".IMPORT \"lib.evo\" AS LIB",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:2: Cannot import LIB: it requires 'DEP'; add USING MODULE <module> AS DEP to the import.");
    }

    @Test
    void anUnknownDirectiveIsCalledUnknown() throws Exception {
        write("main.evo",
                "START:",
                "  .FOO 1");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:2: Unknown directive '.FOO'.");
    }

    @Test
    void aBrokenOperandNamesWhatAnOperandCanBe() throws Exception {
        write("main.evo",
                "START:",
                "  SETI %DR0 :");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:2: Expected a register, a literal, a vector or a name, but got ':'.");
    }

    private void write(String fileName, String... lines) throws Exception {
        Files.writeString(sourceRoot.resolve(fileName), String.join("\n", lines) + "\n");
    }

    private void compile(String fileName) throws Exception {
        compile(fileName, ENV);
    }

    private void compile(String fileName, EnvironmentProperties env) throws Exception {
        CompilerOptions options = new CompilerOptions(List.of(new SourceRoot(sourceRoot.toString(), null)));
        new Compiler().compile(fileName, env, options);
    }

    /**
     * Returns the path of a file in the source root as the compiler writes a resolved path, with
     * forward slashes on every platform.
     */
    private String resolved(String fileName) {
        return sourceRoot.resolve(fileName).toString().replace('\\', '/');
    }
}
