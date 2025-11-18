package org.evochora.compiler.instructions;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.CompilerTestBase;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@Tag("unit")
class EnvironmentInteractionInstructionCompilerTest extends CompilerTestBase {

    private Compiler compiler;

    @BeforeEach
    void setUp() {
        Instruction.init();
        compiler = new Compiler();
    }

    @Test
    void testPEEK_POKE_variants() {
        String source = String.join("\n",
                "PEEK %DR0 %DR1",
                "PEKI %DR0 1|0",
                "PEKS",
                "POKE %DR0 %DR1",
                "POKI %DR0 1|0",
                "POKS"
        );
        List<String> lines = List.of(source.split("\n"));
        assertDoesNotThrow(() -> {
            ProgramArtifact artifact = compiler.compile(lines, "env_auto.s", testEnvProps);
            assertThat(artifact).isNotNull();
            assertThat(artifact.machineCodeLayout()).isNotEmpty();
        });
    }

    @Test
    void testPPKR_compilation() {
        String source = String.join("\n",
                ".PLACE DATA:99 0|1",
                ".ORG 0|0",
                "SETI %DR0 DATA:111",
                "SETV %DR1 0|1",
                "PPKR %DR0 %DR1"
        );
        List<String> lines = List.of(source.split("\n"));
        assertDoesNotThrow(() -> {
            ProgramArtifact artifact = compiler.compile(lines, "ppkr_auto.s", testEnvProps);
            assertThat(artifact).isNotNull();
            assertThat(artifact.machineCodeLayout()).isNotEmpty();
            
            // Verify that PPKR instruction was compiled
            var machineCode = artifact.machineCodeLayout();
            assertThat(machineCode.values()).contains(179); // PPKR instruction ID
            
            // After PPKR execution: DR0 should contain DATA:99 (read from cell 0|1), DR1 unchanged
            // Cell 0|1 should contain DATA:111 (written from %DR0)
        });
    }

    @Test
    void testPPKI_compilation() {
        String source = String.join("\n",
                ".PLACE DATA:99 0|1",
                ".ORG 0|0",
                "SETI %DR0 DATA:111",
                "PPKI %DR0 0|1"
        );
        List<String> lines = List.of(source.split("\n"));
        assertDoesNotThrow(() -> {
            ProgramArtifact artifact = compiler.compile(lines, "ppki_auto.s", testEnvProps);
            assertThat(artifact).isNotNull();
            assertThat(artifact.machineCodeLayout()).isNotEmpty();
            
            // Verify that PPKI instruction was compiled
            var machineCode = artifact.machineCodeLayout();
            assertThat(machineCode.values()).contains(180); // PPKI instruction ID
            
            // After PPKI execution: DR0 should contain DATA:99 (read from cell 0|1)
            // Cell 0|1 should contain DATA:111 (written from %DR0)
        });
    }

    @Test
    void testPPKS_compilation() {
        String source = String.join("\n",
                ".PLACE DATA:99 0|1",
                ".ORG 0|0",
                "SETI %DR0 DATA:111",
                "PUSH %DR0",
                "PUSV 0|1",
                "PPKS"
        );
        List<String> lines = List.of(source.split("\n"));
        assertDoesNotThrow(() -> {
            ProgramArtifact artifact = compiler.compile(lines, "ppks_auto.s", testEnvProps);
            assertThat(artifact).isNotNull();
            assertThat(artifact.machineCodeLayout()).isNotEmpty();
            
            // Verify that PPKS instruction was compiled
            var machineCode = artifact.machineCodeLayout();
            assertThat(machineCode.values()).contains(181); // PPKS instruction ID
            
            // After PPKS execution: DR0 should contain DATA:99 (read from cell 0|1)
            // Cell 0|1 should contain DATA:111 (written from stack)
            // Stack should be empty (both values popped)
        });
    }
}
