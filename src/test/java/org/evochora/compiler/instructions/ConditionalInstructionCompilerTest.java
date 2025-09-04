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
class ConditionalInstructionCompilerTest extends CompilerTestBase {

    private Compiler compiler;

    @BeforeEach
    void setUp() {
        Instruction.init();
        compiler = new Compiler();
    }

    @Test
    void testIF_equal_less_greater_and_type_and_IFM_variants() {
        String source = String.join("\n",
                "IFR %DR0 %DR1",
                "IFI %DR0 DATA:1",
                "IFS",
                "LTR %DR0 %DR1",
                "LTI %DR0 DATA:1",
                "LTS",
                "GTR %DR0 %DR1",
                "GTI %DR0 DATA:1",
                "GTS",
                "IFTR %DR0 %DR1",
                "IFTI %DR0 DATA:1",
                "IFTS",
                "IFMR %LR0",
                "IFMI 1|0",
                "IFMS"
        );
        List<String> lines = List.of(source.split("\n"));
        assertDoesNotThrow(() -> {
            ProgramArtifact artifact = compiler.compile(lines, "cond_auto.s", testEnvProps);
            assertThat(artifact).isNotNull();
            assertThat(artifact.machineCodeLayout()).isNotEmpty();
        });
    }
}
