package org.evochora.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.thermodynamics.ThermodynamicPolicyManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.ConfigFactory;

/**
 * Unit tests for {@link Simulation#forResume(Environment, long, long, ThermodynamicPolicyManager, com.typesafe.config.Config)}.
 * <p>
 * Tests the factory method used for resuming simulations from checkpoints.
 */
class SimulationResumeTest {

    private Environment environment;
    private ThermodynamicPolicyManager policyManager;
    private com.typesafe.config.Config organismConfig;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        environment = new Environment(new int[]{100, 100}, true);

        // Minimal thermodynamic config for testing
        String thermoConfigStr = """
            default {
              className = "org.evochora.runtime.thermodynamics.impl.UniversalThermodynamicPolicy"
              options {
                base-energy = 1
                base-entropy = 1
              }
            }
            overrides {
              instructions {}
              families {}
            }
            """;
        policyManager = new ThermodynamicPolicyManager(ConfigFactory.parseString(thermoConfigStr));

        organismConfig = ConfigFactory.parseMap(Map.of(
            "max-energy", 32767,
            "max-entropy", 8191,
            "error-penalty-cost", 10
        ));
    }

    // ==================== Basic Factory Method Tests ====================

    @Test
    @Tag("unit")
    void testForResume_SetsCurrentTick() {
        Simulation sim = Simulation.forResume(
            environment,
            5000L,      // currentTick
            100L,       // totalOrganismsCreated
            policyManager,
            organismConfig
        );

        assertThat(sim.getCurrentTick()).isEqualTo(5000L);
    }

    @Test
    @Tag("unit")
    void testForResume_SetsNextOrganismId() {
        Simulation sim = Simulation.forResume(
            environment,
            1000L,
            50L,        // totalOrganismsCreated
            policyManager,
            organismConfig
        );

        // Next organism ID should be totalOrganismsCreated + 1
        int nextId = sim.getNextOrganismId();
        assertThat(nextId).isEqualTo(51);

        // Subsequent call should increment
        int nextId2 = sim.getNextOrganismId();
        assertThat(nextId2).isEqualTo(52);
    }

    @Test
    @Tag("unit")
    void testForResume_EnvironmentIsSet() {
        Simulation sim = Simulation.forResume(
            environment,
            0L,
            0L,
            policyManager,
            organismConfig
        );

        assertThat(sim.getEnvironment()).isSameAs(environment);
    }

    @Test
    @Tag("unit")
    void testForResume_PolicyManagerIsSet() {
        Simulation sim = Simulation.forResume(
            environment,
            0L,
            0L,
            policyManager,
            organismConfig
        );

        assertThat(sim.getPolicyManager()).isSameAs(policyManager);
    }

    @Test
    @Tag("unit")
    void testForResume_OrganismConfigIsSet() {
        Simulation sim = Simulation.forResume(
            environment,
            0L,
            0L,
            policyManager,
            organismConfig
        );

        assertThat(sim.getOrganismConfig()).isSameAs(organismConfig);
    }

    @Test
    @Tag("unit")
    void testForResume_StartsWithNoOrganisms() {
        Simulation sim = Simulation.forResume(
            environment,
            1000L,
            50L,
            policyManager,
            organismConfig
        );

        assertThat(sim.getOrganisms()).isEmpty();
    }

    @Test
    @Tag("unit")
    void testForResume_StartsWithNoTickPlugins() {
        Simulation sim = Simulation.forResume(
            environment,
            1000L,
            50L,
            policyManager,
            organismConfig
        );

        assertThat(sim.getTickPlugins()).isEmpty();
    }

    // ==================== Integration Tests ====================

    @Test
    @Tag("unit")
    void testForResume_CanAddOrganisms() {
        Simulation sim = Simulation.forResume(
            environment,
            1000L,
            50L,
            policyManager,
            organismConfig
        );

        // Use RestoreBuilder to create an organism
        Organism org = Organism.restore(25, 500L)
            .ip(new int[]{10, 10})
            .dv(new int[]{1, 0})
            .energy(100)
            .build(sim);

        sim.addOrganism(org);

        assertThat(sim.getOrganisms()).hasSize(1);
        assertThat(sim.getOrganisms().get(0).getId()).isEqualTo(25);
    }

    @Test
    @Tag("unit")
    void testForResume_NewOrganismsGetCorrectIds() {
        Simulation sim = Simulation.forResume(
            environment,
            1000L,
            50L,  // 50 organisms were created in original run
            policyManager,
            organismConfig
        );

        // Create a new organism using normal factory (as if born after resume)
        Organism newOrg = Organism.create(sim, new int[]{0, 0}, 100, sim.getLogger());

        // New organism should get ID 51 (next after 50)
        assertThat(newOrg.getId()).isEqualTo(51);
    }

    @Test
    @Tag("unit")
    void testForResume_TotalOrganismsCountReflectsResumedState() {
        Simulation sim = Simulation.forResume(
            environment,
            1000L,
            100L,
            policyManager,
            organismConfig
        );

        // Before creating any new organisms, count should be 100
        assertThat(sim.getTotalOrganismsCreatedCount()).isEqualTo(100);

        // After creating one, should be 101
        Organism.create(sim, new int[]{0, 0}, 100, sim.getLogger());
        assertThat(sim.getTotalOrganismsCreatedCount()).isEqualTo(101);
    }

    // ==================== Edge Cases ====================

    @Test
    @Tag("unit")
    void testForResume_ZeroTotalOrganisms() {
        Simulation sim = Simulation.forResume(
            environment,
            0L,
            0L,  // No organisms created yet
            policyManager,
            organismConfig
        );

        // First organism should get ID 1
        Organism org = Organism.create(sim, new int[]{0, 0}, 100, sim.getLogger());
        assertThat(org.getId()).isEqualTo(1);
    }

    @Test
    @Tag("unit")
    void testForResume_LargeValues() {
        Simulation sim = Simulation.forResume(
            environment,
            1_000_000_000L,  // 1 billion ticks
            1_000_000L,      // 1 million organisms
            policyManager,
            organismConfig
        );

        assertThat(sim.getCurrentTick()).isEqualTo(1_000_000_000L);
        assertThat(sim.getTotalOrganismsCreatedCount()).isEqualTo(1_000_000);
    }
}
