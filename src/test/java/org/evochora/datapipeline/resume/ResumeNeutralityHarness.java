package org.evochora.datapipeline.resume;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.PluginState;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.utils.delta.DeltaCodec;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.spi.IBirthHandler;
import org.evochora.runtime.spi.IDeathHandler;
import org.evochora.runtime.spi.IInstructionInterceptor;
import org.evochora.runtime.spi.IRandomProvider;
import org.evochora.runtime.spi.ISimulationPlugin;
import org.evochora.runtime.spi.ITickPlugin;
import org.evochora.runtime.thermodynamics.ThermodynamicPolicyManager;

import com.google.protobuf.ByteString;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * What every resume neutrality scenario shares: building a simulation from a resolved configuration,
 * serializing it the way the engine does, rebuilding it with the restorer, and comparing the whole
 * state tick by tick.
 * <p>
 * The scenarios themselves — which organisms exist and what they do — belong to the individual tests.
 * Keeping the machinery here means a second scenario cannot quietly compare less than the first.
 */
final class ResumeNeutralityHarness {

    static final long SEED = 42L;

    private ResumeNeutralityHarness() {}

    /**
     * Every plugin a neutrality run configures. {@link PluginCoverageTest} keeps this complete by
     * looking at the code, so that a new plugin cannot slip past unnoticed.
     * <p>
     * The mutation rate is a parameter because the scenarios need different ones: a run without
     * reproduction leaves the birth handlers idle at any rate, while a run that forks needs them to
     * fire reliably instead of once in forty births.
     *
     * @param mutationRate probability each birth handler applies its mutation
     * @return the {@code plugins} array as JSON
     */
    static String pluginsJson(double mutationRate) {
        return """
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
                "options": { "duplicationRate": %1$s, "minNopSize": 8 } },
              { "className": "org.evochora.runtime.worldgen.GeneDeletionPlugin",
                "options": { "deletionRate": %1$s, "countExponent": 2.0 } },
              { "className": "org.evochora.runtime.worldgen.GeneInsertionPlugin",
                "options": { "mutationRate": %1$s,
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
                "options": { "substitutionRate": %1$s,
                             "CODE": { "weight": 1.0, "operationFlipWeight": 0.7,
                                       "familyFlipWeight": 0.2, "variantFlipWeight": 0.1 },
                             "REGISTER": { "weight": 1.0 },
                             "DATA": { "weight": 1.0, "exponent": 0.7 },
                             "LABEL": { "weight": 1.0, "bitflips": 1 },
                             "LABELREF": { "weight": 1.0, "bitflips": 1 } } }
            ]
            """.formatted(mutationRate);
    }

    /**
     * The resolved configuration a scenario is built from — the same text the restorer is given, so
     * both sides of the comparison start from one description of the world.
     *
     * @param size edge length of the square world
     * @param mutationRate probability each birth handler applies its mutation
     * @return the resolved configuration as JSON
     */
    static String configJson(int size, double mutationRate) {
        return configJson(size, pluginsJson(mutationRate));
    }

    /**
     * The resolved configuration with an explicit plugin list, for scenarios that need to control
     * exactly which plugins take part.
     *
     * @param size edge length of the square world
     * @param pluginsJson the {@code plugins} array as JSON
     * @return the resolved configuration as JSON
     */
    static String configJson(int size, String pluginsJson) {
        return """
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
            """.formatted(size, size, pluginsJson);
    }

    /** A simulation built from a configuration, with its provider and its registered plugins. */
    record Fixture(Environment env, Simulation sim, IRandomProvider provider, List<ISimulationPlugin> plugins) {}

    /**
     * Builds environment, simulation and plugins from a resolved configuration — the same way the
     * restorer builds the resumed simulation, so that a difference cannot come from the setup.
     *
     * @param configJson the resolved configuration
     * @param size edge length of the square world
     * @param parallelism thread count for the plan and execute phases
     * @return the assembled fixture, without any organisms yet
     */
    static Fixture newFixture(String configJson, int size, int parallelism) {
        Config config = ConfigFactory.parseString(configJson);
        Environment env = new Environment(new EnvironmentProperties(new int[]{size, size}, true),
                Environment.createLabelMatchingStrategy(config.getConfig("runtime.label-matching")));
        Simulation sim = new Simulation(env,
                new ThermodynamicPolicyManager(config.getConfig("runtime.thermodynamics")),
                config.getConfig("runtime.organism"), parallelism);
        IRandomProvider provider = new SeededRandomProvider(SEED);
        sim.setRandomProvider(provider);
        return new Fixture(env, sim, provider, registerConfiguredPlugins(sim, config, provider));
    }

    /**
     * Instantiates the configured plugins the way the restorer does and registers them, so the
     * reference run carries the same plugin state the resumed run will be rebuilt with.
     */
    private static List<ISimulationPlugin> registerConfiguredPlugins(
            Simulation sim, Config config, IRandomProvider provider) {
        List<ISimulationPlugin> plugins = new ArrayList<>();
        for (Config pluginConfig : config.getConfigList("plugins")) {
            String className = pluginConfig.getString("className");
            Config options = pluginConfig.hasPath("options")
                    ? pluginConfig.getConfig("options") : ConfigFactory.empty();
            try {
                Object plugin = Class.forName(className)
                        .getConstructor(IRandomProvider.class, Config.class)
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
    static List<ISimulationPlugin> uniquePlugins(SimulationRestorer.RestoredState state) {
        LinkedHashSet<ISimulationPlugin> unique = new LinkedHashSet<>();
        state.tickPlugins().forEach(p -> unique.add(p.plugin()));
        state.instructionInterceptors().forEach(p -> unique.add((ISimulationPlugin) p.interceptor()));
        state.deathHandlers().forEach(p -> unique.add((ISimulationPlugin) p.handler()));
        state.birthHandlers().forEach(p -> unique.add((ISimulationPlugin) p.handler()));
        return new ArrayList<>(unique);
    }

    /**
     * Serializes the live simulation the way the engine does (serializer for organisms, encoder for
     * cells and counters, plugin state per plugin), then rebuilds it with the restorer.
     *
     * @param live the simulation to capture
     * @param liveProvider its random provider, whose state goes into the snapshot
     * @param plugins the plugin instances whose state goes into the snapshot
     * @param configJson the resolved configuration the restorer rebuilds from
     * @param parallelism thread count for the resumed simulation
     * @return the restored state
     */
    static SimulationRestorer.RestoredState restore(
            Simulation live, IRandomProvider liveProvider, List<ISimulationPlugin> plugins,
            String configJson, int parallelism) {
        OrganismStateSerializer serializer = new OrganismStateSerializer();
        List<OrganismState> states = live.getOrganisms().stream().map(serializer::serialize).toList();

        // The engine labels the state after simulation tick T with T, while the simulation's own
        // counter already stands at T + 1 at that point; the snapshot must carry the engine's label.
        long snapshotTick = live.getCurrentTick() - 1;
        DeltaCodec.Encoder encoder = new DeltaCodec.Encoder(
                "resume-test", (int) live.getEnvironment().getTotalCells(), 1, 1, 1);
        Optional<TickDataChunk> chunk = encoder.captureTick(
                snapshotTick, live.getEnvironment(), states,
                live.getTotalOrganismsCreatedCount(), live.getTotalUniqueGenomesCount(),
                live.getAllGenomesEverSeen(),
                ByteString.copyFrom(liveProvider.saveState()), pluginStates(plugins));
        TickData snapshot = chunk.or(encoder::flushPartialChunk).orElseThrow().getSnapshot();

        SimulationMetadata metadata = SimulationMetadata.newBuilder()
                .setSimulationRunId("resume-test")
                .setInitialSeed(SEED)
                .setResolvedConfigJson(configJson)
                .build();
        return SimulationRestorer.restore(
                new ResumeCheckpoint(metadata, snapshot), new SeededRandomProvider(SEED), parallelism);
    }

    /** One state entry per plugin instance, in registration order, as the engine writes them. */
    private static List<PluginState> pluginStates(List<ISimulationPlugin> plugins) {
        List<PluginState> states = new ArrayList<>(plugins.size());
        for (ISimulationPlugin plugin : plugins) {
            states.add(PluginState.newBuilder()
                    .setPluginClass(plugin.getClass().getName())
                    .setStateBlob(ByteString.copyFrom(plugin.saveState()))
                    .build());
        }
        return states;
    }

    /**
     * Advances the simulation and records the complete state after every tick.
     *
     * @param sim the simulation to advance
     * @param n number of ticks
     * @param allowFailures whether organisms may fail instructions; a scenario that expects clean
     *                      execution passes {@code false} so a broken program shows up here rather
     *                      than as a puzzling state difference later
     * @return one entry per tick, each holding a line per organism plus one for the world
     */
    static List<List<String>> tick(Simulation sim, List<ISimulationPlugin> plugins, int n,
                                   boolean allowFailures) {
        List<List<String>> trajectory = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            sim.tick();
            List<Organism> organisms = sim.getOrganisms();
            if (!allowFailures) {
                for (Organism organism : organisms) {
                    assertThat(organism.isInstructionFailed())
                            .as("organism %d failed at tick %d: %s",
                                    organism.getId(), sim.getCurrentTick(), organism.getFailureReason())
                            .isFalse();
                }
            }
            List<String> state = new ArrayList<>(organisms.size() + 2);
            for (Organism organism : organisms) {
                state.add(describe(organism));
            }
            state.add(describe(sim.getEnvironment()));
            state.add(describe(plugins));
            trajectory.add(state);
        }
        return trajectory;
    }

    /**
     * The state of every plugin, keyed by class. A plugin that resumes with a fresh state changes the
     * run from that point on — the geyser positions are the clearest case — so it belongs in the
     * comparison like any organism field. Keyed rather than positional, because the write side groups
     * plugins by interface while the read side follows configuration order.
     */
    static String describe(List<ISimulationPlugin> plugins) {
        StringBuilder line = new StringBuilder(128).append("plugins=");
        for (Map.Entry<String, String> entry : pluginStateByClass(plugins).entrySet()) {
            line.append(entry.getKey()).append(':').append(entry.getValue()).append(' ');
        }
        return line.toString();
    }

    private static TreeMap<String, String> pluginStateByClass(List<ISimulationPlugin> plugins) {
        TreeMap<String, String> byClass = new TreeMap<>();
        for (ISimulationPlugin plugin : plugins) {
            byClass.put(plugin.getClass().getName(), Arrays.toString(plugin.saveState()));
        }
        return byClass;
    }

    /**
     * Every piece of state an organism carries, as one line. A resumed run has to reproduce all of
     * it, not just position and energy — a difference in a stack, a saved register snapshot or the
     * persistent store changes the trajectory just as surely, only later and less visibly.
     */
    static String describe(Organism o) {
        return new StringBuilder(256)
            .append("organism=").append(o.getId())
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
            .append(" failureCallStack=").append(o.getFailureCallStack() == null
                    ? "none" : frames(o.getFailureCallStack()))
            .append(" stackSavedDirty=").append(o.isStackSavedDirty())
            .append(" persistentDirty=").append(o.isPersistentDirty())
            .append(" persistent=").append(persistent(o.getPersistentRegisterState()))
            .toString();
    }

    /** Every occupied cell of the world, so a molecule restored to the wrong place shows up. */
    static String describe(Environment environment) {
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

    /**
     * Compares two trajectories and fails on the first tick that differs, naming the entry and the
     * part of it that changed.
     * <p>
     * A state line holds every field an organism carries, so a plain equality failure prints two
     * lines of several hundred characters and leaves the reader to spot the difference. When a
     * neutrality run does fail, the interesting question is always <em>which</em> field moved — that
     * is what points at the cause.
     *
     * @param expected the uninterrupted run
     * @param actual the interrupted and resumed run
     * @param scenario how to describe this run in the failure message
     */
    static void assertSameTrajectory(List<List<String>> expected, List<List<String>> actual, String scenario) {
        assertThat(actual).as("tick count (%s)", scenario).hasSameSizeAs(expected);
        for (int tick = 0; tick < expected.size(); tick++) {
            List<String> want = expected.get(tick);
            List<String> got = actual.get(tick);
            if (want.equals(got)) {
                continue;
            }
            assertThat(got).as("number of state entries after tick %d (%s)", tick + 1, scenario)
                    .hasSameSizeAs(want);
            for (int entry = 0; entry < want.size(); entry++) {
                assertThat(got.get(entry))
                        .as("after tick %d, entry %d differs (%s)%n  %s",
                                tick + 1, entry, scenario, firstDifference(want.get(entry), got.get(entry)))
                        .isEqualTo(want.get(entry));
            }
        }
    }

    /** Names the first field where two state lines diverge, with both values. */
    private static String firstDifference(String expected, String actual) {
        String[] expectedFields = expected.split(" (?=[a-zA-Z]+=)");
        String[] actualFields = actual.split(" (?=[a-zA-Z]+=)");
        for (int i = 0; i < Math.min(expectedFields.length, actualFields.length); i++) {
            if (!expectedFields[i].equals(actualFields[i])) {
                return "first differing field:%n    expected: %s%n    actual:   %s"
                        .formatted(abbreviate(expectedFields[i]), abbreviate(actualFields[i]));
            }
        }
        return "the lines differ in length, not in a shared field";
    }

    private static String abbreviate(String field) {
        return field.length() <= 200 ? field : field.substring(0, 200) + "…";
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
