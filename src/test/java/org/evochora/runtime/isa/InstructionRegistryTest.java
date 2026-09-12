package org.evochora.runtime.isa;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.evochora.runtime.isa.instructions.ArithmeticInstruction;
import org.evochora.runtime.isa.instructions.BitwiseInstruction;
import org.evochora.runtime.isa.instructions.ConditionalInstruction;
import org.evochora.runtime.isa.instructions.ControlFlowInstruction;
import org.evochora.runtime.isa.instructions.DataInstruction;
import org.evochora.runtime.isa.instructions.EnvironmentInteractionInstruction;
import org.evochora.runtime.isa.instructions.LocationInstruction;
import org.evochora.runtime.isa.instructions.NopInstruction;
import org.evochora.runtime.isa.instructions.StackInstruction;
import org.evochora.runtime.isa.instructions.StateInstruction;
import org.evochora.runtime.isa.instructions.VectorInstruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins what an opcode ID is and what the registry answers about it.
 * <p>
 * An opcode ID is an allocation and nothing more: the family in the lowest five bits, the
 * instruction's index within that family above it, the rest reserved and zero. What an instruction
 * belongs to, what it is called and what it reads is registered, not decoded, so these tests ask
 * the registry rather than the bits — except where the allocation itself is the subject.
 */
@Tag("unit")
class InstructionRegistryTest {

    /** Number of bits the family occupies at the bottom of an opcode ID. */
    private static final int FAMILY_BITS = 5;

    /** Highest family the layout can express. */
    private static final int MAX_FAMILY = 31;

    /** Highest index an instruction can carry within its family. */
    private static final int MAX_INDEX = 255;

    /** Which family each registering class allocates its opcodes in. */
    private static final Map<Class<? extends Instruction>, Integer> FAMILY_OF_CLASS = Map.ofEntries(
            Map.entry(NopInstruction.class, Family.SPECIAL),
            Map.entry(ArithmeticInstruction.class, Family.ARITHMETIC),
            Map.entry(BitwiseInstruction.class, Family.BITWISE),
            Map.entry(DataInstruction.class, Family.DATA),
            Map.entry(StackInstruction.class, Family.DATA),
            Map.entry(ConditionalInstruction.class, Family.CONDITIONAL),
            Map.entry(ControlFlowInstruction.class, Family.CONTROL),
            Map.entry(EnvironmentInteractionInstruction.class, Family.ENVIRONMENT),
            Map.entry(StateInstruction.class, Family.STATE),
            Map.entry(LocationInstruction.class, Family.LOCATION),
            Map.entry(VectorInstruction.class, Family.VECTOR));

    @BeforeAll
    static void initInstructions() {
        Instruction.init();
    }

    @Test
    void everyRegisteredIdLiesInTheOpcodeLayout() {
        for (Instruction.InstructionInfo info : Instruction.getInstructionSetInfo()) {
            int id = info.opcodeId();
            assertThat(id)
                    .as("%s has an opcode ID outside the layout", info.name())
                    .isBetween(0, (MAX_INDEX << FAMILY_BITS) | MAX_FAMILY);
            assertThat(id & MAX_FAMILY)
                    .as("family bits of %s", info.name())
                    .isEqualTo(Instruction.getFamilyById(id));
            assertThat(id >> FAMILY_BITS)
                    .as("index of %s within its family", info.name())
                    .isBetween(0, MAX_INDEX);
        }
    }

    /**
     * The family bits of an opcode are the family the registering class allocates in. A class that
     * registered in another family would hand its instructions ids of a family they do not belong
     * to, and the registry would then say one thing while the ID says another.
     */
    @Test
    void everyOpcodeCarriesTheFamilyOfTheClassThatRegisteredIt() {
        for (Instruction.InstructionInfo info : Instruction.getInstructionSetInfo()) {
            Integer expected = FAMILY_OF_CLASS.get(info.family());
            assertThat(expected)
                    .as("%s registers instructions such as %s", info.family().getSimpleName(), info.name())
                    .isNotNull();
            assertThat(Instruction.getFamilyById(info.opcodeId()))
                    .as("family of %s", info.name())
                    .isEqualTo(expected);
        }
    }

    @Test
    void idsAndNamesAreUnique() {
        List<Instruction.InstructionInfo> instructions = Instruction.getInstructionSetInfo();
        Set<Integer> ids = new HashSet<>();
        Map<String, String> names = new HashMap<>();

        for (Instruction.InstructionInfo info : instructions) {
            assertThat(ids.add(info.opcodeId()))
                    .as("opcode ID %d of %s is used twice", info.opcodeId(), info.name())
                    .isTrue();
            assertThat(names.put(info.name().toUpperCase(), info.name()))
                    .as("instruction name %s is used twice", info.name())
                    .isNull();
        }
        assertThat(instructions).as("the registered instruction set").isNotEmpty();
    }

    /**
     * {@code CODE:0} is the empty cell and the virtual machine's NOP, so NOP has to be the opcode
     * that value names. It is the one ID the instruction set is not free to move.
     */
    @Test
    void nopHasIdZero() {
        assertThat(Instruction.getInstructionIdByName("NOP")).isZero();
    }

    @ParameterizedTest(name = "{0} is family {1}, operation {2}")
    @CsvSource({
            "NOP, 0, 0",
            "ADDR, 1, 0",
            "ADDS, 1, 0",
            "SUBI, 1, 1",
            "ANDR, 2, 0",
            "DUP, 3, 3",
            "GTI, 4, 3",
            "PGTI, 4, 20",
            "JMPI, 5, 0",
            "PEKI, 6, 0",
            "FRKI, 7, 9",
            "POPL, 8, 5",
            "VGTS, 9, 0"
    })
    void registryAnswersFamilyAndOperation(String name, int family, int operation) {
        int id = Instruction.getInstructionIdByName(name);
        assertThat(Instruction.getFamilyById(id)).as("family of %s", name).isEqualTo(family);
        assertThat(Instruction.getOperationById(id)).as("operation of %s", name).isEqualTo(operation);
    }

    @Test
    void anUnregisteredOpcodeHasNoFamilyAndNoOperation() {
        int unregistered = (MAX_INDEX << FAMILY_BITS) | MAX_FAMILY;
        assertThat(Instruction.getFamilyById(unregistered)).isEqualTo(-1);
        assertThat(Instruction.getOperationById(unregistered)).isEqualTo(-1);
    }

    /**
     * The opcodes that do the same thing with different operands share their operation number,
     * which is what ties them together for a mutation that changes only how the operands are
     * supplied.
     */
    @ParameterizedTest(name = "{0} and {1} are one operation of one family")
    @CsvSource({
            "ADDR, ADDI",
            "ADDR, ADDS",
            "ANDR, ANDS",
            "IFR, IFI",
            "PUSH, PUSI",
            "JMPR, JMPI",
            "PEEK, PEKI"
    })
    void opcodesOfOneOperationShareFamilyAndOperation(String first, String second) {
        int firstId = Instruction.getInstructionIdByName(first);
        int secondId = Instruction.getInstructionIdByName(second);
        assertThat(Instruction.getFamilyById(secondId)).isEqualTo(Instruction.getFamilyById(firstId));
        assertThat(Instruction.getOperationById(secondId)).isEqualTo(Instruction.getOperationById(firstId));
    }

    // ---- Registration validation ----

    @Test
    void aFamilyOutsideTheLayoutIsRejected() {
        assertThatThrownBy(() -> Instruction.opcodeIdOf(MAX_FAMILY + 1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Family");
        assertThatThrownBy(() -> Instruction.opcodeIdOf(-1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Family");
    }

    @Test
    void anIndexOutsideTheLayoutIsRejected() {
        assertThatThrownBy(() -> Instruction.opcodeIdOf(Family.ARITHMETIC, MAX_INDEX + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Index");
        assertThatThrownBy(() -> Instruction.opcodeIdOf(Family.ARITHMETIC, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Index");
    }

    /**
     * A second registration for an index already in use is refused before anything is written, so
     * the attempt leaves the registered instruction set as it stands.
     */
    @Test
    void anIndexAlreadyInUseIsRejected() {
        int addr = Instruction.getInstructionIdByName("ADDR");
        int indexOfAddr = addr >> FAMILY_BITS;

        assertThatThrownBy(() -> Instruction.registerOp(
                ArithmeticInstruction.class, ArithmeticInstruction::new,
                Family.ARITHMETIC, 63, indexOfAddr, "TESTOP", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADDR");

        assertThat(Instruction.getInstructionIdByName("TESTOP")).as("nothing was registered").isNull();
        assertThat(Instruction.getInstructionNameById(addr)).isEqualTo("ADDR");
    }

    /**
     * A name already in use is refused as well: the compiler resolves a mnemonic to exactly one
     * opcode, so two opcodes of one name would make the source ambiguous.
     */
    @Test
    void aNameAlreadyInUseIsRejected() {
        assertThatThrownBy(() -> Instruction.registerOp(
                ArithmeticInstruction.class, ArithmeticInstruction::new,
                Family.ARITHMETIC, 63, MAX_INDEX, "ADDR", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADDR");

        assertThat(Instruction.getFamilyById(Instruction.opcodeIdOf(Family.ARITHMETIC, MAX_INDEX)))
                .as("nothing was registered at the free index")
                .isEqualTo(-1);
    }
}
