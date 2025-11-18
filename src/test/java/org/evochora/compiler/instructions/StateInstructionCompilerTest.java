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
class StateInstructionCompilerTest extends CompilerTestBase {

    private Compiler compiler;

    @BeforeEach
    void setUp() {
        Instruction.init();
        compiler = new Compiler();
    }

    @Test
    void testTURN_SYNC_NRG_POS_DIFF_RAND_SEEK_SCAN_FORK() {
        String source = String.join("\n",
                "TURN %DR0",
                "TRNI 1|0",
                "TRNS",
                "SYNC",
                "NRG %DR0",
                "NRGS",
                "POS %DR0",
                "POSS",
                "DIFF %DR0",
                "DIFS",
                "RAND %DR0",
                "RNDS",
                "SEEK %DR0",
                "SEKI 1|0",
                "SEKS",
                "SCAN %DR0 %DR1",
                "SCNI %DR0 1|0",
                "SCNS",
                "FORK %DR0 %DR1 %DR2",
                "FRKI 1|0 DATA:100 0|1",
                "FRKS"
        );
        List<String> lines = List.of(source.split("\n"));
        assertDoesNotThrow(() -> {
            ProgramArtifact artifact = compiler.compile(lines, "state_auto.s", testEnvProps);
            assertThat(artifact).isNotNull();
            assertThat(artifact.machineCodeLayout()).isNotEmpty();
        });
    }

    @Test
    void testActiveDP_RandomBit_operations() {
        String source = String.join("\n",
                "ADPR %DR0",
                "ADPI DATA:1",
                "ADPS",
                "RBIR %DR0 %DR1",
                "RBII %DR0 DATA:1",
                "RBIS"
        );
        List<String> lines = List.of(source.split("\n"));
        assertDoesNotThrow(() -> {
            ProgramArtifact artifact = compiler.compile(lines, "state_auto.s", testEnvProps);
            assertThat(artifact).isNotNull();
            assertThat(artifact.machineCodeLayout()).isNotEmpty();
        });
    }

    @Test
    void testGetDV_operations() {
        String source = String.join("\n",
                "GDVR %DR0",
                "GDVS"
        );
        List<String> lines = List.of(source.split("\n"));
        assertDoesNotThrow(() -> {
            ProgramArtifact artifact = compiler.compile(lines, "gdv_auto.s", testEnvProps);
            assertThat(artifact).isNotNull();
            assertThat(artifact.machineCodeLayout()).isNotEmpty();
        });
    }
}
