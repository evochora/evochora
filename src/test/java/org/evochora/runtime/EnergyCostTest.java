package org.evochora.runtime;

import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.Environment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the energy consumption and harvesting logic for various instructions.
 * These tests use an in-memory {@link Simulation} and {@link Environment} to verify
 * that instructions like PEEK and POKE affect an organism's energy as expected.
 * These are unit tests and do not require external resources.
 */
public class EnergyCostTest {

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

    private void placeInstruction(Organism org, String name, Integer... args) {
        int opcode = Instruction.getInstructionIdByName(name);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, opcode), org.getIp());
        int[] currentPos = org.getIp();
        for (int arg : args) {
            // CORRECTED: Parameter order for getNextInstructionPosition
            currentPos = org.getNextInstructionPosition(currentPos, org.getDv(), environment);
            environment.setMolecule(new Molecule(Config.TYPE_DATA, arg), currentPos);
        }
    }

    /**
     * Verifies that a successful POKE instruction consumes the correct amount of energy.
     * This is a unit test using an in-memory simulation.
     */
    @Test
    @Tag("unit")
    void testPokeConsumesEnergyOnSuccess() {
        Organism org = Organism.create(sim, new int[]{10, 10}, 1000, sim.getLogger());
        sim.addOrganism(org);
        org.setDp(0, org.getIp()); // CORRECTED: Use DP 0

        int[] vec = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(0), vec, environment); // CORRECTED: Use DP 0

        // ensure target is empty
        environment.setMolecule(new Molecule(Config.TYPE_CODE, 0), target);

        int payload = new Molecule(Config.TYPE_DATA, 50).toInt();
        org.setDr(0, payload);      // value register
        org.setDr(1, vec);          // vector register

        int initialEr = org.getEr();

        placeInstruction(org, "POKE", 0, 1);
        sim.tick();

        assertThat(org.isInstructionFailed()).as("POKE should succeed on empty cell").isFalse();
        assertThat(environment.getMolecule(target).toInt()).isEqualTo(payload);
        // POKE(DATA) costs base 1 + 5
        assertThat(org.getEr()).isLessThanOrEqualTo(initialEr - 1 - 5);
    }

    /**
     * Verifies that a failed POKE instruction (due to an occupied target cell)
     * still consumes the base energy cost for the instruction attempt.
     * This is a unit test using an in-memory simulation.
     */
    @Test
    @Tag("unit")
    void testPokeConsumesEnergyEvenOnOccupiedTarget() {
        Organism org = Organism.create(sim, new int[]{20, 20}, 1000, sim.getLogger());
        sim.addOrganism(org);
        org.setDp(0, org.getIp()); // CORRECTED: Use DP 0

        int[] vec = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(0), vec, environment); // CORRECTED: Use DP 0

        // Make target occupied
        environment.setMolecule(new Molecule(Config.TYPE_DATA, 1), target);

        int payload = new Molecule(Config.TYPE_DATA, 60).toInt();
        org.setDr(0, payload);
        org.setDr(1, vec);

        int initialEr = org.getEr();

        placeInstruction(org, "POKE", 0, 1);
        sim.tick();

        assertThat(org.isInstructionFailed()).as("POKE should fail on occupied cell").isTrue();
        // With new model we charge during PEEK consumption logic; for POKE we charge base+type only if executed
        // Occupied cell -> no execution -> no additional type cost
        assertThat(org.getEr()).isLessThanOrEqualTo(initialEr - 1);
        // Target content should remain unchanged due to failure
        assertThat(environment.getMolecule(target).toInt()).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
    }

    /**
     * Verifies that peeking a data molecule from a foreign cell consumes energy.
     * This is a unit test using an in-memory simulation.
     */
    @Test
    @Tag("unit")
    void testPeekDataConsumesEnergy() {
        Organism org = Organism.create(sim, new int[]{30, 30}, 1000, sim.getLogger());
        sim.addOrganism(org);
        org.setDp(0, org.getIp()); // CORRECTED: Use DP 0

        int[] vec = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(0), vec, environment); // CORRECTED: Use DP 0

        int dataVal = new Molecule(Config.TYPE_DATA, 33).toInt();
        environment.setMolecule(Molecule.fromInt(dataVal), target);

        org.setDr(1, vec); // vector register
        int initialEr = org.getEr();

        placeInstruction(org, "PEEK", 0, 1); // store into DR0
        sim.tick();

        assertThat(org.isInstructionFailed()).isFalse();
        assertThat(org.getDr(0)).isEqualTo(dataVal);
        // PEEK(DATA) foreign costs +5
        assertThat(org.getEr()).isLessThanOrEqualTo(initialEr - 5);
    }

    /**
     * Verifies that peeking a structure molecule owned by the organism itself does not cost energy.
     * This is a unit test using an in-memory simulation.
     */
    @Test
    @Tag("unit")
    void testPeekStructureOwnedBySelf_NoEnergyCost() {
        Organism org = Organism.create(sim, new int[]{40, 40}, 1000, sim.getLogger());
        sim.addOrganism(org);
        org.setDp(0, org.getIp()); // CORRECTED: Use DP 0

        int[] vec = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(0), vec, environment); // CORRECTED: Use DP 0

        int structVal = new Molecule(Config.TYPE_STRUCTURE, 10).toInt();
        environment.setMolecule(Molecule.fromInt(structVal), target);
        environment.setOwnerId(org.getId(), target[0], target[1]);

        org.setDr(1, vec);
        int initialEr = org.getEr();

        placeInstruction(org, "PEEK", 0, 1);
        sim.tick();

        assertThat(org.isInstructionFailed()).isFalse();
        assertThat(org.getDr(0)).isEqualTo(structVal);
        // No cost on self-owned structure; allow small per-tick overhead tolerance
        assertThat(org.getEr()).isGreaterThanOrEqualTo(initialEr - 1);
        assertThat(org.getEr()).isLessThanOrEqualTo(initialEr);
    }

    /**
     * Verifies that peeking a structure molecule owned by a different organism costs energy.
     * This is a unit test using an in-memory simulation.
     */
    @Test
    @Tag("unit")
    void testPeekStructureForeignConsumesEnergy() {
        Organism org = Organism.create(sim, new int[]{50, 50}, 1000, sim.getLogger());
        sim.addOrganism(org);
        org.setDp(0, org.getIp()); // CORRECTED: Use DP 0

        int[] vec = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(0), vec, environment); // CORRECTED: Use DP 0

        int structVal = new Molecule(Config.TYPE_STRUCTURE, 12).toInt();
        environment.setMolecule(Molecule.fromInt(structVal), target);
        environment.setOwnerId(org.getId() + 999, target[0], target[1]); // foreign owner

        org.setDr(1, vec);
        int initialEr = org.getEr();

        placeInstruction(org, "PEEK", 0, 1);
        sim.tick();

        assertThat(org.isInstructionFailed()).isFalse();
        assertThat(org.getDr(0)).isEqualTo(structVal);
        // Must charge |12| energy (allow per-tick overhead)
        assertThat(org.getEr()).isLessThanOrEqualTo(initialEr - 12);
    }

    /**
     * Verifies that peeking an energy molecule correctly harvests energy and that the
     * organism's total energy is clamped to its maximum capacity.
     * This is a unit test using an in-memory simulation.
     */
    @Test
    @Tag("unit")
    void testPeekEnergyHarvestsAndClampsToMax() {
        Organism org = Organism.create(sim, new int[]{60, 60}, 100, sim.getLogger());
        sim.addOrganism(org);
        org.setDp(0, org.getIp()); // CORRECTED: Use DP 0

        int[] vec = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(0), vec, environment); // CORRECTED: Use DP 0

        int energyAvailable = 80;
        environment.setMolecule(new Molecule(Config.TYPE_ENERGY, energyAvailable), target);

        org.setDr(1, vec);
        int initialEr = org.getEr();
        int expectedHarvest = Math.min(energyAvailable, Config.MAX_ORGANISM_ENERGY - initialEr);

        placeInstruction(org, "PEEK", 0, 1);
        sim.tick();

        assertThat(org.isInstructionFailed()).isFalse();
        // Final ER should be within [initial + harvest - 1, initial + harvest] to tolerate small per-tick overhead
        assertThat(org.getEr()).isGreaterThanOrEqualTo(initialEr + expectedHarvest - 1);
        assertThat(org.getEr()).isLessThanOrEqualTo(initialEr + expectedHarvest);
    }
}