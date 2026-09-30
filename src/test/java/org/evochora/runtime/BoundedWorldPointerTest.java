package org.evochora.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.test.utils.SimulationTestUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * In a world with topology {@code BOUND} the instruction pointer never stands outside the world.
 * Every step that would cross the edge is the organism's failure: the instruction fails, pays the
 * penalty, and the stall recovery moves the pointer on - to the next return address inside the
 * world, or to the birth position.
 * <p>
 * The organism is born on the left of a row and its code is placed towards the right edge, so
 * that the birth position is where a recovery ends and the edge is where a step fails.
 */
@Tag("unit")
class BoundedWorldPointerTest {

    private static final int SIDE = 32;
    private static final int ROW = 16;
    private static final int LAST = SIDE - 1;
    private static final int[] BIRTH = {5, ROW};
    private static final int ENERGY = 1000;
    private static final int BASE_COST = 1;
    private static final int PENALTY = 10;
    private static final int DR0 = 0;
    private static final int DR1 = 1;
    private static final int DR2 = 2;
    private static final int LABEL_A = 0x111;
    private static final int LABEL_B = 0x222;

    private Environment environment;
    private Simulation simulation;
    private Organism organism;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        environment = new Environment(new int[]{SIDE, SIDE}, false);
        simulation = SimulationTestUtils.createSimulation(environment);
        // The first organism takes ID 0, which the environment reads as "no owner"
        Organism.create(simulation, new int[]{0, 0}, 1);
        organism = Organism.create(simulation, BIRTH.clone(), ENERGY);
        simulation.addOrganism(organism);
        code(BIRTH[0], "WAIT");
    }

    private static Molecule data(int value) {
        return new Molecule(Config.TYPE_DATA, value);
    }

    private void code(int x, String name, Molecule... arguments) {
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName(name)),
                organism.getId(), new int[]{x, ROW});
        for (int i = 0; i < arguments.length; i++) {
            environment.setMolecule(arguments[i], organism.getId(), new int[]{x + 1 + i, ROW});
        }
    }

    /** A LABEL molecule the label index finds; the argument cells of jumps hold their hash as DATA. */
    private void label(int x, int hash) {
        environment.setMolecule(new Molecule(Config.TYPE_LABEL, hash), organism.getId(), new int[]{x, ROW});
    }

    private void assertRecoveredToBirth(String reason, int ticks) {
        assertThat(organism.isInstructionFailed()).isTrue();
        assertThat(organism.getFailureReason()).isEqualTo(reason);
        assertThat(organism.getIp()).containsExactly(BIRTH);
        assertThat(organism.getEr()).isEqualTo(ENERGY - ticks * BASE_COST - PENALTY);
    }

    @Test
    void advancingPastTheEdgeFailsAndRecoversToTheBirthPosition() {
        code(LAST, "WAIT");
        organism.setIp(new int[]{LAST, ROW});

        simulation.tick();

        assertRecoveredToBirth("Instruction pointer left the world", 1);
    }

    @Test
    void skippingPastTheEdgeFailsAtTheEdgeAndRecovers() {
        // Two empty cells lie between the instruction and the edge; the skip walks them and fails
        // on the step beyond, long before the skip budget is spent.
        code(LAST - 2, "WAIT");
        organism.setIp(new int[]{LAST - 2, ROW});

        simulation.tick();

        assertRecoveredToBirth("Instruction pointer left the world", 1);
    }

    @Test
    void aJumpToALabelOnTheLastCellFailsAndThePointerMovesOn() {
        code(BIRTH[0], "JMPI", data(LABEL_A));
        code(BIRTH[0] + 2, "WAIT");
        label(LAST, LABEL_A);

        simulation.tick();

        assertThat(organism.isInstructionFailed()).isTrue();
        assertThat(organism.getFailureReason()).isEqualTo("JMPI: Code cell beyond the edge of the world");
        assertThat(organism.getIp()).as("past the jump, like after a jump without a label")
                .containsExactly(BIRTH[0] + 2, ROW);
    }

    @Test
    void aCallOnTheLastCellsSucceedsAndTheReturnFailsAndRecovers() {
        // The CALL stands on the last two cells, so its return address lies beyond the edge.
        code(LAST - 1, "CALL", data(LABEL_A));
        label(10, LABEL_A);
        code(11, "RET");
        organism.setIp(new int[]{LAST - 1, ROW});

        simulation.tick();
        assertThat(organism.isInstructionFailed()).as("the call itself").isFalse();
        assertThat(organism.getIp()).containsExactly(11, ROW);
        assertThat(organism.getCallStack()).hasSize(1);
        assertThat(organism.getCallStack().peek().absoluteReturnIp()).containsExactly(SIDE, ROW);

        simulation.tick();
        assertRecoveredToBirth("RET: Return address beyond the edge of the world", 2);
        assertThat(organism.getCallStack()).isEmpty();
    }

    @Test
    void aReturnBeyondTheEdgeRecoversToTheNextReturnAddressInsideTheWorld() {
        code(BIRTH[0], "CALL", data(LABEL_A));   // returns to BIRTH + 2
        code(BIRTH[0] + 2, "WAIT");
        label(LAST - 2, LABEL_A);
        code(LAST - 1, "CALL", data(LABEL_B));   // returns beyond the edge
        label(15, LABEL_B);
        code(16, "RET");

        simulation.tick();
        simulation.tick();
        assertThat(organism.getCallStack()).hasSize(2);
        assertThat(organism.getIp()).containsExactly(16, ROW);

        simulation.tick();

        assertThat(organism.getFailureReason()).isEqualTo("RET: Return address beyond the edge of the world");
        assertThat(organism.getIp()).as("the outer call's return address").containsExactly(BIRTH[0] + 2, ROW);
        assertThat(organism.getCallStack()).isEmpty();
    }

    @Test
    void aForkAcrossTheEdgeFailsWithoutAChild() {
        organism.setMr(1);
        organism.setActiveDp(new int[]{LAST, ROW});
        organism.writeOperand(DR0, new int[]{1, 0});
        organism.writeOperand(DR1, data(100).toInt());
        organism.writeOperand(DR2, new int[]{1, 0});
        code(BIRTH[0], "FORK", data(DR0), data(DR1), data(DR2));

        simulation.tick();

        assertThat(organism.getFailureReason()).isEqualTo("FORK: Child position beyond the edge of the world");
        assertThat(simulation.getOrganisms()).as("no child").containsExactly(organism);
        assertThat(organism.getEr()).as("nothing was handed to a child").isEqualTo(ENERGY - BASE_COST - PENALTY);
    }

    @Test
    void argumentCellsBeyondTheEdgeFailTheInstructionWithoutExecutingIt() {
        // SETI on the last cell: its two argument cells lie beyond the edge. Read as empty, they
        // would name %DR0 and the value 0; the instruction must not run on them.
        organism.writeOperand(DR0, data(5).toInt());
        code(LAST, "SETI");
        organism.setIp(new int[]{LAST, ROW});

        simulation.tick();

        assertThat(organism.readOperand(DR0)).as("the register is untouched").isEqualTo(data(5).toInt());
        assertRecoveredToBirth("Argument cell lies beyond the edge of the world", 1);
    }
}
