package org.evochora.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.thermodynamics.ThermodynamicPolicyManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.ConfigFactory;

/**
 * Unit tests for {@link Simulation#setCaptureExecutionDetails(boolean)}: executed
 * instructions leave an execution record on the organism exactly when the capture
 * is enabled, which is the default.
 */
@Tag("unit")
class CaptureExecutionDetailsTest {

    private Simulation simulation;
    private Organism organism;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        Environment environment = new Environment(new int[]{10, 10}, true);
        ThermodynamicPolicyManager policyManager = new ThermodynamicPolicyManager(
            ConfigFactory.parseString("""
                default {
                  className = "org.evochora.runtime.thermodynamics.impl.UniversalThermodynamicPolicy"
                  options { base-energy = 1, base-entropy = 1 }
                }
                overrides { instructions {}, families {} }
                """));
        com.typesafe.config.Config organismConfig = ConfigFactory.parseMap(Map.of(
            "max-energy", 32767,
            "max-entropy", 8191,
            "error-penalty-cost", 10
        ));

        simulation = new Simulation(environment, policyManager, organismConfig, 1);
        simulation.setRandomProvider(new SeededRandomProvider(42L));

        organism = Organism.create(simulation, new int[]{5, 5}, 1000);
        simulation.addOrganism(organism);
        int nopOpcode = Instruction.getInstructionIdByName("NOP");
        environment.setMolecule(new Molecule(Config.TYPE_CODE, nopOpcode), organism.getId(), new int[]{5, 5});
    }

    @Test
    void executionRecordIsPresentByDefault() {
        simulation.tick();
        assertThat(organism.getLastInstructionExecution()).isNotNull();
    }

    @Test
    void executionRecordIsSkippedWhenCaptureIsOff() {
        simulation.setCaptureExecutionDetails(false);
        simulation.tick();
        assertThat(organism.getLastInstructionExecution()).isNull();
    }

    @Test
    void executionRecordReturnsWhenCaptureIsReEnabled() {
        simulation.setCaptureExecutionDetails(false);
        simulation.tick();
        simulation.setCaptureExecutionDetails(true);
        simulation.tick();
        assertThat(organism.getLastInstructionExecution()).isNotNull();
    }
}
