package org.evochora.assembler;

import org.evochora.Config;
import org.evochora.organism.Instruction;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DisassemblerTest {

    @BeforeAll
    static void setup() {
        Instruction.init();
    }

    @Test
    void testDisassembler() {
        // Given
        Config.WORLD_SHAPE[0] = 10;
        Config.WORLD_SHAPE[1] = 10;
        World world = new World(Config.WORLD_SHAPE, true);
        world.setSymbol(new Symbol(Config.TYPE_CODE, Instruction.getInstructionIdByName("NOP")), 0, 0);
        world.setSymbol(new Symbol(Config.TYPE_CODE, Instruction.getInstructionIdByName("RET")), 1, 0);
        Disassembler disassembler = new Disassembler();

        // When
        DisassembledInstruction nopInstruction = disassembler.disassembleGeneric(new int[]{0, 0}, world);
        DisassembledInstruction retInstruction = disassembler.disassembleGeneric(new int[]{1, 0}, world);

        // Then
        assertThat(nopInstruction.opcodeName()).isEqualTo("NOP");
        assertThat(retInstruction.opcodeName()).isEqualTo("RET");
    }
}
