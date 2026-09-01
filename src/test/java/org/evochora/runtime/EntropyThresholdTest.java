package org.evochora.runtime;

import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.test.utils.SimulationTestUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks where the entropy limit kills.
 * <p>
 * The assembly specification and the scientific overview both say an organism dies when its
 * entropy <em>exceeds</em> the maximum, so an organism that ends a tick exactly at the maximum
 * has to survive it.
 */
@Tag("unit")
class EntropyThresholdTest {

    private Environment environment;
    private Simulation sim;
    private int maxEntropy;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        environment = new Environment(new int[]{100, 100}, true);
        sim = SimulationTestUtils.createSimulation(environment);
        maxEntropy = sim.getOrganismConfig().getInt("max-entropy");
    }

    /** Places a NOP at the organism's instruction pointer and starts it at the given entropy. */
    private Organism organismAtEntropy(int entropy) {
        Organism org = Organism.create(sim, new int[]{10, 10}, 1_000_000);
        sim.addOrganism(org);
        environment.setMolecule(
                new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("NOP")), org.getIp());
        org.addSr(entropy - org.getSr());
        return org;
    }

    /**
     * Entropy the tick itself adds, measured rather than assumed: the limit is checked after the
     * instruction has run, so a test that wants to end exactly at the maximum has to start below it.
     */
    private int entropyPerTick() {
        Organism probe = organismAtEntropy(0);
        sim.tick();
        int delta = probe.getSr();
        probe.kill("probe done");
        return delta;
    }

    @Test
    void endingATickExactlyAtTheMaximumIsSurvivable() {
        int perTick = entropyPerTick();
        Organism org = organismAtEntropy(maxEntropy - perTick);

        sim.tick();

        assertThat(org.getSr()).isEqualTo(maxEntropy);
        assertThat(org.isDead())
                .as("reaching max-entropy must not kill: the specification says the limit has to be exceeded")
                .isFalse();
    }

    @Test
    void goingOverTheMaximumKills() {
        int perTick = entropyPerTick();
        Organism org = organismAtEntropy(maxEntropy - perTick + 1);

        sim.tick();

        assertThat(org.isDead()).isTrue();
    }
}
