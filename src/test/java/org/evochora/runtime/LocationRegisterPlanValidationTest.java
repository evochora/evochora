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
 * Pins the invariant that operand validation happens during planning: an instruction
 * whose argument cell decodes to an invalid location-register id is marked failed by
 * {@link VirtualMachine#plan} itself, exactly like an invalid data-register id. Later
 * readers of register values (the instruction body, observers of sampled ticks) must
 * find the failure already recorded and never produce it themselves.
 */
@Tag("unit")
class LocationRegisterPlanValidationTest {

    private static final int INVALID_REGISTER_ID = 3000; // beyond the register id table

    private Environment environment;
    private Simulation simulation;
    private Organism organism;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        environment = new Environment(new int[]{32, 32}, true);
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

        // DPLR with an argument cell whose value is not a valid register id
        int dplrOpcode = Instruction.getInstructionIdByName("DPLR");
        environment.setMolecule(new Molecule(Config.TYPE_CODE, dplrOpcode), organism.getId(), new int[]{5, 5});
        environment.setMolecule(new Molecule(Config.TYPE_DATA, INVALID_REGISTER_ID), organism.getId(), new int[]{6, 5});
    }

    @Test
    void invalidLocationRegisterIdFailsDuringPlanning() {
        simulation.getVirtualMachine().plan(organism);
        assertThat(organism.isInstructionFailed()).isTrue();
    }

    @Test
    void failureIsIndependentOfExecutionDetailCapture() {
        simulation.setCaptureExecutionDetails(false);
        simulation.tick();
        boolean failedWithoutCapture = organism.isInstructionFailed();
        assertThat(failedWithoutCapture).isTrue();
    }
}
