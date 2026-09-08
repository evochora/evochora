package org.evochora.datapipeline.resume;

import static org.assertj.core.api.Assertions.assertThat;

import org.evochora.datapipeline.api.contracts.MutationEvent;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.runtime.Simulation;
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
 * Unit tests for {@link OrganismStateSerializer}, covering the birth mutation records it copies
 * into an organism's state.
 * <p>
 * The flat indices are copied as the plugins held them: the serializer converts nothing, because
 * every consumer that wants a position has the world shape in the run's metadata.
 */
@Tag("unit")
class OrganismStateSerializerTest {

    private Simulation sim;
    private OrganismStateSerializer serializer;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        sim = SimulationTestUtils.createSimulation(new Environment(new int[]{96, 96}, true));
        serializer = new OrganismStateSerializer();
    }

    @Test
    void theRecordsOfAnOrganismAreCopiedFieldForField() {
        Organism org = Organism.create(sim, new int[]{10, 10}, 100);
        sim.addOrganism(org);
        org.recordBirthMutation(new MutationRecord("org.example.Substitution", "substitution",
                new int[]{4711}, new int[]{100}, new int[]{200}, new long[0], new int[]{1, 0}));
        org.recordBirthMutation(new MutationRecord("org.example.Duplication", "duplication",
                new int[]{30, 29}, new int[]{0, 0}, new int[]{11, 12}, new long[]{64L},
                new int[]{-1, 0}));

        OrganismState state = serializer.serialize(org);

        assertThat(state.getBirthMutationsCount()).isEqualTo(2);

        MutationEvent first = state.getBirthMutations(0);
        assertThat(first.getPluginClass()).isEqualTo("org.example.Substitution");
        assertThat(first.getKind()).isEqualTo("substitution");
        assertThat(first.getCellsList()).containsExactly(4711);
        assertThat(first.getOldValuesList()).containsExactly(100);
        assertThat(first.getNewValuesList()).containsExactly(200);
        assertThat(first.getParamsList()).isEmpty();
        assertThat(first.getDvList()).containsExactly(1, 0);

        MutationEvent second = state.getBirthMutations(1);
        assertThat(second.getKind()).isEqualTo("duplication");
        // In the order the plugin wrote them, which is its walk along dv
        assertThat(second.getCellsList()).containsExactly(30, 29);
        assertThat(second.getNewValuesList()).containsExactly(11, 12);
        assertThat(second.getParamsList()).containsExactly(64L);
        assertThat(second.getDvList()).containsExactly(-1, 0);
    }

    @Test
    void anOrganismWithoutRecordsCarriesNoEvents() {
        Organism org = Organism.create(sim, new int[]{10, 10}, 100);
        sim.addOrganism(org);

        OrganismState state = serializer.serialize(org);

        assertThat(state.getBirthMutationsList()).isEmpty();
    }

    @Test
    void theEventsOfOneOrganismDoNotLeakIntoTheNext() {
        // The serializer reuses its builders across organisms
        Organism withRecord = Organism.create(sim, new int[]{10, 10}, 100);
        sim.addOrganism(withRecord);
        withRecord.recordBirthMutation(new MutationRecord("org.example.Substitution", "substitution",
                new int[]{4711}, new int[]{100}, new int[]{200}, new long[0], new int[]{1, 0}));
        Organism withoutRecord = Organism.create(sim, new int[]{20, 20}, 100);
        sim.addOrganism(withoutRecord);

        serializer.serialize(withRecord);
        OrganismState state = serializer.serialize(withoutRecord);

        assertThat(state.getBirthMutationsList()).isEmpty();
    }
}
