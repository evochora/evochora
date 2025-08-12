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

public class OwnershipTests {

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

    private void placeInstructionWithVector(Organism org, String name, int[] vector) {
        int opcode = Instruction.getInstructionIdByName(name);
        world.setMolecule(new Molecule(Config.TYPE_CODE, opcode), org.getIp());
        int[] currentPos = org.getIp();
        for (int val : vector) {
            currentPos = org.getNextInstructionPosition(currentPos, world, org.getDv());
            world.setMolecule(new Molecule(Config.TYPE_DATA, val), currentPos);
        }
    }

    @Test
    void testSeekVariants_SucceedOnOwnedNonEmptyCell() {
        int[] vec = new int[]{0, 1};
        int payload = new Molecule(Config.TYPE_DATA, 42).toInt();

        // SEEK (register-based vector)
        {
            Organism org = Organism.create(sim, new int[]{10, 10}, 2000, sim.getLogger());
            sim.addOrganism(org);
            org.setDp(org.getIp());

            int[] target = org.getTargetCoordinate(org.getDp(), vec, world);
            world.setMolecule(Molecule.fromInt(payload), target);
            world.setOwnerId(org.getId(), target[0], target[1]);

            org.setDr(1, vec);
            placeInstruction(org, "SEEK", 1);

            sim.tick();

            assertThat(org.isInstructionFailed()).as("SEEK should succeed on owned, non-empty cell").isFalse();
            assertThat(org.getDp()).isEqualTo(target);
        }

        // SEKI (immediate vector)
        {
            Organism org = Organism.create(sim, new int[]{20, 20}, 2000, sim.getLogger());
            sim.addOrganism(org);
            org.setDp(org.getIp());

            int[] target = org.getTargetCoordinate(org.getDp(), vec, world);
            world.setMolecule(Molecule.fromInt(payload), target);
            world.setOwnerId(org.getId(), target[0], target[1]);

            placeInstructionWithVector(org, "SEKI", vec);

            sim.tick();

            assertThat(org.isInstructionFailed()).as("SEKI should succeed on owned, non-empty cell").isFalse();
            assertThat(org.getDp()).isEqualTo(target);
        }

        // SEKS (stack-based vector)
        {
            Organism org = Organism.create(sim, new int[]{30, 30}, 2000, sim.getLogger());
            sim.addOrganism(org);
            org.setDp(org.getIp());

            int[] target = org.getTargetCoordinate(org.getDp(), vec, world);
            world.setMolecule(Molecule.fromInt(payload), target);
            world.setOwnerId(org.getId(), target[0], target[1]);

            org.getDataStack().push(vec);
            placeInstruction(org, "SEKS");

            sim.tick();

            assertThat(org.isInstructionFailed()).as("SEKS should succeed on owned, non-empty cell").isFalse();
            assertThat(org.getDp()).isEqualTo(target);
        }
    }

    @Test
    void testSeekVariants_FailOnForeignOwnedCell() {
        int[] vec = new int[]{0, 1};
        int payload = new Molecule(Config.TYPE_DATA, 77).toInt();

        // SEEK failure on foreign-owned
        {
            Organism org = Organism.create(sim, new int[]{40, 10}, 2000, sim.getLogger());
            sim.addOrganism(org);
            org.setDp(org.getIp());

            int[] target = org.getTargetCoordinate(org.getDp(), vec, world);
            world.setMolecule(Molecule.fromInt(payload), target);
            // Set foreign owner (different from org.getId())
            world.setOwnerId(org.getId() + 1, target[0], target[1]);

            org.setDr(1, vec);
            placeInstruction(org, "SEEK", 1);

            sim.tick();

            assertThat(org.isInstructionFailed()).as("SEEK should fail on foreign-owned, non-empty cell").isTrue();
            assertThat(org.getDp()).as("DP must not move on failure").isEqualTo(new int[]{40, 10});
        }

        // SEKI failure on foreign-owned
        {
            Organism org = Organism.create(sim, new int[]{50, 20}, 2000, sim.getLogger());
            sim.addOrganism(org);
            org.setDp(org.getIp());

            int[] target = org.getTargetCoordinate(org.getDp(), vec, world);
            world.setMolecule(Molecule.fromInt(payload), target);
            world.setOwnerId(org.getId() + 2, target[0], target[1]);

            placeInstructionWithVector(org, "SEKI", vec);

            sim.tick();

            assertThat(org.isInstructionFailed()).as("SEKI should fail on foreign-owned, non-empty cell").isTrue();
            assertThat(org.getDp()).as("DP must not move on failure").isEqualTo(new int[]{50, 20});
        }

        // SEKS failure on foreign-owned
        {
            Organism org = Organism.create(sim, new int[]{60, 30}, 2000, sim.getLogger());
            sim.addOrganism(org);
            org.setDp(org.getIp());

            int[] target = org.getTargetCoordinate(org.getDp(), vec, world);
            world.setMolecule(Molecule.fromInt(payload), target);
            world.setOwnerId(org.getId() + 3, target[0], target[1]);

            org.getDataStack().push(vec);
            placeInstruction(org, "SEKS");

            sim.tick();

            assertThat(org.isInstructionFailed()).as("SEKS should fail on foreign-owned, non-empty cell").isTrue();
            assertThat(org.getDp()).as("DP must not move on failure").isEqualTo(new int[]{60, 30});
        }
    }
}
