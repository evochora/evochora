package org.evochora.compiler.instructions;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.CompilerTestBase;
import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Molecule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
                "SETI %DR0 DATA:1",
                "SETR %DR0 %DR1",
                "SETV %DR0 1|0",
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
                "PUSV 1|0"
        );
        List<String> lines = List.of(source.split("\n"));
        assertDoesNotThrow(() -> {
            ProgramArtifact artifact = compiler.compile(lines, "pusv_test.s", testEnvProps);
            assertThat(artifact).isNotNull();
            assertThat(artifact.machineCodeLayout()).isNotEmpty();
        });
    }

    /**
     * A label is not a vector: a jump carries the label's value, and no instruction computes a
     * vector to a label. Accepting one here would reserve the cells of a vector and emit one cell.
     */
    @Test
    void aLabelWhereSetvExpectsAVectorIsReported() {
        List<String> lines = List.of("LBL:", "SETV %DR0 LBL");
        assertThatThrownBy(() -> compiler.compile(lines, "setv_label.s", testEnvProps))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Argument 2 for instruction 'SETV' has the wrong type. Expected VECTOR, but got LABEL.");
    }

    @Test
    void aLabelWherePusvExpectsAVectorIsReported() {
        List<String> lines = List.of("LBL:", "PUSV LBL");
        assertThatThrownBy(() -> compiler.compile(lines, "pusv_label.s", testEnvProps))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Argument 1 for instruction 'PUSV' has the wrong type. Expected VECTOR, but got LABEL.");
    }

    /**
     * A number that does not fit a cell is reported, not cut to the width of the value field.
     */
    @Test
    void aLiteralBeyondACellIsReported() {
        List<String> lines = List.of("SETI %DR0 DATA:1048576");
        assertThatThrownBy(() -> compiler.compile(lines, "big_literal.s", testEnvProps))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Value 1048576 out of range for a cell (-524288 to 524287).");
    }

    /**
     * A vector argument of an instruction becomes one cell per dimension of the world, so it has
     * to carry one component per dimension, whether written, taken from a constant or computed;
     * a vector that never becomes cells may have any length.
     */
    @Test
    void aVectorArgumentWithoutOneComponentPerDimensionIsReported() throws Exception {
        for (List<String> lines : List.of(
                List.of("SETV %DR2 1|2|3"),
                List.of("SETV %DR2 (1|2|3)"),
                List.of(".CONST V 1|2|3", "SETV %DR2 V"))) {
            assertThatThrownBy(() -> compiler.compile(lines, "long_vector.s", testEnvProps))
                    .isInstanceOf(CompilationException.class)
                    .hasMessageContaining("long_vector.s:" + lines.size() + ": Expected a vector with 2 components.");
        }
        ProgramArtifact indexed = compiler.compile(List.of(".CONST V 1|2|3", "SETI %DR0 DATA:V[2]"), "long_vector.s", testEnvProps);
        assertThat(indexed.machineCodeLayout().values()).extracting(Molecule::fromInt)
                .contains(new Molecule(Config.TYPE_DATA, 3, 0));
    }

    @Test
    void aPlacedValueBeyondACellIsReported() {
        List<String> lines = List.of(".PLACE STRUCTURE:1048576 1|1");
        assertThatThrownBy(() -> compiler.compile(lines, "big_place.s", testEnvProps))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("Value 1048576 out of range for a cell (-524288 to 524287).");
    }

    @Test
    void testXCHG() {
        String source = String.join("\n",
                "XCHG %DR0 %DR1"
        );
        List<String> lines = List.of(source.split("\n"));
        assertDoesNotThrow(() -> {
            ProgramArtifact artifact = compiler.compile(lines, "xchg_test.s", testEnvProps);
            assertThat(artifact).isNotNull();
            assertThat(artifact.machineCodeLayout()).isNotEmpty();
        });
    }
}
