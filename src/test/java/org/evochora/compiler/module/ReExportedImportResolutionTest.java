package org.evochora.compiler.module;

import java.nio.file.Files;
import java.nio.file.Path;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifies that a name reaches through a re-exported import.
 * <p>
 * A module may pass an import on to whoever imports it, written as {@code EXPORT .IMPORT}. A symbol
 * behind such an import is then addressed through both levels — {@code MIDDLE.INNER.SYMBOL} — and
 * resolution has to walk the chain, checking at each step that the import was in fact re-exported.
 * Without the export the same name must stay unresolved, or the marker would decide nothing.
 */
class ReExportedImportResolutionTest {

    @TempDir
    Path tempDir;

    private Compiler compiler;

    @BeforeAll
    static void initInstructionSet() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        compiler = new Compiler();
    }

    @Test
    @Tag("integration")
    void aSymbolBehindAReExportedImportResolvesThroughBothLevels() throws Exception {
        writeModules("EXPORT .IMPORT \"inner.evo\" AS INNER");

        assertThatCode(this::compileMain)
                .as("MIDDLE re-exports INNER, so MIDDLE.INNER.TARGET names a known label")
                .doesNotThrowAnyException();
    }

    @Test
    @Tag("integration")
    void withoutTheExportMarkerTheSameNameStaysUnresolved() throws Exception {
        writeModules(".IMPORT \"inner.evo\" AS INNER");

        assertThatCode(this::compileMain)
                .as("MIDDLE keeps INNER to itself, so MIDDLE.INNER.TARGET names nothing")
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("TARGET");
    }

    @Test
    @Tag("integration")
    void aChainReachesThroughEveryLevelThatPassesItOn() throws Exception {
        writeChain("EXPORT .IMPORT \"inner.evo\" AS INNER", "EXPORT .IMPORT \"middle.evo\" AS MIDDLE");

        assertThatCode(this::compileOuter)
                .as("every level passes its import on, so OUTER.MIDDLE.INNER.TARGET is reachable")
                .doesNotThrowAnyException();
    }

    @Test
    @Tag("integration")
    void oneLevelWithoutTheMarkerEndsTheChain() throws Exception {
        writeChain("EXPORT .IMPORT \"inner.evo\" AS INNER", ".IMPORT \"middle.evo\" AS MIDDLE");

        assertThatCode(this::compileOuter)
                .as("the outermost level keeps its import to itself, so the chain stops there")
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("TARGET");
    }

    /**
     * Writes four modules for a two-step chain: outer refers through main and middle to inner.
     */
    private void writeChain(String middleImport, String mainImport) throws Exception {
        Files.writeString(tempDir.resolve("inner.evo"), String.join("\n",
                "EXPORT TARGET:",
                "  NOP",
                ""));

        Files.writeString(tempDir.resolve("middle.evo"), String.join("\n",
                middleImport,
                "  NOP",
                ""));

        Files.writeString(tempDir.resolve("main.evo"), String.join("\n",
                mainImport,
                "  NOP",
                ""));

        Files.writeString(tempDir.resolve("outer.evo"), String.join("\n",
                ".IMPORT \"main.evo\" AS OUTER",
                "  JMPI OUTER.MIDDLE.INNER.TARGET",
                ""));
    }

    private ProgramArtifact compileOuter() throws Exception {
        Path outerFile = tempDir.resolve("outer.evo");
        return compiler.compile(
                Files.readAllLines(outerFile),
                outerFile.toAbsolutePath().toString(),
                new EnvironmentProperties(new int[]{100, 100}, true));
    }

    /**
     * Writes three modules: main refers through middle to inner, and middle imports inner with the
     * given directive — once with the export marker, once without.
     */
    private void writeModules(String middleImport) throws Exception {
        Files.writeString(tempDir.resolve("inner.evo"), String.join("\n",
                "EXPORT TARGET:",
                "  NOP",
                ""));

        Files.writeString(tempDir.resolve("middle.evo"), String.join("\n",
                middleImport,
                "EXPORT OWN:",
                "  NOP",
                ""));

        Files.writeString(tempDir.resolve("main.evo"), String.join("\n",
                ".IMPORT \"middle.evo\" AS MIDDLE",
                "  JMPI MIDDLE.INNER.TARGET",
                ""));
    }

    private ProgramArtifact compileMain() throws Exception {
        Path mainFile = tempDir.resolve("main.evo");
        return compiler.compile(
                Files.readAllLines(mainFile),
                mainFile.toAbsolutePath().toString(),
                new EnvironmentProperties(new int[]{100, 100}, true));
    }
}
