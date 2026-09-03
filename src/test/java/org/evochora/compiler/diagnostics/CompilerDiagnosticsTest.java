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

    private void write(String fileName, String... lines) throws Exception {
        Files.writeString(sourceRoot.resolve(fileName), String.join("\n", lines) + "\n");
    }

    private void compile(String fileName) throws Exception {
        CompilerOptions options = new CompilerOptions(List.of(new SourceRoot(sourceRoot.toString(), null)));
        new Compiler().compile(fileName, ENV, options);
    }
}
