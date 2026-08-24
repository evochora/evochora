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

    /**
     * Every plugin the neutrality run configures, so that plugin state is carried across the resume
     * like any other state. {@link PluginCoverageTest} keeps this list in step with the code: a new
     * plugin that is not listed here fails that test.
     * <p>
     * The world-generating plugins run every tick and are parameterised to stay active without
     * crowding the organisms out of the cells they work on; the birth handlers only fire on
     * reproduction, which does not happen here, but their state is still carried and compared.
     */
    static final String PLUGINS_JSON = """
            [
              { "className": "org.evochora.runtime.worldgen.SeedEnergyCreator",
                "options": { "percentage": 0.001, "amount": 5000, "amountVariance": 0.2 } },
              { "className": "org.evochora.runtime.worldgen.GeyserCreator",
                "options": { "percentage": 0.001, "interval": 5, "amount": 5000, "safetyRadius": 3 } },
              { "className": "org.evochora.runtime.worldgen.SolarRadiationCreator",
                "options": { "probability": 0.02, "amount": 5000, "safetyRadius": 1, "executionsPerTick": 1 } },
              { "className": "org.evochora.runtime.worldgen.DecayOnDeath",
                "options": { "replacement": "CODE:0" } },
              { "className": "org.evochora.runtime.worldgen.LabelRewritePlugin",
                "options": {} },
              { "className": "org.evochora.runtime.worldgen.GeneDuplicationPlugin",
                "options": { "duplicationRate": 0.1, "minNopSize": 8 } },
              { "className": "org.evochora.runtime.worldgen.GeneDeletionPlugin",
                "options": { "deletionRate": 0.025, "countExponent": 2.0 } },
              { "className": "org.evochora.runtime.worldgen.GeneInsertionPlugin",
                "options": { "mutationRate": 0.05,
                             "entries": [
                               { "instructions": "*", "weight": 3,
                                 "args": { "REGISTER": { "range": [0, 7] },
                                           "LOCATION_REGISTER": { "range": [0, 3] },
                                           "DATA": { "min": 0, "max": 255 },
                                           "LABELREF": "existing",
                                           "VECTOR": "unit" } },
                               { "type": "label", "weight": 1, "bitflips": 2 }
                             ] } },
              { "className": "org.evochora.runtime.worldgen.GeneSubstitutionPlugin",
                "options": { "substitutionRate": 0.025,
                             "CODE": { "weight": 1.0, "operationFlipWeight": 0.7,
                                       "familyFlipWeight": 0.2, "variantFlipWeight": 0.1 },
                             "REGISTER": { "weight": 1.0 },
                             "DATA": { "weight": 1.0, "exponent": 0.7 },
                             "LABEL": { "weight": 1.0, "bitflips": 1 },
                             "LABELREF": { "weight": 1.0, "bitflips": 1 } } }
            ]
            """;

    /** Resolved configuration both the reference simulation and the restorer are built from. */
    private static final String CONFIG_JSON = """
            {
              "environment": { "shape": [%d, %d], "topology": "TORUS" },
              "samplingInterval": 1,
              "accumulatedDeltaInterval": 1,
              "snapshotInterval": 1,
              "chunkInterval": 1,
              "plugins": %s,
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
            """.formatted(SIZE, SIZE, PLUGINS_JSON);

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
        organism.getCallStack().push(new Organism.ProcFrame(123, new int[]{5, 5}, new int[]{6, 6},
                organism.snapshotStackSavedRegisters(), java.util.Map.of(0, 1)));
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
        assertThat(expected.getFailureCallStack()).as("scenario provides a failure call stack").isNotEmpty();
        assertThat(new ArrayList<>(actual.getFailureCallStack())).usingRecursiveComparison()
                .isEqualTo(new ArrayList<>(expected.getFailureCallStack()));
        assertThat(actual.getGenomeHash()).isEqualTo(expected.getGenomeHash());
        assertThat(actual.getCurrentProcLabelHash()).isEqualTo(expected.getCurrentProcLabelHash());
        assertThat(actual.isStackSavedDirty()).isEqualTo(expected.isStackSavedDirty());
        assertThat(actual.isPersistentDirty()).isEqualTo(expected.isPersistentDirty());
        assertThat(actual.getPersistentRegisterState()).usingRecursiveComparison()
                .isEqualTo(expected.getPersistentRegisterState());
    }

    /** One state entry per plugin instance, in registration order, as the engine writes them. */
    private static List<org.evochora.datapipeline.api.contracts.PluginState> pluginStates(
            List<ISimulationPlugin> plugins) {
        List<org.evochora.datapipeline.api.contracts.PluginState> states = new ArrayList<>(plugins.size());
        for (ISimulationPlugin plugin : plugins) {
            states.add(org.evochora.datapipeline.api.contracts.PluginState.newBuilder()
                    .setPluginClass(plugin.getClass().getName())
                    .setStateBlob(ByteString.copyFrom(plugin.saveState()))
                    .build());
        }
        return states;
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
            com.typesafe.config.Config config = ConfigFactory.parseString(CONFIG_JSON);
            env = new Environment(new EnvironmentProperties(new int[]{SIZE, SIZE}, true),
                    Environment.createLabelMatchingStrategy(config.getConfig("runtime.label-matching")));
            sim = new Simulation(env,
                    new ThermodynamicPolicyManager(config.getConfig("runtime.thermodynamics")),
                    config.getConfig("runtime.organism"), parallelism);
            simulations.add(sim);
            provider = new SeededRandomProvider(SEED);
            sim.setRandomProvider(provider);
            plugins = registerConfiguredPlugins(sim, config, provider);

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
        List<List<String>> expected = tick(reference.sim, totalTicks);

        World interrupted = new World(parallelismBefore);
        List<List<String>> actual = new ArrayList<>(tick(interrupted.sim, firstPause));
        SimulationRestorer.RestoredState onceResumed = restore(interrupted, parallelismAfter);
        simulations.add(onceResumed.simulation());
        actual.addAll(tick(onceResumed.simulation(), secondPause - firstPause));
        SimulationRestorer.RestoredState twiceResumed = restore(onceResumed.simulation(),
                onceResumed.randomProvider(), uniquePlugins(onceResumed), parallelismAfter);
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
        return restore(world.sim, world.provider, world.plugins, parallelism);
    }

    /**
     * Instantiates the configured plugins the way the restorer does and registers them, so the
     * reference run carries the same plugin state the resumed run will be rebuilt with.
     */
    private static List<ISimulationPlugin> registerConfiguredPlugins(
            Simulation sim, com.typesafe.config.Config config, IRandomProvider provider) {
        List<ISimulationPlugin> plugins = new ArrayList<>();
        for (com.typesafe.config.Config pluginConfig : config.getConfigList("plugins")) {
            String className = pluginConfig.getString("className");
            com.typesafe.config.Config options = pluginConfig.hasPath("options")
                    ? pluginConfig.getConfig("options") : ConfigFactory.empty();
            try {
                Object plugin = Class.forName(className)
                        .getConstructor(IRandomProvider.class, com.typesafe.config.Config.class)
                        .newInstance(provider, options);
                if (plugin instanceof ITickPlugin p) sim.addTickPlugin(p);
                if (plugin instanceof IInstructionInterceptor p) sim.addInstructionInterceptor(p);
                if (plugin instanceof IDeathHandler p) sim.addDeathHandler(p);
                if (plugin instanceof IBirthHandler p) sim.addBirthHandler(p);
                plugins.add((ISimulationPlugin) plugin);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("test plugin must be instantiable: " + className, e);
            }
        }
        return plugins;
    }

    /** The plugin instances a restored state holds, each exactly once, in registration order. */
    private static List<ISimulationPlugin> uniquePlugins(SimulationRestorer.RestoredState state) {
        java.util.LinkedHashSet<ISimulationPlugin> unique = new java.util.LinkedHashSet<>();
        state.tickPlugins().forEach(p -> unique.add(p.plugin()));
        state.instructionInterceptors().forEach(p -> unique.add((ISimulationPlugin) p.interceptor()));
        state.deathHandlers().forEach(p -> unique.add((ISimulationPlugin) p.handler()));
        state.birthHandlers().forEach(p -> unique.add((ISimulationPlugin) p.handler()));
        return new ArrayList<>(unique);
    }

    /**
     * Serializes the live simulation the way the engine does (serializer for organisms, encoder
     * for cells and counters), then rebuilds it with the restorer from that snapshot.
     */
    private SimulationRestorer.RestoredState restore(
            Simulation live, IRandomProvider liveProvider, List<ISimulationPlugin> plugins, int parallelism) {
        OrganismStateSerializer serializer = new OrganismStateSerializer();
        List<OrganismState> states = live.getOrganisms().stream().map(serializer::serialize).toList();

        // The engine labels the state after simulation tick T with T, while the simulation's own
        // counter already stands at T + 1 at that point; the snapshot must carry the engine's label.
        long snapshotTick = live.getCurrentTick() - 1;
        DeltaCodec.Encoder encoder = new DeltaCodec.Encoder("resume-test", (int) live.getEnvironment().getTotalCells(), 1, 1, 1);
        Optional<TickDataChunk> chunk = encoder.captureTick(
                snapshotTick, live.getEnvironment(), states,
                live.getTotalOrganismsCreatedCount(), live.getTotalUniqueGenomesCount(), live.getAllGenomesEverSeen(),
                ByteString.copyFrom(liveProvider.saveState()), pluginStates(plugins));
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
    private static List<List<String>> tick(Simulation sim, int n) {
        List<List<String>> trajectory = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            sim.tick();
            List<Organism> organisms = sim.getOrganisms();
            for (Organism organism : organisms) {
                assertThat(organism.isInstructionFailed())
                        .as("organism %d failed at tick %d: %s", organism.getId(), sim.getCurrentTick(), organism.getFailureReason())
                        .isFalse();
            }
            List<String> state = new ArrayList<>(organisms.size() + 1);
            for (Organism organism : organisms) {
                state.add(describe(organism));
            }
            state.add(describe(sim.getEnvironment()));
            trajectory.add(state);
        }
        return trajectory;
    }

    /**
     * Every piece of state an organism carries, as one line. A resumed run has to reproduce all of
     * it, not just position and energy — a difference in a stack, a saved register snapshot or the
     * persistent store changes the trajectory just as surely, only later and less visibly.
     */
    private static String describe(Organism o) {
        StringBuilder line = new StringBuilder(256);
        line.append("organism=").append(o.getId())
            .append(" parent=").append(o.getParentId())
            .append(" born=").append(o.getBirthTick())
            .append(" program=").append(o.getProgramId())
            .append(" genome=").append(o.getGenomeHash())
            .append(" ip=").append(Arrays.toString(o.getIp()))
            .append(" dv=").append(Arrays.toString(o.getDv()))
            .append(" start=").append(Arrays.toString(o.getInitialPosition()))
            .append(" er=").append(o.getEr())
            .append(" sr=").append(o.getSr())
            .append(" mr=").append(o.getMr())
            .append(" dead=").append(o.isDead())
            .append(" deathTick=").append(o.getDeathTick())
            .append(" failed=").append(o.isInstructionFailed())
            .append(" reason=").append(o.getFailureReason())
            .append(" proc=").append(o.getCurrentProcLabelHash())
            .append(" activeDp=").append(o.getActiveDpIndex())
            .append(" dps=").append(vectors(o.getDps()))
            .append(" registers=").append(values(Arrays.asList(o.getRegisters())))
            .append(" dataStack=").append(values(new ArrayList<>(o.getDataStack())))
            .append(" locationStack=").append(vectors(new ArrayList<>(o.getLocationStack())))
            .append(" callStack=").append(frames(o.getCallStack()))
            .append(" persistent=").append(persistent(o.getPersistentRegisterState()));
        return line.toString();
    }

    /** Every occupied cell of the world, so a molecule restored to the wrong place shows up. */
    private static String describe(Environment environment) {
        StringBuilder line = new StringBuilder(256).append("world=");
        int totalCells = environment.getTotalCells();
        for (int index = 0; index < totalCells; index++) {
            int[] coord = environment.getCoordinateFromIndex(index);
            int molecule = environment.getMolecule(coord).toInt();
            int owner = environment.getOwnerId(coord);
            if (molecule != 0 || owner != 0) {
                line.append(index).append(':').append(molecule).append('/').append(owner).append(' ');
            }
        }
        return line.toString();
    }

    private static String values(List<Object> registerValues) {
        StringBuilder out = new StringBuilder("[");
        for (Object value : registerValues) {
            out.append(value instanceof int[] vector ? Arrays.toString(vector) : String.valueOf(value)).append(',');
        }
        return out.append(']').toString();
    }

    private static String vectors(List<int[]> coordinates) {
        StringBuilder out = new StringBuilder("[");
        for (int[] coordinate : coordinates) {
            out.append(Arrays.toString(coordinate)).append(',');
        }
        return out.append(']').toString();
    }

    private static String frames(Deque<Organism.ProcFrame> callStack) {
        StringBuilder out = new StringBuilder("[");
        for (Organism.ProcFrame frame : callStack) {
            out.append(frame.labelHash())
               .append('@').append(Arrays.toString(frame.absoluteReturnIp()))
               .append("<-").append(Arrays.toString(frame.absoluteCallIp()))
               .append(" saved=").append(frame.savedRegisters() == null
                       ? "none" : values(Arrays.asList(frame.savedRegisters())))
               .append(" bindings=").append(new TreeMap<>(frame.parameterBindings()))
               .append(';');
        }
        return out.append(']').toString();
    }

    private static String persistent(Map<Integer, Object[]> persistentState) {
        StringBuilder out = new StringBuilder("{");
        for (Map.Entry<Integer, Object[]> entry : new TreeMap<>(persistentState).entrySet()) {
            out.append(entry.getKey()).append('=')
               .append(values(Arrays.asList(entry.getValue()))).append(';');
        }
        return out.append('}').toString();
    }
}
