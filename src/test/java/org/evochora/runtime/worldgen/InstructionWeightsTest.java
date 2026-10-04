package org.evochora.runtime.worldgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.instructions.ConditionalJumpInstruction;
import org.evochora.runtime.isa.instructions.ConditionalSkipInstruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Random;

/**
 * Pins how an {@code instructionWeights} block weights the opcodes a mutation draws, and which
 * blocks it rejects.
 */
@Tag("unit")
class InstructionWeightsTest {

    private static final String DATA = "org.evochora.runtime.isa.instructions.DataInstruction";
    private static final String SKIP = "org.evochora.runtime.isa.instructions.ConditionalSkipInstruction";

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    private static InstructionWeights weights(String block) {
        return InstructionWeights.fromConfig(ConfigFactory.parseString(block), "test");
    }

    private static double weightOf(InstructionWeights weights, String name) {
        return weights.weightOf(Instruction.getInstructionIdByName(name));
    }

    /**
     * A family's weight times an opcode's weight; a family not listed weighs the top-level default
     * and each of its opcodes one; a listed family's opcodes not named weigh its default.
     */
    @Test
    void anOpcodeWeighsItsFamilyTimesItself() {
        InstructionWeights weights = weights("""
                default = 1
                families = [
                  { class = "%s", weight = 0.5, default = 1, opcodes { PGTI = 2 } }
                ]
                """.formatted(SKIP));

        assertThat(weightOf(weights, "ADDR")).as("a family not listed").isEqualTo(1.0);
        assertThat(weightOf(weights, "IFI")).as("an opcode not named").isEqualTo(0.5);
        assertThat(weightOf(weights, "PGTI")).as("a named opcode").isEqualTo(1.0);
    }

    /**
     * A blacklist leaves out what it names; everything else, a family added later included, is
     * drawn.
     */
    @Test
    void aBlacklistLeavesOutWhatItNames() {
        InstructionWeights weights = weights("""
                default = 1
                families = [ { class = "%s", weight = 0, default = 1 } ]
                """.formatted(SKIP));

        for (Instruction.InstructionInfo info : Instruction.getInstructionSetInfo()) {
            assertThat(weights.weightOf(info.opcodeId()))
                    .as(info.name())
                    .isEqualTo(info.family() == ConditionalSkipInstruction.class ? 0.0 : 1.0);
        }
    }

    /**
     * A whitelist draws only what it names and stays closed: an opcode of a listed family that is
     * not named, and every family not listed, weigh zero.
     */
    @Test
    void aWhitelistDrawsOnlyWhatItNames() {
        InstructionWeights weights = weights("""
                default = 0
                families = [ { class = "%s", weight = 1, default = 0, opcodes { SETI = 1 } } ]
                """.formatted(DATA));

        for (Instruction.InstructionInfo info : Instruction.getInstructionSetInfo()) {
            assertThat(weights.weightOf(info.opcodeId()))
                    .as(info.name())
                    .isEqualTo("SETI".equals(info.name()) ? 1.0 : 0.0);
        }
    }

    /**
     * A family of weight zero takes its named opcodes along without complaint: the opcode weights
     * stay in the configuration for when the family is switched on again.
     */
    @Test
    void aFamilyOfWeightZeroSilencesItsNamedOpcodes() {
        InstructionWeights weights = weights("""
                default = 1
                families = [ { class = "%s", weight = 0, default = 1, opcodes { PGTI = 2 } } ]
                """.formatted(SKIP));

        assertThat(weightOf(weights, "PGTI")).isEqualTo(0.0);
    }

    /**
     * A draw takes opcodes in proportion to their weights and never one of weight zero.
     */
    @Test
    void aDrawFollowsTheWeights() {
        int ifi = Instruction.getInstructionIdByName("IFI");
        int ini = Instruction.getInstructionIdByName("INI");
        int jfi = Instruction.getInstructionIdByName("JFI");
        InstructionWeights weights = weights("""
                default = 0
                families = [
                  { class = "%s", weight = 1, default = 0, opcodes { IFI = 3, INI = 1 } }
                ]
                """.formatted(SKIP));
        WeightedOpcodes drawable = weights.select(new int[]{ifi, ini, jfi});

        assertThat(drawable.size()).as("JFI weighs zero").isEqualTo(2);
        Random random = new Random(7);
        int ifiDraws = 0;
        int draws = 40_000;
        for (int i = 0; i < draws; i++) {
            int opcode = drawable.opcodeAt(drawable.drawIndex(random));
            assertThat(opcode).isNotEqualTo(jfi);
            if (opcode == ifi) {
                ifiDraws++;
            }
        }
        assertThat(ifiDraws / (double) draws).isCloseTo(0.75, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void theTopLevelDefaultIsRequired() {
        assertRejected("families = []", "default");
    }

    @Test
    void theFamiliesAreRequired() {
        assertRejected("default = 1", "families");
    }

    @Test
    void aFamilyNeedsItsWeightAndDefault() {
        assertRejected("""
                default = 1
                families = [ { class = "%s", weight = 1 } ]
                """.formatted(SKIP), "default");
        assertRejected("""
                default = 1
                families = [ { class = "%s", default = 1 } ]
                """.formatted(SKIP), "weight");
    }

    @Test
    void anUnknownKeyIsRejected() {
        assertRejected("default = 1, families = [], defualt = 0", "defualt");
        assertRejected("""
                default = 1
                families = [ { class = "%s", weight = 1, default = 1, opcode { IFI = 1 } } ]
                """.formatted(SKIP), "opcode");
    }

    @Test
    void aClassThatIsNoInstructionClassIsRejected() {
        assertRejected("""
                default = 1
                families = [ { class = "ConditionalSkipInstruction", weight = 1, default = 1 } ]
                """, "ConditionalSkipInstruction");
    }

    @Test
    void aFamilyListedTwiceIsRejected() {
        assertRejected("""
                default = 1
                families = [
                  { class = "%1$s", weight = 1, default = 1 }
                  { class = "%1$s", weight = 0, default = 1 }
                ]
                """.formatted(SKIP), "twice");
    }

    @Test
    void anUnknownOpcodeIsRejected() {
        assertRejected("""
                default = 1
                families = [ { class = "%s", weight = 1, default = 1, opcodes { IFQ = 1 } } ]
                """.formatted(SKIP), "IFQ");
    }

    @Test
    void anOpcodeUnderAnotherFamilyIsRejected() {
        assertRejected("""
                default = 1
                families = [ { class = "%s", weight = 1, default = 1, opcodes { JFI = 1 } } ]
                """.formatted(SKIP), ConditionalJumpInstruction.class.getName());
    }

    @Test
    void aNegativeWeightIsRejected() {
        assertRejected("default = -1, families = []", "default");
        assertRejected("""
                default = 1
                families = [ { class = "%s", weight = 1, default = 1, opcodes { IFI = -2 } } ]
                """.formatted(SKIP), "IFI");
    }

    private static void assertRejected(String block, String named) {
        Config config = ConfigFactory.parseString(block);
        assertThatThrownBy(() -> InstructionWeights.fromConfig(config, "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(named);
    }
}
