package org.evochora.runtime.isa;

import java.util.Arrays;
import java.util.List;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.test.utils.SimulationTestUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that an instruction's argument slots are mapped to its operands by position.
 * <p>
 * A vector operand occupies one slot per dimension while every other operand occupies exactly one,
 * so an operand that follows a vector sits at a position that depends on the dimensionality of the
 * world. {@code FRKI} is the only opcode in the instruction set whose arguments continue after a
 * vector, which makes it the case where a miscounted slot would surface — as a value read from the
 * wrong cell rather than as an error.
 * <p>
 * The list has one entry per operand even when an operand cannot be read: a stack without a value
 * for it, or argument cells beyond the edge of a bounded world. Such an operand is
 * {@link Instruction.Operand#MISSING} at its place, and the instruction is marked failed; the
 * operands that can be read are resolved as always.
 */
public class InstructionArgumentSlotTest {

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    /**
     * Places an instruction and its arguments as raw molecules along the organism's direction
     * vector, starting at its instruction pointer.
     */
    private static void placeInstruction(Environment environment, Organism organism,
                                         String name, int... argumentValues) {
        int opcode = Instruction.getInstructionIdByName(name);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, opcode), organism.getIp());
        int[] position = organism.getIp();
        for (int value : argumentValues) {
            position = organism.getNextInstructionPosition(position, organism.getDv(), environment);
            environment.setMolecule(new Molecule(Config.TYPE_DATA, value), position);
        }
    }

    @ParameterizedTest(name = "{0} dimensions")
    @ValueSource(ints = {2, 3, 4, 5})
    @Tag("unit")
    void vectorArgumentsDoNotDisplaceTheOperandsThatFollowThem(int dimensions) {
        int[] shape = new int[dimensions];
        Arrays.fill(shape, 32);
        Environment environment = new Environment(shape, true);
        Simulation simulation = SimulationTestUtils.createSimulation(environment);

        int[] start = new int[dimensions];
        Arrays.fill(start, 4);
        Organism organism = Organism.create(simulation, start, 5000);
        simulation.addOrganism(organism);

        // Every argument carries a different value, so a slot read from the wrong position
        // produces a wrong value rather than an accidentally matching one.
        int[] firstVector = new int[dimensions];
        int[] secondVector = new int[dimensions];
        for (int d = 0; d < dimensions; d++) {
            firstVector[d] = 11 + d;
            secondVector[d] = 71 + d;
        }
        int childEnergy = 500;

        int[] arguments = new int[2 * dimensions + 1];
        System.arraycopy(firstVector, 0, arguments, 0, dimensions);
        arguments[dimensions] = childEnergy;
        System.arraycopy(secondVector, 0, arguments, dimensions + 1, dimensions);
        placeInstruction(environment, organism, "FRKI", arguments);

        Instruction planned = simulation.getVirtualMachine().plan(organism);
        List<Instruction.Operand> operands = planned.resolveOperands(environment);

        // The raw slots are the code stream as written, one entry per cell.
        int[] expectedRaw = new int[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            expectedRaw[i] = new Molecule(Config.TYPE_DATA, arguments[i]).toInt();
        }
        assertThat(planned.getRawArguments()).containsExactly(expectedRaw);

        // FRKI is declared as VECTOR, IMMEDIATE, VECTOR: three operands, whatever the dimensionality.
        assertThat(operands).hasSize(3);
        assertThat(operands.get(0).value()).isEqualTo(firstVector);
        assertThat(operands.get(1).value())
                .isEqualTo(new Molecule(Config.TYPE_DATA, childEnergy).toInt());
        assertThat(operands.get(2).value()).isEqualTo(secondVector);
    }

    /**
     * An instruction with stack operands and a stack that holds fewer values than it takes: the
     * value that is there stands at its place, the one that is not is MISSING, and the
     * instruction is failed while it is planned.
     */
    @Test
    @Tag("unit")
    void stackWithoutAValueLeavesTheOperandMissingAtItsPlace() {
        Environment environment = new Environment(new int[]{32, 32}, true);
        Simulation simulation = SimulationTestUtils.createSimulation(environment);
        Organism organism = Organism.create(simulation, new int[]{4, 4}, 5000);
        simulation.addOrganism(organism);
        int value = new Molecule(Config.TYPE_DATA, 17).toInt();
        organism.pushData(value);
        placeInstruction(environment, organism, "POKS");

        Instruction planned = simulation.getVirtualMachine().plan(organism);
        List<Instruction.Operand> operands = planned.resolveOperands(environment);

        // POKS takes two stack operands, the topmost first.
        assertThat(operands).hasSize(2);
        assertThat(operands.get(0).value()).isEqualTo(value);
        assertThat(operands.get(1).isMissing()).isTrue();
        assertThat(organism.isInstructionFailed()).isTrue();
        assertThat(organism.getFailureReason()).isEqualTo("Data stack underflow for POKS");
        // The list stays the instruction's own, which an interceptor may write into.
        operands.set(1, new Instruction.Operand(value, -1));
        assertThat(operands.get(1).value()).isEqualTo(value);
    }

    /**
     * In a bounded world the argument cells can reach beyond the edge. An operand whose cells
     * all lie within the world is read as always; one with a cell beyond the edge is MISSING,
     * and it is not read from the empty cells, which as a register argument would name %DR0.
     */
    @Test
    @Tag("unit")
    void argumentCellsBeyondTheEdgeLeaveTheOperandMissingAtItsPlace() {
        Environment environment = new Environment(new int[]{32, 32}, false);
        Simulation simulation = SimulationTestUtils.createSimulation(environment);
        // POKI takes a register and a vector: three cells behind the opcode. From x=29 the
        // register cell at x=30 and the first vector cell at x=31 exist, the second vector cell
        // would be at x=32.
        Organism organism = Organism.create(simulation, new int[]{29, 4}, 5000);
        organism.setDv(new int[]{1, 0});
        simulation.addOrganism(organism);
        int registerValue = new Molecule(Config.TYPE_DATA, 23).toInt();
        organism.writeOperand(1, registerValue);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("POKI")), new int[]{29, 4});
        environment.setMolecule(new Molecule(Config.TYPE_DATA, 1), new int[]{30, 4});
        environment.setMolecule(new Molecule(Config.TYPE_DATA, 1), new int[]{31, 4});

        Instruction planned = simulation.getVirtualMachine().plan(organism);
        List<Instruction.Operand> operands = planned.resolveOperands(environment);

        assertThat(operands).hasSize(2);
        assertThat(operands.get(0).value()).isEqualTo(registerValue);
        assertThat(operands.get(0).rawSourceId()).isEqualTo(1);
        assertThat(operands.get(1).isMissing()).isTrue();
        assertThat(organism.isInstructionFailed()).isTrue();
        assertThat(organism.getFailureReason()).isEqualTo(Instruction.ARGUMENT_CELL_BEYOND_THE_EDGE);
    }

    /**
     * An opcode on the last cell before the edge has every argument cell beyond it: every
     * operand is MISSING, the register one included, which would otherwise read %DR0 from an
     * empty cell.
     */
    @Test
    @Tag("unit")
    void everyArgumentCellBeyondTheEdgeLeavesEveryOperandMissing() {
        Environment environment = new Environment(new int[]{32, 32}, false);
        Simulation simulation = SimulationTestUtils.createSimulation(environment);
        Organism organism = Organism.create(simulation, new int[]{31, 4}, 5000);
        organism.setDv(new int[]{1, 0});
        simulation.addOrganism(organism);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("POKI")), new int[]{31, 4});

        Instruction planned = simulation.getVirtualMachine().plan(organism);
        List<Instruction.Operand> operands = planned.resolveOperands(environment);

        assertThat(operands).hasSize(2);
        assertThat(operands.get(0).isMissing()).isTrue();
        assertThat(operands.get(1).isMissing()).isTrue();
        assertThat(organism.getFailureReason()).isEqualTo(Instruction.ARGUMENT_CELL_BEYOND_THE_EDGE);
    }

    /**
     * MISSING is one operand that no resolution produces: an operand read from a cell or a
     * register is never equal to it, not even one without a value and with the same source ID.
     */
    @Test
    @Tag("unit")
    void noResolvedOperandEqualsTheMissingOne() {
        assertThat(Instruction.Operand.MISSING.isMissing()).isTrue();
        Instruction.Operand withoutValue = new Instruction.Operand(null, -1);
        assertThat(withoutValue.isMissing()).isFalse();
        assertThat(withoutValue).isNotEqualTo(Instruction.Operand.MISSING);
        assertThat(List.of(withoutValue)).doesNotContain(Instruction.Operand.MISSING);
    }
}
