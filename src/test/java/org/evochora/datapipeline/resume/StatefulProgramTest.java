package org.evochora.datapipeline.resume;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.evochora.runtime.Simulation;
import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.RegisterBank;
import org.evochora.runtime.model.Environment;
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
     * Runs two passes and records which structures held content at least once. Each of them is a
     * structure the restorer has to carry over, and each is recorded at the tick where the program
     * fills it — the state at the end says nothing, since the program tidies up after itself so that
     * the loop can repeat. The second pass is what shows it can: a structure left behind by the first
     * pass would make the second one behave differently.
     */
    @Test
    void programFillsEveryStructureAcrossTwoPasses() {
        Environment environment = new Environment(new int[]{SIZE, SIZE}, true);
        Simulation simulation = SimulationTestUtils.createSimulation(environment);
        simulations.add(simulation);
        simulation.setRandomProvider(new SeededRandomProvider(42L));

        Organism organism = StatefulProgram.place(simulation, environment, new int[]{0, 0}, 1_000_000);

        boolean sawSavedRegisters = false;
        for (int pass = 1; pass <= 2; pass++) {
            sawSavedRegisters |= assertCoverageDuringOnePass(simulation, organism, pass);
        }
        assertThat(sawSavedRegisters)
                .as("a call frame carried a register snapshot")
                .isTrue();

        assertThat(environment.getOwnerId(StatefulProgram.SCRATCH_CELL))
                .as("program wrote to the world")
                .isEqualTo(organism.getId());
    }

    /**
     * Runs one pass and requires every structure to hold content at some point within it.
     * <p>
     * Checked per pass rather than across both, because a structure the first pass fills and the
     * second one does not would otherwise still satisfy the test — and that difference between
     * passes is exactly what would show that the program leaves something behind.
     * <p>
     * The saved-register snapshot is the one exception, and is returned rather than asserted here:
     * CALL only takes a snapshot when a stack-saved register has been written, so the first call of
     * a run leaves the frame without one. It appears from the second call onwards.
     *
     * @param simulation the simulation to advance
     * @param organism the organism running the program
     * @param pass the pass number, for the failure message
     * @return whether a call frame carried a register snapshot during this pass
     */
    private static boolean assertCoverageDuringOnePass(Simulation simulation, Organism organism, int pass) {
        boolean sawDataStack = false;
        boolean sawLocationStack = false;
        boolean sawCallStack = false;
        boolean sawSavedRegisters = false;
        boolean sawPersistentState = false;
        boolean sawActiveDpSwitch = false;
        boolean sawMarker = false;
        boolean sawLocationRegister = false;

        for (int tick = 0; tick < StatefulProgram.TICKS_PER_PASS; tick++) {
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

        assertThat(sawDataStack).as("data stack was used in pass %d", pass).isTrue();
        assertThat(sawLocationStack).as("location stack was used in pass %d", pass).isTrue();
        assertThat(sawCallStack).as("call stack was used in pass %d", pass).isTrue();
        assertThat(sawPersistentState).as("persistent register store was filled in pass %d", pass).isTrue();
        assertThat(sawActiveDpSwitch).as("active data pointer was switched in pass %d", pass).isTrue();
        assertThat(sawMarker).as("molecule marker was set in pass %d", pass).isTrue();
        assertThat(sawLocationRegister).as("location register was written in pass %d", pass).isTrue();
        return sawSavedRegisters;
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
