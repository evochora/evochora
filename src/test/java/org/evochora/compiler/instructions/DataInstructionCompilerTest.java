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
class DataInstructionCompilerTest extends CompilerTestBase {

    private Compiler compiler;

    @BeforeEach
    void setUp() {
        Instruction.init();
        compiler = new Compiler();
    }

    @Test
    void testSETI_SETR_SETV_PUSH_POP_PUSI() {
        String source = String.join("\n",
                "LBL:",
                "SETI %DR0 DATA:1",
                "SETR %DR0 %DR1",
                "SETV %DR0 LBL",
                "PUSH %DR0",
                "POP %DR0",
                "PUSI DATA:1"
        );
        List<String> lines = List.of(source.split("\n"));
        assertDoesNotThrow(() -> {
            ProgramArtifact artifact = compiler.compile(lines, "data_auto.s", testEnvProps);
            assertThat(artifact).isNotNull();
            assertThat(artifact.machineCodeLayout()).isNotEmpty();
        });
    }

    @Test
    void testPUSV() {
        String source = String.join("\n",
                "LBL:",
                "PUSV LBL"
        );
        List<String> lines = List.of(source.split("\n"));
        assertDoesNotThrow(() -> {
            ProgramArtifact artifact = compiler.compile(lines, "pusv_test.s", testEnvProps);
            assertThat(artifact).isNotNull();
            assertThat(artifact.machineCodeLayout()).isNotEmpty();
        });
    }
}
