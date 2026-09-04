package org.evochora.compiler.backend.emit;

import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;
import org.evochora.compiler.model.ir.IrImm;
import org.evochora.compiler.model.ir.IrInstruction;
import org.evochora.compiler.model.ir.IrLabelRef;
import org.evochora.compiler.model.ir.IrReg;
import org.evochora.compiler.model.ir.IrTypedImm;
import org.evochora.compiler.model.ir.IrVec;
import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Molecule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What each kind of IR operand becomes in the machine code.
 */
@Tag("unit")
class OperandEncoderTest {

    private static final SourceInfo SRC = new SourceInfo("test.evo", 3, 1);

    private final OperandEncoder encoder = new OperandEncoder(new RuntimeInstructionSetAdapter());

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void registerBecomesOneRegisterMolecule() throws Exception {
        int[] cells = encoder.encodeOperand(new IrReg("%DR0"), SRC);

        assertThat(cells).hasSize(1);
        assertThat(Molecule.fromInt(cells[0]).type()).isEqualTo(Config.TYPE_REGISTER);
    }

    @Test
    void immediateBecomesOneDataMolecule() throws Exception {
        int[] cells = encoder.encodeOperand(new IrImm(42), SRC);

        assertThat(cells).containsExactly(new Molecule(Config.TYPE_DATA, 42).toInt());
    }

    @Test
    void typedImmediateCarriesItsMoleculeType() throws Exception {
        int[] cells = encoder.encodeOperand(new IrTypedImm("STRUCTURE", 7), SRC);

        assertThat(cells).containsExactly(new Molecule(Config.TYPE_STRUCTURE, 7).toInt());
    }

    @Test
    void vectorBecomesOneDataMoleculePerComponent() throws Exception {
        int[] cells = encoder.encodeOperand(new IrVec(new int[]{1, -2}), SRC);

        assertThat(cells).containsExactly(
                new Molecule(Config.TYPE_DATA, 1).toInt(),
                new Molecule(Config.TYPE_DATA, -2).toInt());
    }

    @Test
    void labelBecomesALabelMoleculeWithTheAssignedValue() {
        assertThat(encoder.encodeLabel(12345))
                .isEqualTo(new Molecule(Config.TYPE_LABEL, 12345).toInt());
    }

    @Test
    void opcodeIsTheInstructionSetsIdOfTheName() throws Exception {
        int cell = encoder.encodeOpcode(new IrInstruction("NOP", List.of(), SRC));

        assertThat(cell).isEqualTo(Instruction.getInstructionIdByName("NOP"));
    }

    @Test
    void unresolvedLabelReferenceIsReportedAtTheInstruction() {
        assertThatThrownBy(() -> encoder.encodeOperand(new IrLabelRef("NOWHERE"), SRC))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("test.evo:3")
                .hasMessageContaining("NOWHERE");
    }

    @Test
    void unknownMoleculeTypeIsReportedAtTheInstruction() {
        assertThatThrownBy(() -> encoder.encodeOperand(new IrTypedImm("NO_SUCH_TYPE", 1), SRC))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("test.evo:3")
                .hasMessageContaining("NO_SUCH_TYPE");
    }
}
