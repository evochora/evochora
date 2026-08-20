package org.evochora.datapipeline.resume;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
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
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.spi.IRandomProvider;
import org.evochora.runtime.thermodynamics.ThermodynamicPolicyManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.google.protobuf.ByteString;
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

    /** Resolved configuration both the reference simulation and the restorer are built from. */
    private static final String CONFIG_JSON = """
            {
              "environment": { "shape": [%d, %d], "topology": "TORUS" },
              "samplingInterval": 1,
              "accumulatedDeltaInterval": 1,
              "snapshotInterval": 1,
              "chunkInterval": 1,
              "plugins": [],
              "organisms": [],
              "runtime": {
                "organism": { "max-energy": 32767, "max-entropy": 8191, "error-penalty-cost": 10 },
                "thermodynamics": {
                  "default": {
                    "className": "org.evochora.runtime.thermodynamics.impl.UniversalThermodynamicPolicy",
                    "options": { "base-energy": 1, "base-entropy": 1 }
                  },
                  "overrides": { "instructions": {}, "families": {} }
                },
                "label-matching": {
                  "className": "org.evochora.runtime.label.PreExpandedHammingStrategy",
                  "options": { "tolerance": 2, "hammingWeight": 50, "foreignPenalty": 100, "selectionSpread": 50 }
                }
              }
            }
            """.formatted(SIZE, SIZE);

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
        organism.getCallStack().push(new Organism.ProcFrame("PROC", 123, new int[]{5, 5}, new int[]{6, 6},
                new Object[]{7, new int[]{8, 9}}, java.util.Map.of(0, 1)));
        organism.setDp(1, new int[]{9, 9});
        organism.setActiveDpIndex(1);
        organism.addSr(17);
        organism.writeOperand(3, new int[]{11, 12});
        world.sim.tick();

        Simulation restored = restore(world, 1).simulation();
        Organism rebuilt = restored.getOrganisms().stream()
                .filter(o -> o.getId() == organism.getId()).findFirst().orElseThrow();

        assertSameState(rebuilt, organism);
    }

    /**
     * Compares every piece of simulation state an organism carries, through the runtime's own
     * accessors — independent of how the serializer represents it, so that a field the serializer
     * forgets shows up as a difference here.
     */
    private static void assertSameState(Organism actual, Organism expected) {
        assertThat(actual.getId()).isEqualTo(expected.getId());
        assertThat(actual.getParentId()).isEqualTo(expected.getParentId());
        assertThat(actual.getBirthTick()).isEqualTo(expected.getBirthTick());
        assertThat(actual.getProgramId()).isEqualTo(expected.getProgramId());
        assertThat(actual.getEr()).as("energy").isEqualTo(expected.getEr());
        assertThat(actual.getSr()).as("entropy").isEqualTo(expected.getSr());
        assertThat(actual.getMr()).as("marker").isEqualTo(expected.getMr());
        assertThat(actual.getIp()).isEqualTo(expected.getIp());
        assertThat(actual.getDv()).isEqualTo(expected.getDv());
        assertThat(actual.getInitialPosition()).isEqualTo(expected.getInitialPosition());
        assertThat(actual.getDps()).usingRecursiveComparison().isEqualTo(expected.getDps());
        assertThat(actual.getActiveDpIndex()).as("active DP").isEqualTo(expected.getActiveDpIndex());
        assertThat(actual.getRegisters()).usingRecursiveComparison().isEqualTo(expected.getRegisters());
        assertThat(new ArrayList<>(actual.getDataStack())).usingRecursiveComparison()
                .isEqualTo(new ArrayList<>(expected.getDataStack()));
        assertThat(new ArrayList<>(actual.getLocationStack())).usingRecursiveComparison()
                .isEqualTo(new ArrayList<>(expected.getLocationStack()));
        assertThat(new ArrayList<>(actual.getCallStack())).usingRecursiveComparison()
                .isEqualTo(new ArrayList<>(expected.getCallStack()));
        assertThat(actual.isDead()).isEqualTo(expected.isDead());
        assertThat(actual.getDeathTick()).isEqualTo(expected.getDeathTick());
        assertThat(actual.isInstructionFailed()).isEqualTo(expected.isInstructionFailed());
        assertThat(actual.getFailureReason()).isEqualTo(expected.getFailureReason());
        assertThat(actual.getGenomeHash()).isEqualTo(expected.getGenomeHash());
        assertThat(actual.getCurrentProcLabelHash()).isEqualTo(expected.getCurrentProcLabelHash());
        assertThat(actual.isStackSavedDirty()).isEqualTo(expected.isStackSavedDirty());
        assertThat(actual.isPersistentDirty()).isEqualTo(expected.isPersistentDirty());
        assertThat(actual.getPersistentRegisterState()).usingRecursiveComparison()
                .isEqualTo(expected.getPersistentRegisterState());
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

        World(int parallelism) {
            com.typesafe.config.Config config = ConfigFactory.parseString(CONFIG_JSON);
            env = new Environment(new EnvironmentProperties(new int[]{SIZE, SIZE}, true),
                    Environment.createLabelMatchingStrategy(config.getConfig("runtime.label-matching")));
            sim = new Simulation(env,
                    new ThermodynamicPolicyManager(config.getConfig("runtime.thermodynamics")),
                    config.getConfig("runtime.organism"), parallelism);
            simulations.add(sim);
            provider = new SeededRandomProvider(SEED);
            sim.setRandomProvider(provider);

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
        List<long[]> expected = tick(reference.sim, totalTicks);

        World interrupted = new World(parallelismBefore);
        List<long[]> actual = new ArrayList<>(tick(interrupted.sim, firstPause));
        SimulationRestorer.RestoredState onceResumed = restore(interrupted, parallelismAfter);
        simulations.add(onceResumed.simulation());
        actual.addAll(tick(onceResumed.simulation(), secondPause - firstPause));
        SimulationRestorer.RestoredState twiceResumed = restore(onceResumed.simulation(), onceResumed.randomProvider(), parallelismAfter);
        simulations.add(twiceResumed.simulation());
        actual.addAll(tick(twiceResumed.simulation(), totalTicks - secondPause));

        assertThat(actual).as("tick count").hasSameSizeAs(expected);
        for (int t = 0; t < expected.size(); t++) {
            assertThat(actual.get(t))
                    .as("state after tick %d differs (parallelism %d -> %d)", t + 1, parallelismBefore, parallelismAfter)
                    .isEqualTo(expected.get(t));
        }
    }

    private SimulationRestorer.RestoredState restore(World world, int parallelism) {
        return restore(world.sim, world.provider, parallelism);
    }

    /**
     * Serializes the live simulation the way the engine does (serializer for organisms, encoder
     * for cells and counters), then rebuilds it with the restorer from that snapshot.
     */
    private SimulationRestorer.RestoredState restore(Simulation live, IRandomProvider liveProvider, int parallelism) {
        OrganismStateSerializer serializer = new OrganismStateSerializer();
        List<OrganismState> states = live.getOrganisms().stream().map(serializer::serialize).toList();

        DeltaCodec.Encoder encoder = new DeltaCodec.Encoder("resume-test", (int) live.getEnvironment().getTotalCells(), 1, 1, 1);
        Optional<TickDataChunk> chunk = encoder.captureTick(
                live.getCurrentTick(), live.getEnvironment(), states,
                live.getTotalOrganismsCreatedCount(), live.getTotalUniqueGenomesCount(), live.getAllGenomesEverSeen(),
                ByteString.copyFrom(liveProvider.saveState()), List.of());
        TickData snapshot = chunk.or(encoder::flushPartialChunk).orElseThrow().getSnapshot();

        SimulationMetadata metadata = SimulationMetadata.newBuilder()
                .setSimulationRunId("resume-test")
                .setInitialSeed(SEED)
                .setResolvedConfigJson(CONFIG_JSON)
                .build();
        return SimulationRestorer.restore(new ResumeCheckpoint(metadata, snapshot), new SeededRandomProvider(SEED), parallelism);
    }

    // ===================================================================================
    // Trajectory
    // ===================================================================================

    /** Ticks and records, per tick, IP, DR0 and energy of every organism in index order. */
    private static List<long[]> tick(Simulation sim, int n) {
        List<long[]> trajectory = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            sim.tick();
            List<Organism> organisms = sim.getOrganisms();
            for (Organism organism : organisms) {
                assertThat(organism.isInstructionFailed())
                        .as("organism %d failed at tick %d: %s", organism.getId(), sim.getCurrentTick(), organism.getFailureReason())
                        .isFalse();
            }
            long[] state = new long[organisms.size() * 4];
            for (int o = 0; o < organisms.size(); o++) {
                Organism organism = organisms.get(o);
                state[o * 4] = organism.getIp()[0];
                state[o * 4 + 1] = organism.getIp()[1];
                state[o * 4 + 2] = ((Integer) organism.readOperand(0));
                state[o * 4 + 3] = organism.getEr();
            }
            trajectory.add(state);
        }
        return trajectory;
    }
}
