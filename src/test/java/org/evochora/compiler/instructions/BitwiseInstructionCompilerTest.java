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
class BitwiseInstructionCompilerTest extends CompilerTestBase {

    private Compiler compiler;

    @BeforeEach
    void setUp() {
        Instruction.init();
        compiler = new Compiler();
    }

    @Test
    void testAND_OR_XOR_NAND_NOT_and_shifts() {
        String source = String.join("\n",
                "ANDR %DR0 %DR1",
                "ANDI %DR0 DATA:1",
                "ANDS",
                "ORR %DR0 %DR1",
                "ORI %DR0 DATA:1",
                "ORS",
                "XORR %DR0 %DR1",
                "XORI %DR0 DATA:1",
                "XORS",
                "NADR %DR0 %DR1",
                "NADI %DR0 DATA:1",
                "NADS",
                "NOT %DR0",
                "NOTS",
                "SHLR %DR0 %DR1",
                "SHLI %DR0 DATA:1",
                "SHLS",
                "SHRR %DR0 %DR1",
                "SHRI %DR0 DATA:1",
                "SHRS"
        );
        List<String> lines = List.of(source.split("\n"));
        assertDoesNotThrow(() -> {
            ProgramArtifact artifact = compiler.compile(lines, "bit_auto.s", testEnvProps);
            assertThat(artifact).isNotNull();
            assertThat(artifact.machineCodeLayout()).isNotEmpty();
        });
    }
}
