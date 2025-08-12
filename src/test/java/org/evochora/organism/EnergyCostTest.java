package org.evochora.organism;

import org.evochora.app.setup.Config;
import org.evochora.app.Simulation;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class EnergyCostTest {

    private World world;
    private Simulation sim;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        world = new World(new int[]{100, 100}, true);
        sim = new Simulation(world);
    }

    private void placeInstruction(Organism org, String name, Integer... args) {
        int opcode = Instruction.getInstructionIdByName(name);
        world.setMolecule(new Molecule(Config.TYPE_CODE, opcode), org.getIp());
        int[] currentPos = org.getIp();
        for (int arg : args) {
            currentPos = org.getNextInstructionPosition(currentPos, world, org.getDv());
            world.setMolecule(new Molecule(Config.TYPE_DATA, arg), currentPos);
        }
    }

    @Test
    void testPokeConsumesEnergyOnSuccess() {
        Organism org = Organism.create(sim, new int[]{10, 10}, 1000, sim.getLogger());
        sim.addOrganism(org);
        org.setDp(org.getIp());

        int[] vec = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(), vec, world);

        // ensure target is empty
        world.setMolecule(new Molecule(Config.TYPE_CODE, 0), target);

        int payload = new Molecule(Config.TYPE_DATA, 50).toInt();
        org.setDr(0, payload);      // value register
        org.setDr(1, vec);          // vector register

        int initialEr = org.getEr();

        placeInstruction(org, "POKE", 0, 1);
        sim.tick();

        assertThat(org.isInstructionFailed()).as("POKE should succeed on empty cell").isFalse();
        assertThat(world.getMolecule(target).toInt()).isEqualTo(payload);
        // Energy must be reduced by at least abs(payload scalar); allow extra per-tick overhead.
        assertThat(org.getEr()).isLessThanOrEqualTo(initialEr - 50);
    }

    @Test
    void testPokeConsumesEnergyEvenOnOccupiedTarget() {
        Organism org = Organism.create(sim, new int[]{20, 20}, 1000, sim.getLogger());
        sim.addOrganism(org);
        org.setDp(org.getIp());

        int[] vec = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(), vec, world);

        // Make target occupied
        world.setMolecule(new Molecule(Config.TYPE_DATA, 1), target);

        int payload = new Molecule(Config.TYPE_DATA, 60).toInt();
        org.setDr(0, payload);
        org.setDr(1, vec);

        int initialEr = org.getEr();

        placeInstruction(org, "POKE", 0, 1);
        sim.tick();

        assertThat(org.isInstructionFailed()).as("POKE should fail on occupied cell").isTrue();
        // Energy should have been consumed despite failure
        assertThat(org.getEr()).isLessThanOrEqualTo(initialEr - 60);
        // Target content should remain unchanged due to failure
        assertThat(world.getMolecule(target).toInt()).isEqualTo(new Molecule(Config.TYPE_DATA, 1).toInt());
    }

    @Test
    void testPeekDataConsumesEnergy() {
        Organism org = Organism.create(sim, new int[]{30, 30}, 1000, sim.getLogger());
        sim.addOrganism(org);
        org.setDp(org.getIp());

        int[] vec = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(), vec, world);

        int dataVal = new Molecule(Config.TYPE_DATA, 33).toInt();
        world.setMolecule(Molecule.fromInt(dataVal), target);

        org.setDr(1, vec); // vector register
        int initialEr = org.getEr();

        placeInstruction(org, "PEEK", 0, 1); // store into DR0
        sim.tick();

        assertThat(org.isInstructionFailed()).isFalse();
        assertThat(org.getDr(0)).isEqualTo(dataVal);
        // Must consume at least |33| energy (allow per-tick overhead)
        assertThat(org.getEr()).isLessThanOrEqualTo(initialEr - 33);
    }

    @Test
    void testPeekStructureOwnedBySelf_NoEnergyCost() {
        Organism org = Organism.create(sim, new int[]{40, 40}, 1000, sim.getLogger());
        sim.addOrganism(org);
        org.setDp(org.getIp());

        int[] vec = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(), vec, world);

        int structVal = new Molecule(Config.TYPE_STRUCTURE, 10).toInt();
        world.setMolecule(Molecule.fromInt(structVal), target);
        world.setOwnerId(org.getId(), target[0], target[1]);

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

    @Test
    void testPeekStructureForeignConsumesEnergy() {
        Organism org = Organism.create(sim, new int[]{50, 50}, 1000, sim.getLogger());
        sim.addOrganism(org);
        org.setDp(org.getIp());

        int[] vec = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(), vec, world);

        int structVal = new Molecule(Config.TYPE_STRUCTURE, 12).toInt();
        world.setMolecule(Molecule.fromInt(structVal), target);
        world.setOwnerId(org.getId() + 999, target[0], target[1]); // foreign owner

        org.setDr(1, vec);
        int initialEr = org.getEr();

        placeInstruction(org, "PEEK", 0, 1);
        sim.tick();

        assertThat(org.isInstructionFailed()).isFalse();
        assertThat(org.getDr(0)).isEqualTo(structVal);
        // Must charge |12| energy (allow per-tick overhead)
        assertThat(org.getEr()).isLessThanOrEqualTo(initialEr - 12);
    }

    @Test
    void testPeekEnergyHarvestsAndClampsToMax() {
        Organism org = Organism.create(sim, new int[]{60, 60}, 100, sim.getLogger());
        sim.addOrganism(org);
        org.setDp(org.getIp());

        int[] vec = new int[]{0, 1};
        int[] target = org.getTargetCoordinate(org.getDp(), vec, world);

        int energyAvailable = 80;
        world.setMolecule(new Molecule(Config.TYPE_ENERGY, energyAvailable), target);

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
