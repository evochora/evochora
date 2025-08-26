package org.evochora.runtime;

import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;

public class OwnershipTests {

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
            currentPos = org.getNextInstructionPosition(currentPos, org.getDv(), environment); // CORRECTED
            environment.setMolecule(new Molecule(Config.TYPE_DATA, arg), currentPos);
        }
    }

    private void placeInstructionWithVector(Organism org, String name, int[] vector) {
        int opcode = Instruction.getInstructionIdByName(name);
        environment.setMolecule(new Molecule(Config.TYPE_CODE, opcode), org.getIp());
        int[] currentPos = org.getIp();
        for (int val : vector) {
            currentPos = org.getNextInstructionPosition(currentPos, org.getDv(), environment); // CORRECTED
            environment.setMolecule(new Molecule(Config.TYPE_DATA, val), currentPos);
        }
    }

    @Test
    @Tag("unit")
    void testSeekVariants_SucceedOnOwnedNonEmptyCell() {
        int[] vec = new int[]{0, 1};
        int payload = new Molecule(Config.TYPE_DATA, 42).toInt();

        // SEEK (register-based vector)
        {
            Organism org = Organism.create(sim, new int[]{10, 10}, 2000, sim.getLogger());
            sim.addOrganism(org);
            org.setDp(0, org.getIp()); // CORRECTED

            int[] target = org.getTargetCoordinate(org.getDp(0), vec, environment); // CORRECTED
            environment.setMolecule(Molecule.fromInt(payload), target);
            environment.setOwnerId(org.getId(), target[0], target[1]);

            org.setDr(1, vec);
            placeInstruction(org, "SEEK", 1);

            sim.tick();

            assertThat(org.isInstructionFailed()).as("SEEK should succeed on owned, non-empty cell").isFalse();
            assertThat(org.getDp(0)).isEqualTo(target); // CORRECTED
        }

        // SEKI (immediate vector)
        {
            Organism org = Organism.create(sim, new int[]{20, 20}, 2000, sim.getLogger());
            sim.addOrganism(org);
            org.setDp(0, org.getIp()); // CORRECTED

            int[] target = org.getTargetCoordinate(org.getDp(0), vec, environment); // CORRECTED
            environment.setMolecule(Molecule.fromInt(payload), target);
            environment.setOwnerId(org.getId(), target[0], target[1]);

            placeInstructionWithVector(org, "SEKI", vec);

            sim.tick();

            assertThat(org.isInstructionFailed()).as("SEKI should succeed on owned, non-empty cell").isFalse();
            assertThat(org.getDp(0)).isEqualTo(target); // CORRECTED
        }

        // SEKS (stack-based vector)
        {
            Organism org = Organism.create(sim, new int[]{30, 30}, 2000, sim.getLogger());
            sim.addOrganism(org);
            org.setDp(0, org.getIp()); // CORRECTED

            int[] target = org.getTargetCoordinate(org.getDp(0), vec, environment); // CORRECTED
            environment.setMolecule(Molecule.fromInt(payload), target);
            environment.setOwnerId(org.getId(), target[0], target[1]);

            org.getDataStack().push(vec);
            placeInstruction(org, "SEKS");

            sim.tick();

            assertThat(org.isInstructionFailed()).as("SEKS should succeed on owned, non-empty cell").isFalse();
            assertThat(org.getDp(0)).isEqualTo(target); // CORRECTED
        }
    }

    @Test
    @Tag("unit")
    void testSeekVariants_FailOnForeignOwnedCell() {
        int[] vec = new int[]{0, 1};
        int payload = new Molecule(Config.TYPE_DATA, 77).toInt();

        // SEEK failure on foreign-owned
        {
            Organism org = Organism.create(sim, new int[]{40, 10}, 2000, sim.getLogger());
            sim.addOrganism(org);
            org.setDp(0, org.getIp()); // CORRECTED

            int[] target = org.getTargetCoordinate(org.getDp(0), vec, environment); // CORRECTED
            environment.setMolecule(Molecule.fromInt(payload), target);
            environment.setOwnerId(org.getId() + 1, target[0], target[1]);

            org.setDr(1, vec);
            placeInstruction(org, "SEEK", 1);

            sim.tick();

            assertThat(org.isInstructionFailed()).as("SEEK should fail on foreign-owned, non-empty cell").isTrue();
            assertThat(org.getDp(0)).as("DP must not move on failure").isEqualTo(new int[]{40, 10}); // CORRECTED
        }

        // SEKI failure on foreign-owned
        {
            Organism org = Organism.create(sim, new int[]{50, 20}, 2000, sim.getLogger());
            sim.addOrganism(org);
            org.setDp(0, org.getIp()); // CORRECTED

            int[] target = org.getTargetCoordinate(org.getDp(0), vec, environment); // CORRECTED
            environment.setMolecule(Molecule.fromInt(payload), target);
            environment.setOwnerId(org.getId() + 2, target[0], target[1]);

            placeInstructionWithVector(org, "SEKI", vec);

            sim.tick();

            assertThat(org.isInstructionFailed()).as("SEKI should fail on foreign-owned, non-empty cell").isTrue();
            assertThat(org.getDp(0)).as("DP must not move on failure").isEqualTo(new int[]{50, 20}); // CORRECTED
        }

        // SEKS failure on foreign-owned
        {
            Organism org = Organism.create(sim, new int[]{60, 30}, 2000, sim.getLogger());
            sim.addOrganism(org);
            org.setDp(0, org.getIp()); // CORRECTED

            int[] target = org.getTargetCoordinate(org.getDp(0), vec, environment); // CORRECTED
            environment.setMolecule(Molecule.fromInt(payload), target);
            environment.setOwnerId(org.getId() + 3, target[0], target[1]);

            org.getDataStack().push(vec);
            placeInstruction(org, "SEKS");

            sim.tick();

            assertThat(org.isInstructionFailed()).as("SEKS should fail on foreign-owned, non-empty cell").isTrue();
            assertThat(org.getDp(0)).as("DP must not move on failure").isEqualTo(new int[]{60, 30}); // CORRECTED
        }
    }
}