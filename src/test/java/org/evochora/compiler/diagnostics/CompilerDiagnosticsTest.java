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
                .hasMessageContaining("Symbol 'LIB.PRIVATE' is not defined")
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
    void codeLaidOverCodeNamesBothLines() throws Exception {
        write("main.evo",
                ".ORG 0|0",
                "START:",
                "  NOP",
                ".ORG 0|0",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Coordinate [0, 0] is already occupied by an instruction at")
                .hasMessageContaining("main.evo:2")
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

        compile("main.evo");
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

        compile("main.evo");
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

    private void write(String fileName, String... lines) throws Exception {
        Files.writeString(sourceRoot.resolve(fileName), String.join("\n", lines) + "\n");
    }

    private void compile(String fileName) throws Exception {
        CompilerOptions options = new CompilerOptions(List.of(new SourceRoot(sourceRoot.toString(), null)));
        new Compiler().compile(fileName, ENV, options);
    }
}
