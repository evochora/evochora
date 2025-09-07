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
class ArithmeticInstructionCompilerTest extends CompilerTestBase {

    private Compiler compiler;

    @BeforeEach
    void setUp() {
        Instruction.init();
        compiler = new Compiler();
    }

    @Test
    void testADDR_ADDI_SUBR_SUBI_MULI_DIVI_MODI_and_stack_variants() {
        String source = String.join("\n",
                "ADDR %DR0 %DR1",
                "ADDI %DR0 DATA:1",
                "ADDS",
                "SUBR %DR0 %DR1",
                "SUBI %DR0 DATA:1",
                "SUBS",
                "MULI %DR0 DATA:2",
                "MULR %DR0 %DR1",
                "MULS",
                "DIVI %DR0 DATA:2",
                "DIVR %DR0 %DR1",
                "DIVS",
                "MODI %DR0 DATA:2",
                "MODR %DR0 %DR1",
                "MODS"
        );
        List<String> lines = List.of(source.split("\n"));
        assertDoesNotThrow(() -> {
            ProgramArtifact artifact = compiler.compile(lines, "arith_auto.s", testEnvProps);
            assertThat(artifact).isNotNull();
            assertThat(artifact.machineCodeLayout()).isNotEmpty();
        });
    }

    @Test
    void testVectorADD_SUB_register_variant() {
        String source = String.join("\n",
                "ADDR %LR0 %LR1",
                "SUBR %LR0 %LR1"
        );
        List<String> lines = List.of(source.split("\n"));
        assertDoesNotThrow(() -> {
            ProgramArtifact artifact = compiler.compile(lines, "arith_auto.s", testEnvProps);
            assertThat(artifact).isNotNull();
            assertThat(artifact.machineCodeLayout()).isNotEmpty();
        });
    }

    @Test
    void testVectorDOT_CRS_operations() {
        String source = String.join("\n",
                "DOTR %DR0 %LR0 %LR1",
                "CRSR %LR0 %LR1 %LR2",
                "DOTS",
                "CRSS"
        );
        List<String> lines = List.of(source.split("\n"));
        assertDoesNotThrow(() -> {
            ProgramArtifact artifact = compiler.compile(lines, "arith_auto.s", testEnvProps);
            assertThat(artifact).isNotNull();
            assertThat(artifact.machineCodeLayout()).isNotEmpty();
        });
    }
}
