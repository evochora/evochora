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
                "IFMR %DR0",
                "IFMI 1|0",
                "IFMS",
                "IFPR %DR0",
                "IFPI 1|0",
                "IFPS",
                "IFFR %DR0",
                "IFFI 1|0",
                "IFFS",
                "IFVR %DR0",
                "IFVI 1|0",
                "IFVS",
                "IFER"
        );
        List<String> lines = List.of(source.split("\n"));
        assertDoesNotThrow(() -> {
            ProgramArtifact artifact = compiler.compile(lines, "cond_auto.s", testEnvProps);
            assertThat(artifact).isNotNull();
            assertThat(artifact.machineCodeLayout()).isNotEmpty();
        });
    }

    @Test
    void testNegatedConditional_variants() {
        String source = String.join("\n",
                "INR %DR0 %DR1",
                "GETR %DR0 %DR1",
                "LETR %DR0 %DR1",
                "INTR %DR0 %DR1",
                "GETI %DR0 DATA:1",
                "LETI %DR0 DATA:1",
                "INTI %DR0 DATA:1",
                "INI %DR0 DATA:1",
                "INS",
                "GETS",
                "LETS",
                "INTS",
                "INMR %DR0",
                "INMI 1|0",
                "INMS",
                "INPR %DR0",
                "INPI 1|0",
                "INPS",
                "INFR %DR0",
                "INFI 1|0",
                "INFS",
                "INVR %DR0",
                "INVI 1|0",
                "INVS",
                "INER"
        );
        List<String> lines = List.of(source.split("\n"));
        assertDoesNotThrow(() -> {
            ProgramArtifact artifact = compiler.compile(lines, "cond_auto.s", testEnvProps);
            assertThat(artifact).isNotNull();
            assertThat(artifact.machineCodeLayout()).isNotEmpty();
        });
    }
}
