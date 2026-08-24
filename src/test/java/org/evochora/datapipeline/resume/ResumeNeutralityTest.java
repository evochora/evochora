package org.evochora.datapipeline.resume;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.TreeMap;
import java.util.Map;
import java.util.Deque;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.utils.delta.DeltaCodec;
import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.RegisterBank;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.spi.IBirthHandler;
import org.evochora.runtime.spi.IDeathHandler;
import org.evochora.runtime.spi.IInstructionInterceptor;
import org.evochora.runtime.spi.ISimulationPlugin;
import org.evochora.runtime.spi.ITickPlugin;
import org.evochora.runtime.spi.IRandomProvider;
import org.evochora.runtime.thermodynamics.ThermodynamicPolicyManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.ConfigFactory;

/**
 * Resume neutrality through the real pipeline layer: a simulation is serialized with
 * {@link OrganismStateSerializer} and the tick-data encoder, rebuilt by {@link SimulationRestorer},
 * and must continue exactly like the uninterrupted run. Unlike the runtime-level contract tests,
 * this exercises the serialization in both directions.
 */
@Tag("unit")
class ResumeNeutralityTest {

    private static final long SEED = 42L;
    private static final int SIZE = 64;
    private static final int JUMPERS = 16;
    private static final int LABEL_HASH = 0b1011_0110_0101_1001_1010 & Config.VALUE_MASK;
    private static final String PROGRAM_ID = "resume-neutrality";

    /**
     * The resolved configuration this scenario runs on. No reproduction happens here, so the birth
     * handlers stay idle whatever their rate; they are configured all the same, because their state
     * is carried across the resume like any other.
     */
    private static final String CONFIG_JSON = ResumeNeutralityHarness.configJson(SIZE, 0.025);

    private final List<Simulation> simulations = new ArrayList<>();

    @BeforeAll
    static void initInstructions() {
        Instruction.init();
    }

    @AfterEach
    void shutdownSimulations() {
        simulations.forEach(Simulation::shutdown);
        simulations.clear();
    }

    @Test
    void resumedRun_continuesExactlyLikeTheUninterruptedRun_singleThread() {
        assertResumeNeutral(1);
    }

    @Test
    void resumedRun_continuesExactlyLikeTheUninterruptedRun_twoThreads() {
        assertResumeNeutral(2);
    }

    @Test
    void resumedRun_isNeutralAcrossAChangeOfThreadCount() {
        assertResumeNeutral(1, 2);
        assertResumeNeutral(2, 1);
    }

    @Test
    void serializer_andRestorer_roundTripEveryOrganismField() {
        World world = new World(1);
        Organism organism = world.sim.getOrganisms().get(0);
        // Populate state that plain ticking leaves empty
        organism.getDataStack().push(new Molecule(Config.TYPE_DATA, 5).toInt());
        organism.getDataStack().push(new int[]{1, 2});
        organism.getLocationStack().push(new int[]{3, 4});
        // The snapshot has to hold one value per stack-saved slot, the way CALL takes it — a partial
        // one describes a frame no procedure call could leave behind, and RET would reject it.
        organism.writeOperand(RegisterBank.PDR.base, 7);
        organism.writeOperand(RegisterBank.PLR.base, new int[]{8, 9});
        // Two frames, as a procedure calling another procedure leaves behind. One frame would hide
        // any ordering mistake in the round trip, because a single-element stack reads the same in
        // both directions.
        organism.getCallStack().push(new Organism.ProcFrame(123, new int[]{5, 5}, new int[]{6, 6},
                organism.snapshotStackSavedRegisters(), java.util.Map.of(0, 1)));
        organism.getCallStack().push(new Organism.ProcFrame(456, new int[]{7, 7}, new int[]{8, 8},
                organism.snapshotStackSavedRegisters(), java.util.Map.of(1, 2)));
        organism.setDp(1, new int[]{9, 9});
        organism.setActiveDpIndex(1);
        organism.addSr(17);
        organism.writeOperand(3, new int[]{11, 12});
        world.sim.tick();
        // A failure recorded with a non-empty call stack, as an instruction failing inside a
        // procedure would leave it at the end of a tick
        organism.instructionFailed("test failure");

        Simulation restored = restore(world, 1).simulation();
        simulations.add(restored);
        Organism rebuilt = restored.getOrganisms().stream()
                .filter(o -> o.getId() == organism.getId()).findFirst().orElseThrow();

        assertThat(ResumeNeutralityHarness.describe(rebuilt))
                .isEqualTo(ResumeNeutralityHarness.describe(organism));
    }

    // ===================================================================================
    // Scenario
    // ===================================================================================

    /**
     * Sixteen organisms jumping every tick between two own copies of one label (stochastic
     * selection draws from each organism's random source), plus one organism alternating
     * {@code SETI}/{@code RAND}. Built from {@link #CONFIG_JSON}, exactly like the restorer
     * builds the resumed simulation.
     */
    private final class World {
        final Environment env;
        final Simulation sim;
        final IRandomProvider provider;
        final List<ISimulationPlugin> plugins;

        World(int parallelism) {
            ResumeNeutralityHarness.Fixture fixture =
                    ResumeNeutralityHarness.newFixture(CONFIG_JSON, SIZE, parallelism);
            env = fixture.env();
            sim = fixture.sim();
            provider = fixture.provider();
            plugins = fixture.plugins();
            simulations.add(sim);

            for (int row = 0; row < JUMPERS; row++) {
                Organism organism = Organism.create(sim, new int[]{0, row}, 10_000);
                organism.setProgramId(PROGRAM_ID);
                sim.addOrganism(organism);
                int id = organism.getId();
                placeJump(id, 0, row);
                env.setMolecule(new Molecule(Config.TYPE_LABEL, LABEL_HASH), id, new int[]{20, row});
                placeJump(id, 21, row);
                env.setMolecule(new Molecule(Config.TYPE_LABEL, LABEL_HASH), id, new int[]{44, row});
                placeJump(id, 45, row);
            }
            // An organism that exercises the structures the jumpers never touch: procedure calls with
            // parameters, proc-local and static registers, both stacks, the data pointers and the
            // molecule marker. Without it, neutrality would only be shown for registers and position.
            StatefulProgram.place(sim, env, new int[]{0, JUMPERS + 1}, 1_000_000);

            Organism roller = Organism.create(sim, new int[]{0, JUMPERS}, 10_000);
            roller.setProgramId(PROGRAM_ID);
            sim.addOrganism(roller);
            int seti = Instruction.getInstructionIdByName("SETI");
            int rand = Instruction.getInstructionIdByName("RAND");
            for (int x = 0; x + 5 <= SIZE; x += 5) {
                env.setMolecule(new Molecule(Config.TYPE_CODE, seti), roller.getId(), new int[]{x, JUMPERS});
                env.setMolecule(new Molecule(Config.TYPE_DATA, 0), roller.getId(), new int[]{x + 1, JUMPERS});
                env.setMolecule(new Molecule(Config.TYPE_DATA, 1000), roller.getId(), new int[]{x + 2, JUMPERS});
                env.setMolecule(new Molecule(Config.TYPE_CODE, rand), roller.getId(), new int[]{x + 3, JUMPERS});
                env.setMolecule(new Molecule(Config.TYPE_DATA, 0), roller.getId(), new int[]{x + 4, JUMPERS});
            }
        }

        private void placeJump(int ownerId, int x, int y) {
            env.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("JMPI")), ownerId, new int[]{x, y});
            env.setMolecule(new Molecule(Config.TYPE_DATA, LABEL_HASH), ownerId, new int[]{x + 1, y});
        }
    }

    // ===================================================================================
    // Resume through serializer, encoder and restorer
    // ===================================================================================

    private void assertResumeNeutral(int parallelism) {
        assertResumeNeutral(parallelism, parallelism);
    }

    /**
     * The reference runs uninterrupted with {@code parallelismBefore}; the candidate runs with
     * {@code parallelismBefore} until the first pause and with {@code parallelismAfter} after
     * every resume. Resuming on a different thread count must not change the trajectory.
     */
    private void assertResumeNeutral(int parallelismBefore, int parallelismAfter) {
        int totalTicks = 30;
        int firstPause = 7;
        int secondPause = 19;

        World reference = new World(parallelismBefore);
        List<List<String>> expected = ResumeNeutralityHarness.tick(reference.sim, reference.plugins, totalTicks, false);

        World interrupted = new World(parallelismBefore);
        List<List<String>> actual = new ArrayList<>(ResumeNeutralityHarness.tick(interrupted.sim, interrupted.plugins, firstPause, false));
        SimulationRestorer.RestoredState onceResumed = restore(interrupted, parallelismAfter);
        simulations.add(onceResumed.simulation());
        actual.addAll(ResumeNeutralityHarness.tick(onceResumed.simulation(),
                ResumeNeutralityHarness.uniquePlugins(onceResumed), secondPause - firstPause, false));
        SimulationRestorer.RestoredState twiceResumed = ResumeNeutralityHarness.restore(
                onceResumed.simulation(), onceResumed.randomProvider(),
                ResumeNeutralityHarness.uniquePlugins(onceResumed), CONFIG_JSON, parallelismAfter);
        simulations.add(twiceResumed.simulation());
        actual.addAll(ResumeNeutralityHarness.tick(twiceResumed.simulation(),
                ResumeNeutralityHarness.uniquePlugins(twiceResumed), totalTicks - secondPause, false));

        ResumeNeutralityHarness.assertSameTrajectory(expected, actual, "parallelism %d -> %d".formatted(parallelismBefore, parallelismAfter));
    }

    private SimulationRestorer.RestoredState restore(World world, int parallelism) {
        return ResumeNeutralityHarness.restore(
                world.sim, world.provider, world.plugins, CONFIG_JSON, parallelism);
    }
}
