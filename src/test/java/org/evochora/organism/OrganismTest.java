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

import java.util.Deque;

import static org.assertj.core.api.Assertions.assertThat;

public class OrganismTest {

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

    @Test
    void testPlanTickStrictTypingOnNonCodeCell() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 100, sim.getLogger());
        sim.addOrganism(org);
        // Place a DATA symbol at IP to violate strict typing
        world.setMolecule(new Molecule(Config.TYPE_DATA, 1), org.getIp());

        Instruction planned = org.planTick(world);
        assertThat(planned).isNotNull();
        // Planner yields a no-op placeholder with name "UNKNOWN" for illegal type
        assertThat(planned.getName()).isEqualTo("UNKNOWN");
        assertThat(org.isInstructionFailed()).isTrue();
        assertThat(org.getFailureReason()).contains("Illegal cell type");
    }

    @Test
    void testPlanTickUnknownOpcodeProducesNop() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 100, sim.getLogger());
        sim.addOrganism(org);
        // Place a CODE opcode that doesn't exist (e.g., 999)
        world.setMolecule(new Molecule(Config.TYPE_CODE, 999), org.getIp());

        Instruction planned = org.planTick(world);
        assertThat(planned).isNotNull();
        // Planner yields a no-op placeholder with name "UNKNOWN" for unknown opcode
        assertThat(planned.getName()).isEqualTo("UNKNOWN");
        assertThat(org.isInstructionFailed()).isTrue();
        assertThat(org.getFailureReason()).contains("Unknown opcode");
    }

    @Test
    void testEnergyDecreasesAndDeath() {
        // Start with small energy; execute NOP until dead
        Organism org = Organism.create(sim, new int[]{0, 0}, 2, sim.getLogger());
        sim.addOrganism(org);
        int nopId = Instruction.getInstructionIdByName("NOP");
        world.setMolecule(new Molecule(Config.TYPE_CODE, nopId), org.getIp());

        // Two ticks should drain energy to <= 0 and mark dead
        sim.tick();
        sim.tick();

        assertThat(org.isDead()).isTrue();
        assertThat(org.isInstructionFailed()).isTrue();
        assertThat(org.getFailureReason()).contains("Ran out of energy");
    }

    @Test
    void testIpAdvancesAlongDv() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 10, sim.getLogger());
        sim.addOrganism(org);
        // Move along +X
        org.setDv(new int[]{1, 0});
        int nopId = Instruction.getInstructionIdByName("NOP");
        // Place NOP at [0,0] and [1,0]
        world.setMolecule(new Molecule(Config.TYPE_CODE, nopId), new int[]{0, 0});
        world.setMolecule(new Molecule(Config.TYPE_CODE, nopId), new int[]{1, 0});

        assertThat(org.getIp()).isEqualTo(new int[]{0, 0});
        sim.tick();
        assertThat(org.getIp()).isEqualTo(new int[]{1, 0});
        sim.tick();
        assertThat(org.getIp()).isEqualTo(new int[]{2, 0});
    }

    @Test
    void testGetTargetCoordinateFromDp() {
        Organism org = Organism.create(sim, new int[]{10, 10}, 100, sim.getLogger());
        sim.addOrganism(org);
        // Set DP to somewhere else to ensure DP is used
        org.setDp(new int[]{5, 5});
        int[] target = org.getTargetCoordinate(org.getDp(), new int[]{0, 1}, world);
        assertThat(target).isEqualTo(new int[]{5, 6});
    }

    @Test
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

    @Test
    void testRegisterAccessDrPrFpr() {
        Organism org = Organism.create(sim, new int[]{0, 0}, 100, sim.getLogger());

        // DR
        int dataVal = new Molecule(Config.TYPE_DATA, 42).toInt();
        org.setDr(0, dataVal);
        assertThat(org.getDr(0)).isEqualTo(dataVal);

        // PR (procedure register) via snapshot/restore helpers: set/get directly if available
        // Here we use set/get by PR index through read/writeOperand routes; however, Organism exposes PRs as a list
        org.setPr(0, dataVal);
        assertThat(org.getPr(0)).isEqualTo(dataVal);

        // FPR stores Objects (e.g., vectors)
        int[] vec = new int[]{3, 4};
        org.setFpr(0, vec);
        assertThat(org.getFpr(0)).isEqualTo(vec);
    }
}
