package org.evochora.runtime.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.isa.Instruction;
import org.evochora.test.utils.SimulationTestUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests where {@link Organism#skipNextInstruction(Environment)} leaves the instruction pointer.
 * <p>
 * Every test places a conditional instruction of three cells at the organism's position, lets the
 * organism read it as its current instruction and then skips what follows. The instructions are
 * never executed; only the walk of the instruction pointer is under test.
 */
@Tag("unit")
class OrganismSkipNextInstructionTest {

    private static final int WORLD_SIDE = 96;

    private Environment environment;
    private Organism organism;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    /**
     * Creates a world with one organism at the given position that has just read its instruction.
     *
     * @param toroidal whether the world wraps around at its edges
     * @param start the position of the organism and of its current instruction
     * @param direction the direction of travel
     */
    private void organismAt(boolean toroidal, int[] start, int[] direction) {
        environment = new Environment(new int[]{WORLD_SIDE, WORLD_SIDE}, toroidal);
        Simulation simulation = SimulationTestUtils.createSimulation(environment);
        // The first organism takes ID 0, which the environment reads as "no owner"
        Organism.create(simulation, new int[]{-1, -1}, 1);
        organism = Organism.create(simulation, start, 1000);
        simulation.addOrganism(organism);
        organism.setDv(direction);
        organism.resetTickState();
    }

    /**
     * Places an instruction of three cells, the opcode and two arguments, along the x axis.
     *
     * @param name the name of the instruction
     * @param x the position of the opcode on the x axis
     * @param step the direction of travel along the x axis, {@code 1} or {@code -1}
     */
    private void placeThreeCells(String name, int x, int step) {
        place(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName(name)), x);
        place(new Molecule(Config.TYPE_DATA, 0), x + step);
        place(new Molecule(Config.TYPE_DATA, 1), x + 2 * step);
    }

    /**
     * Places a molecule in the row the organism travels along.
     *
     * @param molecule the molecule to place
     * @param x the position on the x axis, wrapped into the world
     */
    private void place(Molecule molecule, int x) {
        environment.setMolecule(molecule, new int[]{Math.floorMod(x, WORLD_SIDE), 5});
    }

    @Test
    void stopsBehindTheInstructionThatFollowsTheCurrentOne() {
        organismAt(true, new int[]{5, 5}, new int[]{1, 0});
        placeThreeCells("IFI", 5, 1);
        placeThreeCells("ADDI", 8, 1);

        organism.skipNextInstruction(environment);

        assertThat(organism.getIp()).containsExactly(11, 5);
        assertThat(organism.shouldSkipIpAdvance()).isTrue();
        assertThat(organism.isInstructionFailed()).isFalse();
    }

    @Test
    void passesOverCellsThatHoldNoInstruction() {
        organismAt(true, new int[]{5, 5}, new int[]{1, 0});
        placeThreeCells("IFI", 5, 1);
        place(new Molecule(Config.TYPE_DATA, 7), 8);
        // (9, 5) stays empty
        place(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("NOP")), 10);
        placeThreeCells("ADDI", 11, 1);

        organism.skipNextInstruction(environment);

        assertThat(organism.getIp()).containsExactly(14, 5);
        assertThat(organism.isInstructionFailed()).isFalse();
    }

    @Test
    void wrapsAroundTheEdgeOfAToroidalWorld() {
        organismAt(true, new int[]{94, 5}, new int[]{1, 0});
        placeThreeCells("IFI", 94, 1);
        placeThreeCells("ADDI", 97, 1);

        organism.skipNextInstruction(environment);

        assertThat(organism.getIp()).containsExactly(4, 5);
        assertThat(organism.isInstructionFailed()).isFalse();
    }

    @Test
    void followsADirectionTowardsSmallerCoordinates() {
        organismAt(true, new int[]{4, 5}, new int[]{-1, 0});
        placeThreeCells("IFI", 4, -1);
        placeThreeCells("ADDI", 1, -1);

        organism.skipNextInstruction(environment);

        assertThat(organism.getIp()).containsExactly(94, 5);
        assertThat(organism.isInstructionFailed()).isFalse();
    }

    @Test
    void leavesABoundedWorldWhenTheSkippedInstructionEndsAtItsEdge() {
        organismAt(false, new int[]{90, 5}, new int[]{1, 0});
        placeThreeCells("IFI", 90, 1);
        placeThreeCells("ADDI", 93, 1);

        organism.skipNextInstruction(environment);

        assertThat(organism.getIp()).containsExactly(96, 5);
        assertThat(organism.shouldSkipIpAdvance()).isTrue();
        assertThat(organism.isInstructionFailed()).isFalse();
    }

    @Test
    void failsWhenNothingButTheEdgeOfABoundedWorldFollows() {
        organismAt(false, new int[]{93, 5}, new int[]{1, 0});
        placeThreeCells("IFI", 93, 1);

        organism.skipNextInstruction(environment);

        assertThat(organism.isInstructionFailed()).isTrue();
        // The step beyond the edge fails at once, without spending the skip budget on cells that
        // do not exist
        assertThat(organism.getFailureReason()).isEqualTo("Instruction pointer left the world");
        assertThat(organism.shouldSkipIpAdvance()).isTrue();
        // Without a call frame to return to, the stall sends the organism back to where it was born
        assertThat(organism.getIp()).containsExactly(93, 5);
    }

    @Test
    void leavesThePositionTheInstructionWasReadFromUntouched() {
        organismAt(true, new int[]{5, 5}, new int[]{1, 0});
        placeThreeCells("IFI", 5, 1);
        placeThreeCells("ADDI", 8, 1);

        organism.skipNextInstruction(environment);

        assertThat(organism.getIpBeforeFetch()).containsExactly(5, 5);
        assertThat(organism.getInitialPosition()).containsExactly(5, 5);
    }
}
