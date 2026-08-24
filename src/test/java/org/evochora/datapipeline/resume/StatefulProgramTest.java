package org.evochora.datapipeline.resume;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.RegisterBank;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.Organism;
import org.evochora.test.utils.SimulationTestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Guards the program {@link StatefulProgram} runs in the resume neutrality test.
 * <p>
 * That test can only prove neutrality for state the organisms actually build up. This test pins the
 * precondition: within the ticks the neutrality test runs, the program must touch every organism
 * structure a resume has to carry over, and it must do so without a single failing instruction —
 * a failure would leave the structure empty and silently narrow what neutrality proves.
 */
@Tag("unit")
class StatefulProgramTest {

    private static final int SIZE = 64;

    private final List<Simulation> simulations = new ArrayList<>();

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @AfterEach
    void shutdownSimulations() {
        simulations.forEach(Simulation::shutdown);
        simulations.clear();
    }

    @Test
    void programTouchesEveryOrganismStructure_withoutFailing() {
        Environment environment = new Environment(new int[]{SIZE, SIZE}, true);
        Simulation simulation = SimulationTestUtils.createSimulation(environment);
        simulations.add(simulation);
        simulation.setRandomProvider(new SeededRandomProvider(42L));

        Organism organism = StatefulProgram.place(simulation, environment, new int[]{0, 0}, 1_000_000);

        for (int tick = 0; tick < StatefulProgram.TICKS_PER_PASS * 2; tick++) {
            simulation.tick();
            assertThat(organism.isInstructionFailed())
                    .as("instruction failed at tick %d: %s", simulation.getCurrentTick(), organism.getFailureReason())
                    .isFalse();
            assertThat(organism.isDead())
                    .as("organism died at tick %d", simulation.getCurrentTick())
                    .isFalse();
        }

        // Death by starvation or by exceeding the entropy limit is already covered by isDead above;
        // the remaining energy shows how much headroom the neutrality test has.
        assertThat(organism.getEr()).as("energy left").isGreaterThan(0);
    }

    /**
     * Runs one pass and records which structures held content at least once. Each of them is a
     * structure the restorer has to carry over, and each is checked at the tick where the program
     * fills it — the state at the end of a pass says nothing, since the program tidies up after
     * itself so that the loop can repeat.
     */
    @Test
    void programFillsEveryStructureAtSomePointDuringOnePass() {
        Environment environment = new Environment(new int[]{SIZE, SIZE}, true);
        Simulation simulation = SimulationTestUtils.createSimulation(environment);
        simulations.add(simulation);
        simulation.setRandomProvider(new SeededRandomProvider(42L));

        Organism organism = StatefulProgram.place(simulation, environment, new int[]{0, 0}, 1_000_000);

        boolean sawDataStack = false;
        boolean sawLocationStack = false;
        boolean sawCallStack = false;
        boolean sawSavedRegisters = false;
        boolean sawPersistentState = false;
        boolean sawActiveDpSwitch = false;
        boolean sawMarker = false;
        boolean sawLocationRegister = false;

        for (int tick = 0; tick < StatefulProgram.TICKS_PER_PASS * 2; tick++) {
            simulation.tick();
            sawDataStack |= !organism.getDataStack().isEmpty();
            sawLocationStack |= !organism.getLocationStack().isEmpty();
            sawCallStack |= !organism.getCallStack().isEmpty();
            sawSavedRegisters |= !organism.getCallStack().isEmpty()
                    && organism.getCallStack().peek().savedRegisters() != null;
            sawPersistentState |= !organism.getPersistentRegisterState().isEmpty();
            sawActiveDpSwitch |= organism.getActiveDpIndex() != 0;
            sawMarker |= organism.getMr() != 0;
            sawLocationRegister |= isNonZeroVector(organism.readOperand(RegisterBank.LR.base));
        }

        assertThat(sawDataStack).as("data stack was used").isTrue();
        assertThat(sawLocationStack).as("location stack was used").isTrue();
        assertThat(sawCallStack).as("call stack was used").isTrue();
        assertThat(sawSavedRegisters).as("call frame carried a register snapshot").isTrue();
        assertThat(sawPersistentState).as("persistent register store was filled").isTrue();
        assertThat(sawActiveDpSwitch).as("active data pointer was switched").isTrue();
        assertThat(sawMarker).as("molecule marker was set").isTrue();
        assertThat(sawLocationRegister).as("location register was written").isTrue();

        assertThat(environment.getOwnerId(StatefulProgram.SCRATCH_CELL))
                .as("program wrote to the world")
                .isEqualTo(organism.getId());
    }

    private static boolean isNonZeroVector(Object value) {
        if (!(value instanceof int[] vector)) {
            return false;
        }
        for (int component : vector) {
            if (component != 0) {
                return true;
            }
        }
        return false;
    }

}
