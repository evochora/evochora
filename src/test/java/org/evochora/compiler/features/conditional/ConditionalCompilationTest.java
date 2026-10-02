package org.evochora.compiler.features.conditional;

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
 * Conditional compilation through the whole pipeline: programs written to disk, compiled with
 * flags from the options, and judged by the artifact or the message. These are the cases in
 * which the dependency scan and the preprocessor have to agree, because a dependency stands in a
 * conditional block.
 */
@Tag("integration")
class ConditionalCompilationTest {

    private static final EnvironmentProperties ENV = new EnvironmentProperties(new int[]{100, 100}, true);

    /** The cell the label {@code START:} of every program here claims. */
    private static final int LABEL = 1;

    @TempDir
    Path sourceRoot;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void aSourceInATakenBranchIsIncluded() throws Exception {
        write("extra.evo",
                "  NOP");
        write("main.evo",
                ".DEFINE WITH_EXTRA",
                "START:",
                ".IFDEF WITH_EXTRA",
                "  .SOURCE \"extra.evo\"",
                ".ENDDEF",
                "  NOP");

        ProgramArtifact artifact = compile("main.evo", Map.of());

        assertThat(artifact.sources().keySet()).anySatisfy(path -> assertThat(path).endsWith("extra.evo"));
        assertThat(artifact.machineCodeLayout()).hasSize(LABEL + 2);
    }

    @Test
    void aSourceAndAnImportInASkippedBranchAreNeverLoaded() throws Exception {
        write("main.evo",
                "START:",
                ".IFDEF WITH_EXTRA",
                "  .SOURCE \"does-not-exist.evo\"",
                "  .IMPORT \"missing-module.evo\" AS MISSING",
                ".ENDDEF",
                "  NOP");

        ProgramArtifact artifact = compile("main.evo", Map.of());

        assertThat(artifact.sources()).hasSize(1);
        assertThat(artifact.machineCodeLayout()).hasSize(LABEL + 1);
    }

    @Test
    void twoImportsUnderOneAliasInTwoBranchesChooseTheModuleByTheFlag() throws Exception {
        write("big.evo",
                "EXPORT .PROC WORK",
                "  NOP",
                "  NOP",
                "  RET",
                ".ENDPROC");
        write("small.evo",
                "EXPORT .PROC WORK",
                "  RET",
                ".ENDPROC");
        write("main.evo",
                ".IFDEF BIG",
                "  .IMPORT \"big.evo\" AS LIB",
                ".ELSEDEF",
                "  .IMPORT \"small.evo\" AS LIB",
                ".ENDDEF",
                "START:",
                "  CALL LIB.WORK");

        ProgramArtifact big = compile("main.evo", Map.of("BIG", OptionalInt.empty()));
        ProgramArtifact small = compile("main.evo", Map.of());

        assertThat(big.sources().keySet())
                .anySatisfy(path -> assertThat(path).endsWith("big.evo"))
                .noneSatisfy(path -> assertThat(path).endsWith("small.evo"));
        assertThat(small.sources().keySet())
                .anySatisfy(path -> assertThat(path).endsWith("small.evo"))
                .noneSatisfy(path -> assertThat(path).endsWith("big.evo"));
        assertThat(big.machineCodeLayout().size()).isGreaterThan(small.machineCodeLayout().size());
    }

    @Test
    void aSkippedBranchMayHoldHalfAProcedure() throws Exception {
        write("main.evo",
                "START:",
                ".IFDEF NEVER",
                "  .ENDPROC",
                ".ENDDEF",
                "  NOP");

        ProgramArtifact artifact = compile("main.evo", Map.of());

        assertThat(artifact.machineCodeLayout()).hasSize(LABEL + 1);
    }

    @Test
    void aDefineInASourcedFileDecidesALaterBlockOfTheIncluder() throws Exception {
        write("settings.evo",
                ".DEFINE WITH_EXTRA");
        write("extra.evo",
                "  NOP");
        write("main.evo",
                ".SOURCE \"settings.evo\"",
                "START:",
                ".IFDEF WITH_EXTRA",
                "  .SOURCE \"extra.evo\"",
                ".ENDDEF",
                "  NOP");

        ProgramArtifact artifact = compile("main.evo", Map.of());

        assertThat(artifact.machineCodeLayout()).hasSize(LABEL + 2);
    }

    @Test
    void aConfiguredFlagDecidesTheBlock() throws Exception {
        write("main.evo",
                "START:",
                ".IFDEF LEVEL >= 2",
                "  NOP",
                "  NOP",
                ".ENDDEF",
                "  NOP");

        assertThat(compile("main.evo", Map.of("level", OptionalInt.of(2))).machineCodeLayout()).hasSize(LABEL + 3);
        assertThat(compile("main.evo", Map.of("LEVEL", OptionalInt.of(1))).machineCodeLayout()).hasSize(LABEL + 1);
    }

    @Test
    void aConfiguredFlagThatIsNoNameIsReportedOnce() throws Exception {
        write("main.evo",
                ".IFDEF A",
                ".ENDDEF",
                ".IFDEF B",
                ".ENDDEF",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo", Map.of("NOT A NAME", OptionalInt.empty())))
                .isInstanceOf(CompilationException.class)
                .satisfies(e -> assertThat(e.getMessage().split("Configured flag 'NOT A NAME' is not a valid name",
                        -1)).hasSize(2));
    }

    /**
     * The documented limit: the dependency scan reads a module once, while the preprocessor
     * inlines it at every import. A flag the module sets, removed between two imports, is set
     * again in the preprocessor but not in the scan, so a later block on it disagrees, and the
     * file it includes was never loaded. The preprocessor reports that as an internal error.
     */
    @Test
    void aModuleImportedTwiceUnderOtherFlagsIsAnInternalErrorUntilPlacementsAreModules() throws Exception {
        write("module.evo",
                ".DEFINE MODULE_LOADED",
                "EXPORT .PROC WORK",
                "  RET",
                ".ENDPROC");
        write("extra.evo",
                "  NOP");
        write("main.evo",
                ".IMPORT \"module.evo\" AS FIRST",
                ".UNDEF MODULE_LOADED",
                ".IMPORT \"module.evo\" AS SECOND",
                ".IFDEF MODULE_LOADED",
                "  .SOURCE \"extra.evo\"",
                ".ENDDEF",
                "START:",
                "  NOP");

        assertThatThrownBy(() -> compile("main.evo", Map.of()))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:5: Internal error: the dependency scan did not load "
                        + sourceRoot.resolve("extra.evo") + ".");
    }

    private void write(String fileName, String... lines) throws Exception {
        Files.writeString(sourceRoot.resolve(fileName), String.join("\n", lines) + "\n");
    }

    private ProgramArtifact compile(String fileName, Map<String, OptionalInt> defines) throws Exception {
        CompilerOptions options = new CompilerOptions(List.of(new SourceRoot(sourceRoot.toString(), null)), defines);
        return new Compiler().compile(fileName, ENV, options);
    }
}
