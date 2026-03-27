package org.evochora.runtime.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.RegisterBank;
import org.evochora.test.utils.SimulationTestUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class OrganismWriteOperandSplitTest {

    private Organism org;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        Environment environment = new Environment(new int[]{100, 100}, true);
        Simulation sim = SimulationTestUtils.createSimulation(environment);
        org = Organism.create(sim, new int[]{0, 0}, 100);
        sim.addOrganism(org);
    }

    @Test
    void testWriteOperandToDrSucceeds() {
        boolean result = org.writeOperand(0, 42);

        assertThat(result).isTrue();
        assertThat(org.readOperand(0)).isEqualTo(42);
    }

    @Test
    void testWriteOperandToLrIsRejected() {
        boolean result = org.writeOperand(RegisterBank.LR.base, new int[]{1, 2});

        assertThat(result).isFalse();
    }

    @Test
    void testWriteLocationOperandToLrSucceeds() {
        int[] vec = {5, 10};
        boolean result = org.writeLocationOperand(RegisterBank.LR.base, vec);

        assertThat(result).isTrue();
        assertThat(org.readOperand(RegisterBank.LR.base)).isEqualTo(vec);
    }

    @Test
    void testWriteLocationOperandToDrIsRejected() {
        boolean result = org.writeLocationOperand(0, new int[]{1, 2});

        assertThat(result).isFalse();
    }

    @Test
    void testIsLocationBankForLr() {
        assertThat(Organism.isLocationBank(RegisterBank.LR.base)).isTrue();
        assertThat(Organism.isLocationBank(RegisterBank.LR.base + Config.NUM_LOCATION_REGISTERS - 1)).isTrue();
        assertThat(Organism.isLocationBank(RegisterBank.LR.base + Config.NUM_LOCATION_REGISTERS)).isFalse();
    }

    @Test
    void testIsLocationBankForDr() {
        assertThat(Organism.isLocationBank(0)).isFalse();
    }

    @Test
    void testIsLocationBankForPdr() {
        assertThat(Organism.isLocationBank(RegisterBank.PDR.base)).isFalse();
    }
}
