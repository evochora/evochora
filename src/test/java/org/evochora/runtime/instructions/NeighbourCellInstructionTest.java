package org.evochora.runtime.instructions;

import static org.assertj.core.api.Assertions.assertThat;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.test.utils.SimulationTestUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests the instructions that address the cell the data pointer stands on or one next to it, at
 * the edge of the world.
 * <p>
 * The data pointer stands in the corner of the world whose neighbours towards larger x and
 * towards smaller y lie across the edge. In a toroidal world those two are the cells on the
 * opposite side. In a bounded world they do not exist: a cell beyond the edge reads as empty and
 * unowned and is not passable, an instruction whose target lies there fails, and so does every
 * instruction of an organism whose data pointer has left the world.
 * <p>
 * The instructions run far from the corner, in a row of their own, so that what they are made of
 * is never among the cells they address.
 */
@Tag("unit")
class NeighbourCellInstructionTest {

    private static final int SIDE = 96;
    private static final int[] CORNER = {SIDE - 1, 0};
    private static final int[] CODE_START = {5, 40};

    /** Register ids as they stand in an argument cell: the first data registers. */
    private static final int DR0 = 0;
    private static final int DR1 = 1;

    /** The id of an organism that is not the one under test. */
    private static final int FOREIGN = 77;

    private Environment environment;
    private Simulation simulation;
    private Organism organism;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    /**
     * Creates the world and the organism, whose data pointer stands in the corner.
     *
     * @param toroidal whether the world wraps around at its edges
     */
    private void world(boolean toroidal) {
        environment = new Environment(new int[]{SIDE, SIDE}, toroidal);
        simulation = SimulationTestUtils.createSimulation(environment);
        // The first organism takes ID 0, which the environment reads as "no owner"
        Organism.create(simulation, new int[]{-1, -1}, 1);
        organism = Organism.create(simulation, CODE_START.clone(), 1000);
        simulation.addOrganism(organism);
        organism.setActiveDp(CORNER.clone());
    }

    private static Molecule data(int value) {
        return new Molecule(Config.TYPE_DATA, value);
    }

    private static Molecule energy(int value) {
        return new Molecule(Config.TYPE_ENERGY, value);
    }

    /**
     * The molecule a cell holds after the organism has written a value into it.
     *
     * @param written the molecule the organism writes
     * @return the packed molecule as the environment stores it
     */
    private int stored(Molecule written) {
        return Molecule.storedFormOfWrite(written.toInt(), organism.getMr());
    }

    /**
     * Places an instruction at the organism's instruction pointer, its arguments behind it, and
     * an instruction to stop at behind both. Without the latter the organism would walk on over
     * empty cells after the instruction, in a bounded world out of it, and fail for that.
     *
     * @param name the name of the instruction
     * @param arguments the argument cells in the order they follow the opcode; a vector takes one
     *                  per dimension
     * @return the number of cells the instruction occupies
     */
    private int place(String name, Molecule... arguments) {
        int length = placeAt(0, name, arguments);
        placeAt(length, "WAIT");
        return length;
    }

    /**
     * Places an instruction a number of cells behind the organism's instruction pointer.
     *
     * @param offset the distance of the opcode from the instruction pointer, in cells
     * @param name the name of the instruction
     * @param arguments the argument cells in the order they follow the opcode
     * @return the number of cells the instruction occupies
     */
    private int placeAt(int offset, String name, Molecule... arguments) {
        int x = CODE_START[0] + offset;
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName(name)),
                organism.getId(), new int[]{x, CODE_START[1]});
        for (int i = 0; i < arguments.length; i++) {
            environment.setMolecule(arguments[i], organism.getId(), new int[]{x + 1 + i, CODE_START[1]});
        }
        return 1 + arguments.length;
    }

    /**
     * Runs a condition on a cell and reports whether it held.
     * <p>
     * The condition is followed by one instruction and a second one behind that. A condition that
     * holds leaves the instruction pointer on the first, one that does not skips it and leaves the
     * pointer on the second.
     *
     * @param name the name of the conditional instruction
     * @param x the x component of its vector
     * @param y the y component of its vector
     * @return {@code true} if the condition held
     */
    private boolean holds(String name, int x, int y) {
        int length = place(name, data(x), data(y));
        int next = placeAt(length, "ADDI", data(DR1), data(1));
        placeAt(length + next, "WAIT");
        simulation.tick();
        assertThat(organism.isInstructionFailed()).as(name + " failed: " + organism.getFailureReason()).isFalse();
        int stoodOn = organism.getIp()[0] - CODE_START[0];
        assertThat(stoodOn).as("the pointer stands on one of the two instructions behind " + name)
                .isIn(length, length + next);
        return stoodOn == length;
    }

    private void assertFailedForLeavingTheWorld(String name) {
        assertThat(organism.isInstructionFailed()).as(name + " fails").isTrue();
        assertThat(organism.getFailureReason()).as("the reason " + name + " gives").contains("outside the world");
    }

    // ==================== Toroidal world ====================

    @Test
    void seekCrossesTheEdgeOfATorus() {
        world(true);
        place("SEKI", data(1), data(0));
        simulation.tick();
        assertThat(organism.getActiveDp()).containsExactly(0, 0);

        world(true);
        place("SEKI", data(0), data(-1));
        simulation.tick();
        assertThat(organism.getActiveDp()).containsExactly(SIDE - 1, SIDE - 1);
    }

    @Test
    void seekStaysBeforeACellOfAnotherOrganismAcrossTheEdge() {
        world(true);
        environment.setMolecule(data(3), FOREIGN, new int[]{0, 0});
        place("SEKI", data(1), data(0));
        simulation.tick();

        assertThat(organism.isInstructionFailed()).isTrue();
        assertThat(organism.getFailureReason()).isEqualTo("SEEK: Target cell is owned by another organism.");
        assertThat(organism.getActiveDp()).containsExactly(CORNER);
    }

    @Test
    void scanReadsAcrossTheEdgeOfATorus() {
        world(true);
        environment.setMolecule(energy(9), FOREIGN, new int[]{SIDE - 1, SIDE - 1});
        place("SCNI", data(DR0), data(0), data(-1));
        simulation.tick();

        assertThat(organism.isInstructionFailed()).isFalse();
        assertThat(organism.readOperand(DR0)).isEqualTo(energy(9).toInt());
        assertThat(environment.getMolecule(SIDE - 1, SIDE - 1).toInt()).as("a scan takes nothing").isEqualTo(energy(9).toInt());
    }

    @Test
    void passableNeighboursAreFoundAcrossTheEdgeOfATorus() {
        world(true);
        environment.setMolecule(data(1), FOREIGN, new int[]{0, 0});                      // +x: another's
        environment.setMolecule(data(2), organism.getId(), new int[]{SIDE - 2, 0});      // -x: own
        // +y stays empty
        environment.setMolecule(data(4), new int[]{SIDE - 1, SIDE - 1});                 // -y: nobody's
        place("SPNR", data(DR0));
        simulation.tick();

        assertThat(organism.isInstructionFailed()).isFalse();
        assertThat(organism.readOperand(DR0)).as("-x and +y are passable").isEqualTo(data(0b0110).toInt());
    }

    @Test
    void neighboursOfATypeAreFoundAcrossTheEdgeOfATorus() {
        world(true);
        environment.setMolecule(energy(1), new int[]{0, 0});                             // +x
        environment.setMolecule(data(2), new int[]{SIDE - 2, 0});                        // -x
        environment.setMolecule(energy(4), FOREIGN, new int[]{SIDE - 1, SIDE - 1});      // -y
        place("SNTI", data(DR0), energy(0));
        simulation.tick();

        assertThat(organism.isInstructionFailed()).isFalse();
        assertThat(organism.readOperand(DR0)).as("+x and -y hold energy").isEqualTo(data(0b1001).toInt());
    }

    @Test
    void conditionsTestTheCellAcrossTheEdgeOfATorus() {
        world(true);
        environment.setMolecule(data(1), organism.getId(), new int[]{0, 0});
        assertThat(holds("IFMI", 1, 0)).as("the cell is the organism's own").isTrue();

        world(true);
        environment.setMolecule(data(1), FOREIGN, new int[]{0, 0});
        assertThat(holds("IFMI", 1, 0)).as("the cell is another's").isFalse();

        world(true);
        environment.setMolecule(data(1), FOREIGN, new int[]{SIDE - 1, SIDE - 1});
        assertThat(holds("IFFI", 0, -1)).as("the cell is another's").isTrue();

        world(true);
        environment.setMolecule(data(1), FOREIGN, new int[]{SIDE - 1, SIDE - 1});
        assertThat(holds("IFPI", 0, -1)).as("another's cell is not passable").isFalse();

        world(true);
        environment.setMolecule(data(1), new int[]{SIDE - 1, SIDE - 1});
        assertThat(holds("IFVI", 0, -1)).as("the cell has no owner").isTrue();
    }

    @Test
    void conditionsWithoutADisplacementTestTheCellUnderTheDataPointer() {
        world(true);
        environment.setMolecule(data(1), organism.getId(), CORNER);
        assertThat(holds("IFMI", 0, 0)).isTrue();

        world(true);
        environment.setMolecule(data(1), FOREIGN, CORNER);
        assertThat(holds("IFPI", 0, 0)).isFalse();
    }

    @Test
    void pokeAndPeekReachAcrossTheEdgeOfATorus() {
        world(true);
        organism.writeOperand(DR0, data(5).toInt());
        place("POKI", data(DR0), data(1), data(0));
        simulation.tick();

        assertThat(organism.isInstructionFailed()).as("POKI failed: " + organism.getFailureReason()).isFalse();
        assertThat(environment.getMolecule(0, 0).toInt()).isEqualTo(stored(data(5)));
        assertThat(environment.getOwnerId(0, 0)).isEqualTo(organism.getId());

        world(true);
        environment.setMolecule(data(6), FOREIGN, new int[]{SIDE - 1, SIDE - 1});
        place("PEKI", data(DR0), data(0), data(-1));
        simulation.tick();

        assertThat(organism.isInstructionFailed()).as("PEKI failed: " + organism.getFailureReason()).isFalse();
        assertThat(organism.readOperand(DR0)).isEqualTo(data(6).toInt());
        assertThat(environment.getMolecule(SIDE - 1, SIDE - 1).isEmpty()).isTrue();
        assertThat(environment.getOwnerId(SIDE - 1, SIDE - 1)).isZero();
    }

    @Test
    void peekAndPokeInOneReachesAcrossTheEdgeOfATorus() {
        world(true);
        environment.setMolecule(data(6), FOREIGN, new int[]{0, 0});
        organism.writeOperand(DR0, data(5).toInt());
        place("PPKI", data(DR0), data(1), data(0));
        simulation.tick();

        assertThat(organism.isInstructionFailed()).as("PPKI failed: " + organism.getFailureReason()).isFalse();
        assertThat(organism.readOperand(DR0)).isEqualTo(data(6).toInt());
        assertThat(environment.getMolecule(0, 0).toInt()).isEqualTo(stored(data(5)));
        assertThat(environment.getOwnerId(0, 0)).isEqualTo(organism.getId());
    }

    // ==================== Bounded world, data pointer inside ====================

    @Test
    void aCellBeyondTheEdgeOfABoundedWorldReadsAsEmptyAndUnowned() {
        world(false);
        place("SCNI", data(DR0), data(1), data(0));
        simulation.tick();
        assertThat(organism.isInstructionFailed()).isFalse();
        assertThat(organism.readOperand(DR0)).isEqualTo(new Molecule(Config.TYPE_CODE, 0).toInt());

        world(false);
        environment.setMolecule(data(2), FOREIGN, new int[]{SIDE - 2, 0});               // -x: another's
        environment.setMolecule(data(3), FOREIGN, new int[]{SIDE - 1, 1});               // +y: another's
        place("SPNR", data(DR0));
        simulation.tick();
        assertThat(organism.isInstructionFailed()).isFalse();
        assertThat(organism.readOperand(DR0)).as("a cell beyond the edge does not exist and is not passable")
                .isEqualTo(data(0).toInt());

        world(false);
        environment.setMolecule(energy(2), new int[]{SIDE - 2, 0});                      // -x
        place("SNTI", data(DR0), energy(0));
        simulation.tick();
        assertThat(organism.isInstructionFailed()).isFalse();
        assertThat(organism.readOperand(DR0)).as("nothing beyond the edge holds energy")
                .isEqualTo(data(0b0010).toInt());
    }

    @Test
    void conditionsTakeACellBeyondTheEdgeOfABoundedWorldForEmptyAndUnowned() {
        world(false);
        assertThat(holds("IFPI", 1, 0)).as("passable: a cell that does not exist is not").isFalse();
        world(false);
        assertThat(holds("IFVI", 0, -1)).as("without an owner").isTrue();
        world(false);
        assertThat(holds("IFMI", 1, 0)).as("the organism's own").isFalse();
        world(false);
        assertThat(holds("IFFI", 0, -1)).as("another's").isFalse();
    }

    @Test
    void seekFailsAtTheEdgeOfABoundedWorld() {
        world(false);
        place("SEKI", data(1), data(0));
        simulation.tick();

        assertThat(organism.isInstructionFailed()).isTrue();
        assertThat(organism.getFailureReason()).isEqualTo("SEKI: Target cell beyond the edge of the world");
        assertThat(organism.getActiveDp()).as("the pointer stays").containsExactly(CORNER);
    }

    // ==================== Bounded world, target beyond the edge ====================

    @Test
    void pokeBeyondTheEdgeOfABoundedWorldFails() {
        world(false);
        organism.writeOperand(DR0, data(5).toInt());
        place("POKI", data(DR0), data(1), data(0));
        simulation.tick();

        assertFailedForLeavingTheWorld("POKI");
        assertThat(environment.getMolecule(0, 0).isEmpty()).as("the cell a wrap would have reached").isTrue();
    }

    @Test
    void peekBeyondTheEdgeOfABoundedWorldFails() {
        world(false);
        organism.writeOperand(DR0, data(5).toInt());
        place("PEKI", data(DR0), data(0), data(-1));
        simulation.tick();

        assertFailedForLeavingTheWorld("PEKI");
        assertThat(organism.readOperand(DR0)).as("the register keeps its value").isEqualTo(data(5).toInt());
    }

    @Test
    void peekAndPokeInOneBeyondTheEdgeOfABoundedWorldFails() {
        world(false);
        organism.writeOperand(DR0, data(5).toInt());
        place("PPKI", data(DR0), data(1), data(0));
        simulation.tick();

        assertFailedForLeavingTheWorld("PPKI");
        assertThat(organism.readOperand(DR0)).as("the register keeps its value").isEqualTo(data(5).toInt());
    }

    // ==================== Bounded world, data pointer outside ====================

    @Test
    void anOrganismWhoseDataPointerHasLeftTheWorldFailsToReadAroundIt() {
        for (String name : new String[]{"SCNI", "SPNR", "SNTI"}) {
            world(false);
            organism.setActiveDp(new int[]{SIDE, 0});
            organism.writeOperand(DR0, data(5).toInt());
            switch (name) {
                case "SCNI" -> place(name, data(DR0), data(-1), data(0));
                case "SPNR" -> place(name, data(DR0));
                default -> place(name, data(DR0), energy(0));
            }
            simulation.tick();

            assertFailedForLeavingTheWorld(name);
            assertThat(organism.readOperand(DR0)).as("the register " + name + " writes to keeps its value")
                    .isEqualTo(data(5).toInt());
        }
    }

    @Test
    void anOrganismWhoseDataPointerHasLeftTheWorldFailsToMoveIt() {
        world(false);
        organism.setActiveDp(new int[]{SIDE, 0});
        place("SEKI", data(-1), data(0));
        simulation.tick();

        assertFailedForLeavingTheWorld("SEKI");
        assertThat(organism.getActiveDp()).containsExactly(SIDE, 0);
    }

    @Test
    void anOrganismWhoseDataPointerHasLeftTheWorldFailsToTestACell() {
        for (String name : new String[]{"IFMI", "IFPI", "IFFI", "IFVI"}) {
            world(false);
            organism.setActiveDp(new int[]{SIDE, 0});
            place(name, data(-1), data(0));
            simulation.tick();

            assertFailedForLeavingTheWorld(name);
        }
    }

    @Test
    void anOrganismWhoseDataPointerHasLeftTheWorldFailsToWriteAndToTake() {
        for (String name : new String[]{"POKI", "PEKI", "PPKI"}) {
            world(false);
            organism.setActiveDp(new int[]{SIDE, 0});
            environment.setMolecule(data(8), FOREIGN, new int[]{SIDE - 1, 0});
            organism.writeOperand(DR0, data(5).toInt());
            place(name, data(DR0), data(-1), data(0));
            simulation.tick();

            assertFailedForLeavingTheWorld(name);
            assertThat(environment.getMolecule(SIDE - 1, 0).toInt()).as("the cell beside the pointer")
                    .isEqualTo(data(8).toInt());
            assertThat(organism.readOperand(DR0)).isEqualTo(data(5).toInt());
        }
    }
}
