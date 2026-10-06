package org.evochora.compiler.module;

import java.nio.file.Files;
import java.nio.file.Path;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifies that a path reaches a name inside a procedure of an imported module.
 * <p>
 * A module and a procedure are levels; a name of a level the writer does not stand in is visible
 * only through its path, and only if every segment that leads into such a level is marked
 * {@code EXPORT}. From an importing module, {@code UTIL.CLAMP.TO_MIN} therefore needs the
 * procedure {@code CLAMP} exported by the module and the label {@code TO_MIN} exported by the
 * procedure. An import passed on with {@code EXPORT .IMPORT} is such a segment as well.
 */
@Tag("integration")
class QualifiedPathResolutionTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void initInstructionSet() {
        Instruction.init();
    }

    @Test
    void aLabelExportedByAnExportedProcedureIsReachedByItsPath() throws Exception {
        writeUtil("EXPORT ");

        assertThatCode(() -> compileMain("  PSLI UTIL.CLAMP.TO_MIN"))
                .doesNotThrowAnyException();
    }

    @Test
    void aLabelTheProcedureDoesNotExportIsReported() throws Exception {
        writeUtil("EXPORT ");

        assertThatCode(() -> compileMain("  PSLI UTIL.CLAMP.DONE"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Cannot use 'UTIL.CLAMP.DONE' as an argument: 'DONE' of UTIL.CLAMP is not marked EXPORT.");
    }

    @Test
    void aProcedureTheModuleDoesNotExportEndsThePath() throws Exception {
        writeUtil("");

        assertThatCode(() -> compileMain("  PSLI UTIL.CLAMP.TO_MIN"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Cannot use 'UTIL.CLAMP.TO_MIN' as an argument: 'CLAMP' of UTIL is not marked EXPORT.");
    }

    @Test
    void aNameTheModuleDoesNotHaveIsReported() throws Exception {
        writeUtil("EXPORT ");

        assertThatCode(() -> compileMain("  PSLI UTIL.NOPE"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Cannot use 'UTIL.NOPE' as an argument: 'UTIL' has no member 'NOPE'.");
    }

    @Test
    void aProcedureBehindAPassedOnImportIsCalledByItsPath() throws Exception {
        writePassedOn("EXPORT .IMPORT \"step.evo\" AS STEP");

        assertThatCode(() -> compile("main.evo"))
                .doesNotThrowAnyException();
    }

    @Test
    void anImportThatIsNotPassedOnEndsThePath() throws Exception {
        writePassedOn(".IMPORT \"step.evo\" AS STEP");

        assertThatCode(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Cannot call 'NAV.STEP.FORWARD': import 'STEP' of NAV is not marked EXPORT.");
    }

    /**
     * Writes {@code lib/util.evo}: a procedure {@code CLAMP} with the given export prefix, holding
     * an exported label {@code TO_MIN} and a label {@code DONE} it keeps to itself.
     */
    private void writeUtil(String clampExport) throws Exception {
        Files.createDirectories(tempDir.resolve("lib"));
        Files.writeString(tempDir.resolve("lib/util.evo"), String.join("\n",
                clampExport + ".PROC CLAMP",
                "  JMPI DONE",
                "EXPORT TO_MIN:",
                "  NOP",
                "DONE:",
                "  RET",
                ".ENDPROC",
                ""));
    }

    /**
     * Writes {@code main.evo}, importing {@code lib/util.evo} as {@code UTIL}, with the given line.
     */
    private void compileMain(String line) throws Exception {
        Files.writeString(tempDir.resolve("main.evo"), String.join("\n",
                ".IMPORT \"lib/util.evo\" AS UTIL",
                line,
                ""));
        compile("main.evo");
    }

    /**
     * Writes three modules: main calls {@code NAV.STEP.FORWARD}, nav imports step with the given
     * directive, and step exports the procedure {@code FORWARD}.
     */
    private void writePassedOn(String navImport) throws Exception {
        Files.writeString(tempDir.resolve("step.evo"), String.join("\n",
                "EXPORT .PROC FORWARD",
                "  RET",
                ".ENDPROC",
                ""));
        Files.writeString(tempDir.resolve("nav.evo"), String.join("\n",
                navImport,
                "  NOP",
                ""));
        Files.writeString(tempDir.resolve("main.evo"), String.join("\n",
                ".IMPORT \"nav.evo\" AS NAV",
                "  CALL NAV.STEP.FORWARD",
                ""));
    }

    private ProgramArtifact compile(String file) throws Exception {
        Path path = tempDir.resolve(file);
        return new Compiler().compile(
                Files.readAllLines(path),
                path.toAbsolutePath().toString(),
                new EnvironmentProperties(new int[]{100, 100}, true));
    }
}
