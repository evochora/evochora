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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
                .hasMessageContaining("Cannot use 'LIB.PRIVATE' as an argument: 'PRIVATE' of LIB is not marked EXPORT.")
                .hasMessageContaining("main.evo:3");
    }

    @Test
    void macroInvokedWithWrongArgumentCountNamesTheMacroAndTheLineOfTheInvocation() throws Exception {
        write("main.evo",
                ".MACRO INC REG",
                "  ADDI REG DATA:1",
                ".ENDM",
                "START:",
                "  INC %DR0 %DR1");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Macro 'INC' expects 1 arguments, but got 2")
                .hasMessageContaining("main.evo:5");
    }

    @Test
    void secondDefinitionOfAMacroNamesBothPlaces() throws Exception {
        write("macros.evo",
                ".MACRO INC REG",
                "  ADDI REG DATA:1",
                ".ENDM");
        write("main.evo",
                ".SOURCE \"macros.evo\"",
                ".MACRO INC REG",
                "  SUBI REG DATA:1",
                ".ENDM",
                "START:",
                "  INC %DR0");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Macro 'INC' is already defined in")
                .hasMessageContaining("macros.evo:1")
                .hasMessageContaining("main.evo:2");
    }

    @Test
    void importWithoutAliasNamesTheMissingAsAndTheLine() throws Exception {
        write("lib.evo",
                "  NOP");
        write("main.evo",
                ".IMPORT \"lib.evo\"",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Expected AS after .IMPORT path.")
                .hasMessageContaining("main.evo:1");
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
                .hasMessageContaining("Expected AS after .IMPORT path.")
                .hasMessageContaining("main.evo:2");
    }

    @Test
    void importOfAMissingFileNamesTheFileAndTheLine() throws Exception {
        write("main.evo",
                ".IMPORT \"nowhere.evo\" AS X",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Module file not found: nowhere.evo")
                .hasMessageContaining("main.evo:1");
    }

    @Test
    void sourceOfAMissingFileNamesTheFileAndTheLine() throws Exception {
        write("main.evo",
                ".SOURCE \"nowhere.evo\"",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Source file not found: nowhere.evo")
                .hasMessageContaining("main.evo:1");
    }

    @Test
    void importPathFromAMacroParameterIsRejectedAtTheInvocation() throws Exception {
        write("lib.evo",
                "  NOP");
        write("main.evo",
                ".MACRO USE P",
                "  .IMPORT P AS X",
                ".ENDM",
                "USE \"lib.evo\"",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining(".IMPORT path must be a literal, not a macro parameter")
                .hasMessageContaining("main.evo:4");
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
                .hasMessageContaining(".IMPORT not allowed in a .SOURCE file")
                .hasMessageContaining("lib.evo:3");
    }

    @Test
    void repeatWithoutACountNamesTheDirectiveAndTheLine() throws Exception {
        write("main.evo",
                "START:",
                "  NOP",
                ".REPEAT",
                "  NOP",
                ".ENDR");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Expected repeat count after .REPEAT")
                .hasMessageContaining("main.evo:3");
    }

    @Test
    void registerIndexBeyondTheBankIsRejectedWhereTheRegisterIsWritten() throws Exception {
        write("main.evo",
                "START:",
                "  SETI %DR999 DATA:1");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Register '%DR999' is out of bounds. Valid range: %DR0-%DR")
                .hasMessageContaining("main.evo:2");
    }

    @Test
    void registerIndexBeyondTheBankIsRejectedInARegDirectiveToo() throws Exception {
        write("main.evo",
                ".REG %X %DR999",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Register '%DR999' is out of bounds. Valid range: %DR0-%DR")
                .hasMessageContaining("main.evo:1");
    }

    @Test
    void aParameterRegisterNamedInSourceIsRejectedWithTheWayOut() throws Exception {
        write("main.evo",
                "START:",
                "  SETI %FDR0 DATA:1");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Register '%FDR0' is reserved for procedure parameters. Use the parameter's name instead.")
                .hasMessageContaining("main.evo:2");
    }

    @Test
    void aProcedureScopedRegisterAliasedOutsideAProcedureNamesTheScope() throws Exception {
        write("main.evo",
                ".REG %X %PDR0",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Register '%PDR0' is only available inside a procedure.")
                .hasMessageContaining("main.evo:1");
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

    @Test
    void aMacroOfAnImportedModuleIsNotAMacroInTheImportingFile() throws Exception {
        write("lib.evo",
                ".MACRO INC REG",
                "  ADDI REG DATA:1",
                ".ENDM",
                "HELPER:",
                "  NOP");
        write("main.evo",
                ".IMPORT \"lib.evo\" AS LIB",
                "START:",
                "  INC %DR0");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("'INC'")
                .hasMessageContaining("main.evo:3");
    }

    @Test
    void aMacroOfTheImportingFileIsNotAMacroInTheImportedModule() throws Exception {
        write("lib.evo",
                "HELPER:",
                "  INC %DR0");
        write("main.evo",
                ".MACRO INC REG",
                "  ADDI REG DATA:1",
                ".ENDM",
                ".IMPORT \"lib.evo\" AS LIB",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("'INC'")
                .hasMessageContaining("lib.evo:2");
    }

    @Test
    void twoModulesMayEachDefineAMacroOfTheSameName() throws Exception {
        write("a.evo",
                ".MACRO INC REG",
                "  ADDI REG DATA:1",
                ".ENDM",
                "A:",
                "  INC %DR0");
        write("b.evo",
                ".MACRO INC REG",
                "  ADDI REG DATA:2",
                ".ENDM",
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
                ".ENDM");
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
                ".DEFINE LIMIT DATA:5",
                "START:",
                "  SETI LIMIT DATA:1");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Argument 1 for instruction 'SETI' has the wrong type. Expected REGISTER, but got LITERAL.")
                .hasMessageContaining("main.evo:3");
    }

    @Test
    void aRegisterAliasWhereALiteralIsExpectedNamesTheKindItHas() throws Exception {
        write("main.evo",
                ".REG %X %DR1",
                "START:",
                "  SETI %DR0 %X");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Argument 2 for instruction 'SETI' has the wrong type. Expected LITERAL, but got REGISTER.")
                .hasMessageContaining("main.evo:3");
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
                .hasMessageContaining("'LIB' is not a value, a register or a label and cannot be an instruction argument.")
                .hasMessageContaining("main.evo:3");
    }

    @Test
    void aLocationAliasPassedByRefNamesTheKindItHas() throws Exception {
        write("main.evo",
                ".PROC P REF X",
                "  RET",
                ".ENDP",
                ".REG %POS %LR0",
                "START:",
                "  CALL P REF %POS");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("REF argument '%POS' is a location register, expected a data register.")
                .hasMessageContaining("main.evo:6");
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
                ".ENDP",
                "START:",
                "  CALL P REF LIB");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("'LIB' is not a register or a label and cannot be a CALL argument.")
                .hasMessageContaining("main.evo:6");
    }

    @Test
    void aCircularDefinitionNamesTheCircle() throws Exception {
        write("main.evo",
                ".DEFINE A B",
                ".DEFINE B A",
                "START:",
                "  SETI %DR0 A");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Definition of A is circular: A -> B -> A.")
                .hasMessageContaining("main.evo:1");
    }

    @Test
    void aUsingClauseWithoutAsNamesTheMissingAsAndTheLine() throws Exception {
        write("lib.evo",
                "HELPER:",
                "  NOP");
        write("main.evo",
                ".IMPORT \"lib.evo\" AS LIB USING X",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Expected AS after USING source alias.")
                .hasMessageContaining("main.evo:1");
    }

    @Test
    void anUnknownMoleculeTypeInAPlacementNamesTheTypeAndTheLine() throws Exception {
        write("main.evo",
                "START:",
                "  NOP",
                ".PLACE FOO:1 5|5");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Unknown molecule type 'FOO' in .PLACE.")
                .hasMessageContaining("main.evo:3");
    }

    @Test
    void aPlacementWithMoreDimensionsThanTheWorldNamesBothCounts() throws Exception {
        write("main.evo",
                "START:",
                "  NOP",
                ".PLACE DATA:1 5|5|*");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining(".PLACE uses 3 dimensions, the world has 2.")
                .hasMessageContaining("main.evo:3");
    }

    @Test
    void aPlacementCoordinateWithMoreDimensionsThanTheWorldIsRejected() throws Exception {
        write("main.evo",
                "START:",
                "  NOP",
                ".PLACE DATA:1 5|5|7");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Coordinate [5, 5, 7] has 3 dimensions, the world has 2.")
                .hasMessageContaining("main.evo:3");
    }

    @Test
    void anOriginWithMoreDimensionsThanTheWorldIsRejectedAtTheFirstCellPlacedThere() throws Exception {
        write("main.evo",
                ".ORG 1|2|3",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Coordinate [1, 2, 3] has 3 dimensions, the world has 2.")
                .hasMessageContaining("main.evo:2");
    }

    @Test
    void constantsOfTheSameNameInTwoModulesAreNoCircle() throws Exception {
        write("lib.evo",
                ".DEFINE A DATA:5",
                "EXPORT .DEFINE B A",
                "HELPER:",
                "  NOP");
        write("main.evo",
                ".IMPORT \"lib.evo\" AS LIB",
                ".DEFINE A LIB.B",
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
                .hasMessageContaining("Cannot define label 'START': the name is already used at")
                .hasMessageContaining("main.evo:1")
                .hasMessageContaining("main.evo:3");
    }

    @Test
    void aSecondProcedureOfTheSameNameNamesTheFirst() throws Exception {
        write("main.evo",
                ".PROC STEP",
                "  RET",
                ".ENDP",
                ".PROC STEP",
                "  RET",
                ".ENDP",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Cannot define procedure 'STEP': the name is already used at")
                .hasMessageContaining("main.evo:1")
                .hasMessageContaining("main.evo:4");
    }

    @Test
    void aSecondConstantOfTheSameNameNamesTheFirst() throws Exception {
        write("main.evo",
                ".DEFINE MAX DATA:1",
                ".DEFINE MAX DATA:2",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Cannot define constant 'MAX': the name is already used at")
                .hasMessageContaining("main.evo:1")
                .hasMessageContaining("main.evo:2");
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
                .hasMessageContaining("Cannot define register alias '%TMP': the name is already used at")
                .hasMessageContaining("main.evo:1")
                .hasMessageContaining("main.evo:2");
    }

    @Test
    void aParameterDeclaredTwiceNamesTheProcedure() throws Exception {
        write("main.evo",
                ".PROC STEP REF X VAL X",
                "  RET",
                ".ENDP",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Cannot declare parameter 'X' twice in procedure 'STEP'.")
                .hasMessageContaining("main.evo:1");
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
                .hasMessageContaining("Cannot import as 'LIB': the name is already used at")
                .hasMessageContaining("main.evo:1")
                .hasMessageContaining("main.evo:2");
    }

    @Test
    void aSecondRequirementUnderTheSameAliasNamesTheFirst() throws Exception {
        write("lib.evo",
                ".REQUIRE \"a.evo\" AS DEP",
                ".REQUIRE \"b.evo\" AS DEP",
                "L:",
                "  NOP");
        write("a.evo", "A:", "  NOP");
        write("b.evo", "B:", "  NOP");
        write("main.evo",
                ".IMPORT \"a.evo\" AS A",
                ".IMPORT \"lib.evo\" AS LIB USING A AS DEP",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Cannot require as 'DEP': the name is already used at")
                .hasMessageContaining("lib.evo:1")
                .hasMessageContaining("lib.evo:2");
    }

    @Test
    void aUsingAliasBoundTwiceInOneImportIsRejected() throws Exception {
        write("lib.evo",
                ".REQUIRE \"a.evo\" AS DEP",
                "L:",
                "  NOP");
        write("a.evo", "A:", "  NOP");
        write("main.evo",
                ".IMPORT \"a.evo\" AS A",
                ".IMPORT \"lib.evo\" AS LIB USING A AS DEP USING A AS DEP",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Cannot bind 'DEP' twice in the USING clauses of one import.")
                .hasMessageContaining("main.evo:2");
    }

    @Test
    void callingThroughAnImportThatIsNotPassedOnNamesTheMissingExport() throws Exception {
        write("arith.evo",
                "EXPORT .PROC ADD_CLAMPED",
                "  RET",
                ".ENDP");
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
                .hasMessageContaining("Cannot call 'NAV.ARITH.ADD_CLAMPED': import 'ARITH' of NAV is not marked EXPORT.")
                .hasMessageContaining("main.evo:3");
    }

    @Test
    void callingThroughARequirementOfAnotherModuleNamesTheRule() throws Exception {
        write("arith.evo",
                "EXPORT .PROC ADD",
                "  RET",
                ".ENDP");
        write("nav.evo",
                ".REQUIRE \"arith.evo\" AS ARITH",
                "NAV_START:",
                "  NOP");
        write("main.evo",
                ".IMPORT \"arith.evo\" AS M",
                ".IMPORT \"nav.evo\" AS NAV USING M AS ARITH",
                "START:",
                "  CALL NAV.ARITH.ADD");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Cannot call 'NAV.ARITH.ADD': 'ARITH' is a requirement of NAV; use the module you supplied for it.")
                .hasMessageContaining("main.evo:4");
    }

    @Test
    void callingAProcedureAModuleDoesNotExportNamesTheMissingExport() throws Exception {
        write("nav.evo",
                ".PROC STEP",
                "  RET",
                ".ENDP");
        write("main.evo",
                ".IMPORT \"nav.evo\" AS NAV",
                "START:",
                "  CALL NAV.STEP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Cannot call 'NAV.STEP': 'STEP' of NAV is not marked EXPORT.")
                .hasMessageContaining("main.evo:3");
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
                .hasMessageContaining("Cannot call 'NAV.STEP': NAV has no symbol 'STEP'.")
                .hasMessageContaining("main.evo:3");
    }

    @Test
    void callingThroughAnUnknownAliasSaysWhatTheAliasIsNot() throws Exception {
        write("main.evo",
                "START:",
                "  CALL FOO.STEP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Cannot call 'FOO.STEP': 'FOO' is neither an import nor a requirement of this module.")
                .hasMessageContaining("main.evo:2");
    }

    @Test
    void callingSomethingThatIsNotAProcedureSaysSo() throws Exception {
        write("main.evo",
                "START:",
                "  CALL START REF %DR0");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Cannot call 'START': it is not a procedure.")
                .hasMessageContaining("main.evo:2");
    }

    @Test
    void anUndefinedNameAsAnArgumentSaysSo() throws Exception {
        write("main.evo",
                "START:",
                "  SETI %DR0 MAX");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Cannot use 'MAX' as an argument: the name is not defined.")
                .hasMessageContaining("main.evo:2");
    }

    private void write(String fileName, String... lines) throws Exception {
        Files.writeString(sourceRoot.resolve(fileName), String.join("\n", lines) + "\n");
    }

    private void compile(String fileName) throws Exception {
        CompilerOptions options = new CompilerOptions(List.of(new SourceRoot(sourceRoot.toString(), null)));
        new Compiler().compile(fileName, ENV, options);
    }
}
