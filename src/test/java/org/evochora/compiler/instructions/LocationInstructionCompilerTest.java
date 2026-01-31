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
class LocationInstructionCompilerTest extends CompilerTestBase {

    private Compiler compiler;

    @BeforeEach
    void setUp() {
        Instruction.init();
        compiler = new Compiler();
    }

    @Test
    void testLocationStack_operations() {
        String source = String.join("\n",
                "DUPL",
                "SWPL",
                "DRPL",
                "ROTL",
                "DPLS",
                "SKLS",
                "LSDS"
        );
        List<String> lines = List.of(source.split("\n"));
        assertDoesNotThrow(() -> {
            ProgramArtifact artifact = compiler.compile(lines, "location_auto.s", testEnvProps);
            assertThat(artifact).isNotNull();
            assertThat(artifact.machineCodeLayout()).isNotEmpty();
        });
    }

    @Test
    void testLocationRegister_operations() {
        String source = String.join("\n",
                "DPLR %LR0",
                "SKLR %LR0",
                "PUSL %LR0",
                "LRDS %LR0",
                "POPL %LR0"
        );
        List<String> lines = List.of(source.split("\n"));
        assertDoesNotThrow(() -> {
            ProgramArtifact artifact = compiler.compile(lines, "location_auto.s", testEnvProps);
            assertThat(artifact).isNotNull();
            assertThat(artifact.machineCodeLayout()).isNotEmpty();
        });
    }

    @Test
    void testLocationRegisterPair_operations() {
        String source = String.join("\n",
                "LRDR %DR0 %LR1",
                "LSDR %DR0"
        );
        List<String> lines = List.of(source.split("\n"));
        assertDoesNotThrow(() -> {
            ProgramArtifact artifact = compiler.compile(lines, "location_auto.s", testEnvProps);
            assertThat(artifact).isNotNull();
            assertThat(artifact.machineCodeLayout()).isNotEmpty();
        });
    }

    @Test
    void testLrlrInstruction() {
        String source = String.join("\n",
                "LRLR %LR2 %LR1",
                "LRLR %LR0 %LR3"
        );
        List<String> lines = List.of(source.split("\n"));
        assertDoesNotThrow(() -> {
            ProgramArtifact artifact = compiler.compile(lines, "lrlr_test.s", testEnvProps);
            assertThat(artifact).isNotNull();
            assertThat(artifact.machineCodeLayout()).isNotEmpty();
        });
    }

    /**
     * Tests compilation of CRLR instruction.
     */
    @Test
    void testCrlrInstruction() {
        String source = String.join("\n",
                "CRLR %LR0",
                "CRLR %LR1",
                "CRLR %LR2",
                "CRLR %LR3"
        );
        List<String> lines = List.of(source.split("\n"));
        assertDoesNotThrow(() -> {
            ProgramArtifact artifact = compiler.compile(lines, "crlr_test.s", testEnvProps);
            assertThat(artifact).isNotNull();
            assertThat(artifact.machineCodeLayout()).isNotEmpty();
        });
    }

    /**
     * Tests compilation of SKJ* instructions (seek jump to label).
     */
    @Test
    void testSkjInstructions() {
        String source = String.join("\n",
                "TARGET:",
                "SKJI TARGET",
                "SKJR %DR0",
                "SKJS"
        );
        List<String> lines = List.of(source.split("\n"));
        assertDoesNotThrow(() -> {
            ProgramArtifact artifact = compiler.compile(lines, "skj_test.s", testEnvProps);
            assertThat(artifact).isNotNull();
            assertThat(artifact.machineCodeLayout()).isNotEmpty();
        });
    }
}
