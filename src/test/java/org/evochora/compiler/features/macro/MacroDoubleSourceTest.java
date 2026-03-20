package org.evochora.compiler.features.macro;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end test verifying that identical macro definitions sourced from multiple
 * modules are silently deduplicated (idempotent re-registration).
 */
class MacroDoubleSourceTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void initInstructionSet() {
        org.evochora.runtime.isa.Instruction.init();
    }

    @Test
    @Tag("integration")
    void twoModulesSourceSameMacroFile_compilesSuccessfully() throws Exception {
        Files.writeString(tempDir.resolve("macros.evo"),
                ".MACRO INC R\n" +
                "  ADDI R DATA:1\n" +
                ".ENDM\n");

        Files.writeString(tempDir.resolve("module_a.evo"),
                ".SOURCE \"macros.evo\"\n" +
                "EXPORT .PROC A_WORK REF X\n" +
                "  INC X\n" +
                "  RET\n" +
                ".ENDP\n");

        Files.writeString(tempDir.resolve("module_b.evo"),
                ".SOURCE \"macros.evo\"\n" +
                "EXPORT .PROC B_WORK REF X\n" +
                "  INC X\n" +
                "  RET\n" +
                ".ENDP\n");

        Files.writeString(tempDir.resolve("main.evo"),
                ".IMPORT \"module_a.evo\" AS A\n" +
                ".IMPORT \"module_b.evo\" AS B\n" +
                "NOP\n");

        Compiler compiler = new Compiler();
        CompilerOptions options = new CompilerOptions(
                List.of(new SourceRoot(tempDir.toAbsolutePath().toString(), null)));

        EnvironmentProperties envProps = new EnvironmentProperties(new int[]{64, 64}, true);
        ProgramArtifact artifact = compiler.compile(
                "main.evo", envProps, options);

        assertThat(artifact).isNotNull();
    }
}
