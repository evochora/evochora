package org.evochora.runtime;

import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.Environment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.Deque;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contains unit tests for the core logic of the {@link Organism} class.
 * These tests verify fundamental aspects of an organism's state, lifecycle,
 * and interaction with its immediate environment within a simulation.
 * These are unit tests and do not require external resources.
 */
public class OrganismTest {

    private Environment environment;
    private Simulation sim;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        environment = new Environment(new int[]{100, 100}, true);
        sim = new Simulation(environment);
    }

    /**
     * Verifies that an organism attempting to execute a non-code cell (e.g., DATA)
     * correctly enters a failed state when strict typing is active.
     * This is a unit test for runtime type safety.
     */
    @Test
    @Tag("unit")
    void testPlanTickStrictTypingOnNonCodeCell() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 100, sim.getLogger());
        sim.addOrganism(org);
        // Place a DATA symbol at IP to violate strict typing
        environment.setMolecule(new Molecule(Config.TYPE_DATA, 1), org.getIp());

        Instruction planned = sim.getVirtualMachine().plan(org);
        assertThat(planned).isNotNull();
        // Planner yields a no-op placeholder with name "UNKNOWN" for illegal type
        assertThat(planned.getName()).isEqualTo("UNKNOWN");
        assertThat(org.isInstructionFailed()).isTrue();
        assertThat(org.getFailureReason()).contains("Illegal cell type");
    }

    /**
     * Verifies that an organism attempting to execute an unknown opcode
     * correctly enters a failed state.
     * This is a unit test for runtime opcode validation.
     */
    @Test
    @Tag("unit")
    void testPlanTickUnknownOpcodeProducesNop() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 100, sim.getLogger());
        sim.addOrganism(org);
        // Place a CODE opcode that doesn't exist (e.g., 999)
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 999), org.getIp());

        Instruction planned = sim.getVirtualMachine().plan(org);
        assertThat(planned).isNotNull();
        // Planner yields a no-op placeholder with name "UNKNOWN" for unknown opcode
        assertThat(planned.getName()).isEqualTo("UNKNOWN");
        assertThat(org.isInstructionFailed()).isTrue();
        assertThat(org.getFailureReason()).contains("Unknown opcode");
    }

    /**
     * Verifies the basic energy consumption logic, ensuring that an organism
     * with minimal energy dies after executing instructions.
     * This is a unit test for the organism's lifecycle.
     */
    @Test
    @Tag("unit")
    void testEnergyDecreasesAndDeath() {
        // Start with small energy; execute NOP until dead
        Organism org = Organism.create(sim, new int[]{0, 0}, 2, sim.getLogger());
        sim.addOrganism(org);
        int nopId = Instruction.getInstructionIdByName("NOP");
        environment.setMolecule(new Molecule(Config.TYPE_CODE, nopId), org.getIp());

        // Two ticks should drain energy to <= 0 and mark dead
        sim.tick();
        sim.tick();

        assertThat(org.isDead()).isTrue();
        assertThat(org.isInstructionFailed()).isTrue();
        assertThat(org.getFailureReason()).contains("Ran out of energy");
    }

    /**
     * Verifies that the organism's Instruction Pointer (IP) correctly advances
     * along its Direction Vector (DV) after each simulation tick.
     * This is a unit test for organism movement.
     */
    @Test
    @Tag("unit")
    void testIpAdvancesAlongDv() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 10, sim.getLogger());
        sim.addOrganism(org);
        // Move along +X
        org.setDv(new int[]{1, 0});
        int nopId = Instruction.getInstructionIdByName("NOP");
        // Place NOP at [0,0] and [1,0]
        environment.setMolecule(new Molecule(Config.TYPE_CODE, nopId), new int[]{0, 0});
        environment.setMolecule(new Molecule(Config.TYPE_CODE, nopId), new int[]{1, 0});

        assertThat(org.getIp()).isEqualTo(new int[]{0, 0});
        sim.tick();
        assertThat(org.getIp()).isEqualTo(new int[]{1, 0});
        sim.tick();
        assertThat(org.getIp()).isEqualTo(new int[]{2, 0});
    }

    /**
     * Verifies that the helper method for calculating a target coordinate correctly
     * uses the Data Pointer (DP) as its base.
     * This is a unit test for organism coordinate calculations.
     */
    @Test
    @Tag("unit")
    void testGetTargetCoordinateFromDp() {
        Organism org = Organism.create(sim, new int[]{10, 10}, 100, sim.getLogger());
        sim.addOrganism(org);
        // Set DP to somewhere else to ensure DP is used
        org.setDp(0, new int[]{5, 5}); // CORRECTED
        int[] target = org.getTargetCoordinate(org.getDp(0), new int[]{0, 1}, environment); // CORRECTED
        assertThat(target).isEqualTo(new int[]{5, 6});
    }

    /**
     * Verifies the LIFO (Last-In, First-Out) behavior of the organism's data stack.
     * This is a unit test for the organism's internal data structures.
     */
    @Test
    @Tag("unit")
    void testDataStackPushPopOrder() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 100, sim.getLogger());
        Deque<Object> ds = org.getDataStack();
        int a = new Molecule(Config.TYPE_DATA, 1).toInt();
        int b = new Molecule(Config.TYPE_DATA, 2).toInt();

        ds.push(a);
        ds.push(b);

        assertThat(ds.pop()).isEqualTo(b);
        assertThat(ds.pop()).isEqualTo(a);
        assertThat(ds.isEmpty()).isTrue();
    }

    /**
     * Verifies the basic set and get functionality for all main register types:
     * Data Registers (DR), Pointer Registers (PR), and Formal Parameter Registers (FPR).
     * This is a unit test for the organism's register state management.
     */
    @Test
    @Tag("unit")
    void testRegisterAccessDrPrFpr() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 100, sim.getLogger());

        // DR
        int dataVal = new Molecule(Config.TYPE_DATA, 42).toInt();
        org.setDr(0, dataVal);
        assertThat(org.getDr(0)).isEqualTo(dataVal);

        // PR
        org.setPr(0, dataVal);
        assertThat(org.getPr(0)).isEqualTo(dataVal);

        // FPR
        int[] vec = new int[]{3, 4};
        org.setFpr(0, vec);
        assertThat(org.getFpr(0)).isEqualTo(vec);
    }
}