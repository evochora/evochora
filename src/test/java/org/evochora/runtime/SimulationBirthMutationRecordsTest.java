package org.evochora.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.resume.OrganismStateSerializer;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.MutationRecord;
import org.evochora.runtime.model.Organism;
import org.evochora.test.utils.SimulationTestUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests how the simulation holds the birth mutation records of its newborns until they have been
 * written out.
 * <p>
 * The records are the only place a mutation is observable, so the point of the list is that a
 * newborn's records survive until the recording that carries them - including for an organism that
 * dies before it was ever recorded alive.
 */
@Tag("unit")
class SimulationBirthMutationRecordsTest {

    private Environment environment;
    private Simulation sim;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        environment = new Environment(new int[]{96, 96}, true);
        sim = SimulationTestUtils.createSimulation(environment);
    }

    @Test
    void aNewbornWhoseBirthHandlerRecordedSomethingIsClearedWithTheNextRecording() {
        sim.addBirthHandler(recordingHandler());
        Organism newborn = Organism.create(sim, new int[]{10, 10}, 100);
        sim.addNewOrganism(newborn);

        sim.tick();

        assertThat(newborn.getBirthMutations()).hasSize(1);

        sim.clearBirthMutationRecords();

        assertThat(newborn.getBirthMutations()).isNull();
    }

    @Test
    void clearingTouchesOnlyTheNewbornsThatWerePending() {
        // A record that never came from a birth handler is not in the simulation's list, and the
        // clear must not walk the population looking for one
        sim.addBirthHandler(recordingHandler());
        Organism newborn = Organism.create(sim, new int[]{10, 10}, 100);
        sim.addNewOrganism(newborn);
        sim.tick();

        Organism other = Organism.create(sim, new int[]{20, 20}, 100);
        sim.addOrganism(other);
        other.recordBirthMutation(record(99));

        sim.clearBirthMutationRecords();

        assertThat(newborn.getBirthMutations()).isNull();
        assertThat(other.getBirthMutations()).hasSize(1);
    }

    @Test
    void aNewbornThatDiedBeforeItsFirstRecordingIsStillSerializedWithItsRecords() {
        sim.addBirthHandler(recordingHandler());
        Organism newborn = Organism.create(sim, new int[]{10, 10}, 100);
        sim.addNewOrganism(newborn);
        sim.tick();

        // Born and dead between two recordings: the death removes it from the simulation only
        // after the recording that reports it dead, so that recording still carries its records
        newborn.kill("test");
        OrganismState state = new OrganismStateSerializer().serialize(newborn);
        sim.pruneDeadOrganisms();

        assertThat(sim.getOrganisms()).doesNotContain(newborn);
        assertThat(state.getIsDead()).isTrue();
        assertThat(state.getBirthMutationsList()).hasSize(1);
        assertThat(state.getBirthMutations(0).getKind()).isEqualTo("substitution");
        assertThat(state.getBirthMutations(0).getCellsList()).containsExactly(4711);
    }

    @Test
    void aNewbornNoPluginTouchedIsNeverPending() {
        Organism newborn = Organism.create(sim, new int[]{10, 10}, 100);
        sim.addNewOrganism(newborn);
        sim.tick();

        assertThat(newborn.getBirthMutations()).isNull();

        sim.clearBirthMutationRecords();

        assertThat(newborn.getBirthMutations()).isNull();
    }

    /** A birth handler that reports one substitution for every newborn. */
    private org.evochora.runtime.spi.IBirthHandler recordingHandler() {
        return new org.evochora.runtime.spi.IBirthHandler() {
            @Override
            public void onBirth(Organism child, Environment env) {
                child.recordBirthMutation(record(4711));
            }

            @Override
            public byte[] saveState() {
                return new byte[0];
            }

            @Override
            public void loadState(byte[] state) {
                // Stateless
            }
        };
    }

    private static MutationRecord record(int cell) {
        return new MutationRecord("org.example.Plugin", "substitution",
                new int[]{cell}, new int[]{1}, new int[]{2}, new long[0], new int[]{1, 0});
    }
}
