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
}
