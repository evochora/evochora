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
class VectorInstructionCompilerTest extends CompilerTestBase {

    private Compiler compiler;

    @BeforeEach
    void setUp() {
        Instruction.init();
        compiler = new Compiler();
    }

    @Test
    void testVectorGet_operations() {
        String source = String.join("\n",
                "VGTR %DR0 %DR1 %DR2",
                "VGTI %DR0 %DR1 DATA:1",
                "VGTS"
        );
        List<String> lines = List.of(source.split("\n"));
        assertDoesNotThrow(() -> {
            ProgramArtifact artifact = compiler.compile(lines, "vector_auto.s", testEnvProps);
            assertThat(artifact).isNotNull();
            assertThat(artifact.machineCodeLayout()).isNotEmpty();
        });
    }

    @Test
    void testVectorSet_operations() {
        String source = String.join("\n",
                "VSTR %DR0 %DR1 %DR2",
                "VSTI %DR0 DATA:1 DATA:2",
                "VSTS"
        );
        List<String> lines = List.of(source.split("\n"));
        assertDoesNotThrow(() -> {
            ProgramArtifact artifact = compiler.compile(lines, "vector_auto.s", testEnvProps);
            assertThat(artifact).isNotNull();
            assertThat(artifact.machineCodeLayout()).isNotEmpty();
        });
    }

    @Test
    void testVectorBuild_operations() {
        String source = String.join("\n",
                "VBLD %DR0",
                "VBLS"
        );
        List<String> lines = List.of(source.split("\n"));
        assertDoesNotThrow(() -> {
            ProgramArtifact artifact = compiler.compile(lines, "vector_auto.s", testEnvProps);
            assertThat(artifact).isNotNull();
            assertThat(artifact.machineCodeLayout()).isNotEmpty();
        });
    }

    @Test
    void testBitToVector_operations() {
        String source = String.join("\n",
                "B2VR %DR0 %DR1",
                "B2VI %DR0 DATA:1",
                "B2VS"
        );
        List<String> lines = List.of(source.split("\n"));
        assertDoesNotThrow(() -> {
            ProgramArtifact artifact = compiler.compile(lines, "vector_auto.s", testEnvProps);
            assertThat(artifact).isNotNull();
            assertThat(artifact.machineCodeLayout()).isNotEmpty();
        });
    }

    @Test
    void testVectorToBit_operations() {
        String source = String.join("\n",
                "V2BR %DR0 %DR1",
                "V2BI %DR0 1|0",
                "V2BS"
        );
        List<String> lines = List.of(source.split("\n"));
        assertDoesNotThrow(() -> {
            ProgramArtifact artifact = compiler.compile(lines, "vector_auto.s", testEnvProps);
            assertThat(artifact).isNotNull();
            assertThat(artifact.machineCodeLayout()).isNotEmpty();
        });
    }

    @Test
    void testVectorRotate_operations() {
        String source = String.join("\n",
                "RTRR %DR0 %DR1 %DR2",
                "RTRI %DR0 DATA:1 DATA:2",
                "RTRS"
        );
        List<String> lines = List.of(source.split("\n"));
        assertDoesNotThrow(() -> {
            ProgramArtifact artifact = compiler.compile(lines, "vector_auto.s", testEnvProps);
            assertThat(artifact).isNotNull();
            assertThat(artifact.machineCodeLayout()).isNotEmpty();
        });
    }
}
