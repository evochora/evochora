package org.evochora.compiler.features.control;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Reaches a control block of an imported module from the importer. The block, its cases and its
 * end are names on levels like any other, so the one visibility rule of qualified names decides:
 * a path such as {@code LIB.WALK.BLOCKED} resolves only when every segment it crosses into the
 * library is marked {@code EXPORT}, and a missing marker is reported naming that segment.
 */
class ControlBlockModuleTest {

    private static final String EXPORT = "EXPORT ";
    private static final String PLAIN = "";

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
    void anExportedBlockAndCaseAreReachedFromTheImporterAndNamedByTheirPaths() throws Exception {
        writeLib(EXPORT, EXPORT, PLAIN);
        writeMain("  JMPI LIB.WALK", "  JMPI LIB.WALK.BLOCKED");

        ProgramArtifact artifact = compile("main.evo");

        assertThat(artifact.labelNameToValue()).containsKeys("LIB.WALK", "LIB.WALK.BLOCKED", "LIB.WALK.END");
    }

    @Test
    @Tag("integration")
    void aBlockWithoutExportIsReportedNamingTheBlock() throws Exception {
        writeLib(PLAIN, EXPORT, EXPORT);
        writeMain("  JMPI LIB.WALK");

        assertThatCode(() -> compile("main.evo"))
                .as("the block's name is a member of the library that is not exported")
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("'WALK' of LIB is not marked EXPORT.");
    }

    @Test
    @Tag("integration")
    void aCaseWithoutExportIsReportedNamingTheCaseOfTheBlock() throws Exception {
        writeLib(EXPORT, PLAIN, PLAIN);
        writeMain("  JMPI LIB.WALK.BLOCKED");

        assertThatCode(() -> compile("main.evo"))
                .as("the block is exported, its case is not")
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("'BLOCKED' of LIB.WALK is not marked EXPORT.");
    }

    @Test
    @Tag("integration")
    void theExportedBlockAloneIsReachedWhenItsCaseIsNotExported() throws Exception {
        writeLib(EXPORT, PLAIN, PLAIN);
        writeMain("  JMPI LIB.WALK");

        assertThatCode(() -> compile("main.evo")).doesNotThrowAnyException();
    }

    @Test
    @Tag("integration")
    void anExportedEndIsReachedFromTheImporter() throws Exception {
        writeLib(EXPORT, PLAIN, EXPORT);
        writeMain("  JMPI LIB.WALK.END");

        assertThatCode(() -> compile("main.evo")).doesNotThrowAnyException();
    }

    @Test
    @Tag("integration")
    void anEndWithoutExportIsReportedNamingTheEnd() throws Exception {
        writeLib(EXPORT, EXPORT, PLAIN);
        writeMain("  JMPI LIB.WALK.END");

        assertThatCode(() -> compile("main.evo"))
                .as("the block is exported, its end is not")
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("'END' of LIB.WALK is not marked EXPORT.");
    }

    @Test
    @Tag("integration")
    void aBlockInsideAnExportedProcedureIsReachedThroughTheProcedure() throws Exception {
        writeLibWithProcedure(EXPORT);
        writeMain("  CALL LIB.P", "  JMPI LIB.P.WALK");

        assertThatCode(() -> compile("main.evo")).doesNotThrowAnyException();
    }

    @Test
    @Tag("integration")
    void aBlockInsideAProcedureWithoutExportIsReportedNamingTheProcedure() throws Exception {
        writeLibWithProcedure(PLAIN);
        writeMain("  JMPI LIB.P.WALK");

        assertThatCode(() -> compile("main.evo"))
                .as("the block is exported, the procedure holding it is not")
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("'P' of LIB is not marked EXPORT.");
    }

    @Test
    @Tag("integration")
    void aBlockIsReachedThroughAnImportThatIsPassedOn() throws Exception {
        writeLib(EXPORT, PLAIN, PLAIN);
        write("main.evo",
                "EXPORT .IMPORT \"lib.evo\" AS LIB",
                "  NOP");
        write("outer.evo",
                ".IMPORT \"main.evo\" AS M",
                "  JMPI M.LIB.WALK");

        assertThatCode(() -> compile("outer.evo")).doesNotThrowAnyException();
    }

    /**
     * Writes a library with one block {@code WALK}: a head that leaves to the case or out of the
     * block, and the case {@code BLOCKED}. Each argument is the prefix of one block word, either
     * {@link #EXPORT} or {@link #PLAIN}.
     */
    private void writeLib(String block, String blockedCase, String end) throws Exception {
        write("lib.evo",
                block + ".CONTROL WALK",
                "  NOP",
                "  JMPI BLOCKED",
                "  JMPI END",
                blockedCase + ".CASE BLOCKED",
                "  NOP",
                end + ".ENDCONTROL",
                "  NOP");
    }

    /**
     * Writes a library whose block {@code WALK} stands inside the procedure {@code P}; the block
     * is exported, the procedure carries the given prefix.
     */
    private void writeLibWithProcedure(String procedure) throws Exception {
        write("lib.evo",
                procedure + ".PROC P",
                "  EXPORT .CONTROL WALK",
                "    NOP",
                "    JMPI END",
                "  .ENDCONTROL",
                "  RET",
                ".ENDPROC");
    }

    /**
     * Writes the importer, which imports the library as {@code LIB} and runs the given lines.
     */
    private void writeMain(String... body) throws Exception {
        String[] lines = new String[body.length + 1];
        lines[0] = ".IMPORT \"lib.evo\" AS LIB";
        System.arraycopy(body, 0, lines, 1, body.length);
        write("main.evo", lines);
    }

    private void write(String name, String... lines) throws Exception {
        Files.writeString(tempDir.resolve(name), String.join("\n", lines) + "\n");
    }

    private ProgramArtifact compile(String name) throws Exception {
        Path file = tempDir.resolve(name);
        return compiler.compile(
                Files.readAllLines(file),
                file.toAbsolutePath().toString(),
                new EnvironmentProperties(new int[]{100, 100}, true));
    }
}
