package org.evochora.compiler.module;

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
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A module is identified by its placement, not by its file: every {@code .IMPORT} places the
 * module again under its own alias chain, scanned and preprocessed with the flags of that
 * import. Programs are written to disk, compiled through the whole pipeline and judged by the
 * artifact or the message.
 */
@Tag("integration")
class ModulePlacementIntegrationTest {

    private static final EnvironmentProperties ENV = new EnvironmentProperties(new int[]{100, 100}, true);

    @TempDir
    Path sourceRoot;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void aFileImportedTwiceIsPlacedTwice_andBothProceduresAreCalled() throws Exception {
        write("lib.evo",
                "EXPORT .PROC WORK",
                "  NOP",
                "  RET",
                ".ENDPROC");
        write("main.evo",
                ".IMPORT \"lib.evo\" AS FIRST",
                ".IMPORT \"lib.evo\" AS SECOND",
                "START:",
                "  CALL FIRST.WORK",
                "  CALL SECOND.WORK");

        ProgramArtifact artifact = compile("main.evo", Map.of());

        assertThat(artifact.procNameToParamNames()).containsOnlyKeys("FIRST.WORK", "SECOND.WORK");
        assertThat(artifact.labelNameToValue()).containsKeys("FIRST.WORK", "SECOND.WORK");
        assertThat(artifact.labelNameToValue().get("FIRST.WORK"))
                .isNotEqualTo(artifact.labelNameToValue().get("SECOND.WORK"));
        assertThat(addressesOf(artifact, "lib.evo", 2)).as("the NOP of the procedure, once per placement").isEqualTo(2);
    }

    @Test
    void twoPlacementsTakeDifferentBranchesWhenAFlagChangesBetweenTheImports() throws Exception {
        write("lib.evo",
                "EXPORT .PROC WORK",
                ".IFDEF FAST",
                "  NOP",
                ".ELSEDEF",
                "  NOP",
                "  NOP",
                ".ENDDEF",
                "  RET",
                ".ENDPROC");
        write("main.evo",
                ".DEFINE FAST",
                ".IMPORT \"lib.evo\" AS FIRST",
                ".UNDEF FAST",
                ".IMPORT \"lib.evo\" AS SECOND",
                "START:",
                "  CALL FIRST.WORK",
                "  CALL SECOND.WORK");

        ProgramArtifact artifact = compile("main.evo", Map.of());

        assertThat(addressesOf(artifact, "lib.evo", 3)).as("the branch FIRST takes").isEqualTo(1);
        assertThat(addressesOf(artifact, "lib.evo", 5) + addressesOf(artifact, "lib.evo", 6))
                .as("the branch SECOND takes").isEqualTo(2);
    }

    @Test
    void aConditionalImportInsideTheModuleFollowsTheFlagsOfEachPlacement() throws Exception {
        write("big.evo",
                "EXPORT .PROC RUN",
                "  NOP",
                "  RET",
                ".ENDPROC");
        write("small.evo",
                "EXPORT .PROC RUN",
                "  RET",
                ".ENDPROC");
        write("lib.evo",
                ".IFDEF BIG",
                "  .IMPORT \"big.evo\" AS IMPL",
                ".ELSEDEF",
                "  .IMPORT \"small.evo\" AS IMPL",
                ".ENDDEF",
                "EXPORT .PROC WORK",
                "  CALL IMPL.RUN",
                "  RET",
                ".ENDPROC");
        write("main.evo",
                ".DEFINE BIG",
                ".IMPORT \"lib.evo\" AS FIRST",
                ".UNDEF BIG",
                ".IMPORT \"lib.evo\" AS SECOND",
                "START:",
                "  CALL FIRST.WORK",
                "  CALL SECOND.WORK");

        ProgramArtifact artifact = compile("main.evo", Map.of());

        assertThat(artifact.procNameToParamNames())
                .containsOnlyKeys("FIRST.WORK", "SECOND.WORK", "FIRST.IMPL.RUN", "SECOND.IMPL.RUN");
        assertThat(addressesOf(artifact, "big.evo", 2)).as("only FIRST imports big.evo").isEqualTo(1);
        assertThat(addressesOf(artifact, "small.evo", 2)).as("only SECOND imports small.evo").isEqualTo(1);
    }

    @Test
    void aDiamondPlacesTheSharedModuleUnderEachImporter() throws Exception {
        write("m.evo",
                "EXPORT .PROC X",
                "  NOP",
                "  RET",
                ".ENDPROC");
        write("a.evo",
                "EXPORT .IMPORT \"m.evo\" AS M");
        write("b.evo",
                "EXPORT .IMPORT \"m.evo\" AS M");
        write("main.evo",
                ".IMPORT \"a.evo\" AS A",
                ".IMPORT \"b.evo\" AS B",
                "START:",
                "  CALL A.M.X",
                "  CALL B.M.X");

        ProgramArtifact artifact = compile("main.evo", Map.of());

        assertThat(artifact.procNameToParamNames()).containsOnlyKeys("A.M.X", "B.M.X");
        assertThat(addressesOf(artifact, "m.evo", 2)).isEqualTo(2);
    }

    @Test
    void aConstantChainIntoAPlacementResolvesInThatPlacement() throws Exception {
        write("lib.evo",
                ".IFDEF BIG",
                "  .CONST A DATA:5",
                ".ELSEDEF",
                "  .CONST A DATA:7",
                ".ENDDEF",
                "EXPORT .CONST B A");
        write("main.evo",
                ".DEFINE BIG",
                ".IMPORT \"lib.evo\" AS FIRST",
                ".UNDEF BIG",
                ".IMPORT \"lib.evo\" AS SECOND",
                ".CONST A FIRST.B",
                "START:",
                "  SETI %DR0 A",
                "  SETI %DR1 SECOND.B");
        write("literal.evo",
                "START:",
                "  SETI %DR0 DATA:5",
                "  SETI %DR1 DATA:7");

        ProgramArtifact placed = compile("main.evo", Map.of());
        ProgramArtifact literal = compile("literal.evo", Map.of());

        assertThat(placed.machineCodeLayout().values())
                .containsExactlyInAnyOrderElementsOf(literal.machineCodeLayout().values());
    }

    @Test
    void aFileThatImportsItselfThroughAnotherIsACycle() throws Exception {
        write("a.evo",
                ".IMPORT \"b.evo\" AS B");
        write("b.evo",
                ".IMPORT \"a.evo\" AS A");
        write("main.evo",
                ".IMPORT \"a.evo\" AS A",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo", Map.of()))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Circular dependency detected: " + sourceRoot.resolve("a.evo"));
    }

    /** The number of machine addresses the given line of a file produced. */
    private static long addressesOf(ProgramArtifact artifact, String fileName, int line) {
        return artifact.sourceMap().values().stream()
                .filter(info -> info.fileName().endsWith("/" + fileName) && info.lineNumber() == line)
                .count();
    }

    private void write(String fileName, String... lines) throws Exception {
        Files.writeString(sourceRoot.resolve(fileName), String.join("\n", lines) + "\n");
    }

    private ProgramArtifact compile(String fileName, Map<String, OptionalInt> defines) throws Exception {
        CompilerOptions options = new CompilerOptions(List.of(new SourceRoot(sourceRoot.toString(), null)), defines);
        return new Compiler().compile(fileName, ENV, options);
    }
}
