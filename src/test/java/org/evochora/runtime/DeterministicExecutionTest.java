package org.evochora.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.label.PreExpandedHammingStrategy;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.spi.IInstructionInterceptor;
import org.evochora.runtime.spi.IRandomProvider;
import org.evochora.runtime.spi.InterceptionContext;
import org.evochora.test.utils.SimulationTestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for deterministic execution: the trajectory of a simulation is a function of
 * seed, configuration and code alone — independent of the thread count and of pause/resume.
 * <p>
 * Each test is a minimal hand-built scenario that isolates one aspect of the contract:
 * <ul>
 *   <li>stochastic label selection under a fixed thread count and across thread counts,</li>
 *   <li>organism randomness ({@code RAND}) and label selection across a resume,</li>
 *   <li>visibility of same-tick environment writes across thread counts,</li>
 *   <li>rejection of shared randomness inside the parallel wave.</li>
 * </ul>
 */
@Tag("unit")
class DeterministicExecutionTest {

    private static final long SEED = 42L;
    private static final int LABEL_HASH = 0b1011_0110_0101_1001_1010 & Config.VALUE_MASK;
    private static final int JUMPERS = 16;
    private static final int JUMP_TICKS = 40;

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

    // ===================================================================================
    // Stochastic label selection
    // ===================================================================================

    @Test
    void labelSelection_sameSeedAndParallelism_isReproducible() {
        List<int[][]> first = runJumpers(4, JUMP_TICKS);
        List<int[][]> second = runJumpers(4, JUMP_TICKS);

        assertSameTrajectory(first, second, "two runs with identical seed and parallelism=4");
    }

    @Test
    void labelSelection_isParallelismInvariant() {
        List<int[][]> sequential = runJumpers(1, JUMP_TICKS);
        List<int[][]> parallel = runJumpers(4, JUMP_TICKS);

        assertSameTrajectory(sequential, parallel, "parallelism=1 vs parallelism=4");
    }

    @Test
    void labelSelection_isInvariantUnderParallelismScaling() {
        List<int[][]> unscaled = runJumpers(4, JUMP_TICKS);

        JumperWorld scaled = newJumperWorld(JUMPERS, 4);
        // Below 8 organisms sequential, from 8 on two threads: with 16 organisms this caps the
        // active thread count at 2 although 4 are configured.
        scaled.sim.setParallelismScaling(new int[]{8}, new int[]{2});
        List<int[][]> withScaling = tick(scaled.sim, JUMP_TICKS);

        assertSameTrajectory(unscaled, withScaling, "parallelism=4 vs parallelism-scaling capping at 2");
    }

    @Test
    void labelSelection_isResumeNeutral() {
        int pauseTick = 7;
        int totalTicks = 20;

        // Uninterrupted reference run.
        JumperWorld reference = newJumperWorld(1, 1);
        List<int[][]> expected = tick(reference.sim, totalTicks);

        // Interrupted run: tick to the pause, rebuild the simulation the way the restorer does,
        // continue to the end.
        JumperWorld interrupted = newJumperWorld(1, 1);
        List<int[][]> actual = new ArrayList<>(tick(interrupted.sim, pauseTick));
        Simulation resumed = resume(interrupted, new JumperWorld(1, 1));
        actual.addAll(tick(resumed, totalTicks - pauseTick));

        assertSameTrajectory(expected, actual, "uninterrupted vs resumed at tick " + pauseTick);
    }

    // ===================================================================================
    // Organism randomness (RAND)
    // ===================================================================================

    @Test
    void organismRandom_isResumeNeutral() {
        int pairs = 10;
        int pauseTick = 10; // after five SETI/RAND pairs

        RandWorld reference = newRandWorld();
        List<Integer> expected = randValues(reference.sim, reference.organism(), pairs * 2);

        RandWorld interrupted = newRandWorld();
        List<Integer> actual = new ArrayList<>(randValues(interrupted.sim, interrupted.organism(), pauseTick));
        RandWorld fresh = new RandWorld();
        Simulation resumed = resume(interrupted, fresh);
        actual.addAll(randValues(resumed, resumed.getOrganisms().get(0), pairs * 2 - pauseTick));

        assertThat(actual)
                .as("RAND values of an interrupted run must equal those of the uninterrupted run")
                .isEqualTo(expected);
    }

    // ===================================================================================
    // Visibility of same-tick environment writes
    // ===================================================================================

    @Test
    void tickVisibility_isParallelismInvariant() {
        int sequential = readAfterSameTickWrite(1);
        int parallel = readAfterSameTickWrite(2);

        assertThat(parallel)
                .as("a wave-1 read of a cell written in the same tick must not depend on the thread count")
                .isEqualTo(sequential);
    }

    @Test
    void tickVisibility_readsObserveStateFromTickStart() {
        int emptyCell = 0;

        assertThat(readAfterSameTickWrite(1))
                .as("all organisms act against the environment as it was at the start of the tick")
                .isEqualTo(emptyCell);
        assertThat(readAfterSameTickWrite(2))
                .as("all organisms act against the environment as it was at the start of the tick")
                .isEqualTo(emptyCell);
    }

    // ===================================================================================
    // Shared randomness is rejected inside the parallel wave
    // ===================================================================================

    @Test
    void rootProvider_rejectsDrawsFromInstructionInterceptor() {
        assertThat(rejectedDrawsInInterceptor(1)).as("single-thread wave").isEqualTo(JUMPERS);
        assertThat(rejectedDrawsInInterceptor(2)).as("multi-thread wave").isEqualTo(JUMPERS);
    }

    /**
     * Registers an interceptor that tries to draw from the simulation's root provider for every
     * organism and counts how often the draw is rejected. Returns that count after one tick.
     */
    private int rejectedDrawsInInterceptor(int parallelism) {
        JumperWorld world = newJumperWorld(JUMPERS, parallelism);
        AtomicInteger rejected = new AtomicInteger();
        world.sim.addInstructionInterceptor(new IInstructionInterceptor() {
            @Override
            public void intercept(InterceptionContext context) {
                try {
                    world.provider.nextInt(10);
                } catch (IllegalStateException expected) {
                    rejected.incrementAndGet();
                }
            }

            @Override
            public byte[] saveState() {
                return new byte[0];
            }

            @Override
            public void loadState(byte[] state) {
                // stateless
            }
        });

        world.sim.tick();
        return rejected.get();
    }

    // ===================================================================================
    // Scenario: jumping organisms with two own copies of the same label
    // ===================================================================================

    /**
     * A torus in which organism {@code i} lives in row {@code y = i} and, every tick, executes
     * {@code JMPI} to a label of which it owns two copies at different distances. With
     * {@code selectionSpread > 0} each jump is a weighted random choice between the copies, so
     * the instruction pointer sequence of every organism is a function of the randomness it
     * receives.
     */
    private final class JumperWorld {
        final Environment env;
        final Simulation sim;
        final IRandomProvider provider;

        JumperWorld(int organismCount, int parallelism) {
            env = new Environment(new EnvironmentProperties(new int[]{64, 64}, true),
                    new PreExpandedHammingStrategy(2, 100, 50, 50));
            sim = SimulationTestUtils.createSimulation(env, parallelism);
            simulations.add(sim);
            provider = new SeededRandomProvider(SEED);
            sim.setRandomProvider(provider);
            for (int row = 0; row < organismCount; row++) {
                Organism organism = Organism.create(sim, new int[]{0, row}, 10_000);
                sim.addOrganism(organism);
                layoutRow(organism);
            }
        }

        private void layoutRow(Organism organism) {
            int y = organism.getInitialPosition()[1];
            int id = organism.getId();
            placeJump(id, 0, y);
            env.setMolecule(new Molecule(Config.TYPE_LABEL, LABEL_HASH), id, new int[]{20, y});
            placeJump(id, 21, y);
            env.setMolecule(new Molecule(Config.TYPE_LABEL, LABEL_HASH), id, new int[]{44, y});
            placeJump(id, 45, y);
        }

        private void placeJump(int ownerId, int x, int y) {
            env.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName("JMPI")), ownerId, new int[]{x, y});
            env.setMolecule(new Molecule(Config.TYPE_DATA, LABEL_HASH), ownerId, new int[]{x + 1, y});
        }
    }

    private JumperWorld newJumperWorld(int organismCount, int parallelism) {
        return new JumperWorld(organismCount, parallelism);
    }

    private List<int[][]> runJumpers(int parallelism, int ticks) {
        return tick(newJumperWorld(JUMPERS, parallelism).sim, ticks);
    }

    // ===================================================================================
    // Scenario: one organism alternating SETI and RAND
    // ===================================================================================

    /**
     * A single organism on a line of {@code SETI %DR0 1000; RAND %DR0} pairs. Every second tick
     * {@code DR0} holds a fresh random value in {@code [0, 1000)}.
     */
    private final class RandWorld {
        final Environment env;
        final Simulation sim;
        final IRandomProvider provider;

        RandWorld() {
            env = new Environment(new EnvironmentProperties(new int[]{64}, true));
            sim = SimulationTestUtils.createSimulation(env, 1);
            simulations.add(sim);
            provider = new SeededRandomProvider(SEED);
            sim.setRandomProvider(provider);
            Organism organism = Organism.create(sim, new int[]{0}, 10_000);
            sim.addOrganism(organism);
            int seti = Instruction.getInstructionIdByName("SETI");
            int rand = Instruction.getInstructionIdByName("RAND");
            for (int x = 0; x + 5 <= 64; x += 5) {
                env.setMolecule(new Molecule(Config.TYPE_CODE, seti), organism.getId(), new int[]{x});
                env.setMolecule(new Molecule(Config.TYPE_DATA, 0), organism.getId(), new int[]{x + 1});
                env.setMolecule(new Molecule(Config.TYPE_DATA, 1000), organism.getId(), new int[]{x + 2});
                env.setMolecule(new Molecule(Config.TYPE_CODE, rand), organism.getId(), new int[]{x + 3});
                env.setMolecule(new Molecule(Config.TYPE_DATA, 0), organism.getId(), new int[]{x + 4});
            }
        }

        Organism organism() {
            return sim.getOrganisms().get(0);
        }
    }

    private RandWorld newRandWorld() {
        return new RandWorld();
    }

    /** Ticks the simulation and collects {@code DR0} after every {@code RAND} (every second tick). */
    private static List<Integer> randValues(Simulation sim, Organism organism, int ticks) {
        List<Integer> values = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            sim.tick();
            assertThat(organism.isInstructionFailed())
                    .as("instruction failed at tick %d: %s", sim.getCurrentTick(), organism.getFailureReason())
                    .isFalse();
            if (sim.getCurrentTick() % 2 == 0) {
                values.add(Molecule.fromInt((Integer) organism.readOperand(0)).toScalarValue());
            }
        }
        return values;
    }

    // ===================================================================================
    // Scenario: one organism writes a cell, another reads it in the same tick
    // ===================================================================================

    /**
     * Organism 0 executes {@code POKI} into the empty cell X; organism 1 executes {@code SCNI}
     * of cell X in the same tick. Returns what organism 1 read.
     */
    private int readAfterSameTickWrite(int parallelism) {
        Environment env = new Environment(new EnvironmentProperties(new int[]{32, 32}, true));
        Simulation sim = SimulationTestUtils.createSimulation(env, parallelism);
        simulations.add(sim);
        sim.setRandomProvider(new SeededRandomProvider(SEED));

        int[] cellX = new int[]{0, 1};
        int payload = new Molecule(Config.TYPE_DATA, 77).toInt();

        Organism writer = Organism.create(sim, new int[]{0, 0}, 10_000);
        sim.addOrganism(writer);
        writer.setDp(0, new int[]{0, 0});
        writer.writeOperand(0, payload);
        placeWithVector(env, writer, "POKI", 0, new int[]{0, 1});

        Organism reader = Organism.create(sim, new int[]{10, 0}, 10_000);
        sim.addOrganism(reader);
        reader.setDp(0, new int[]{0, 0});
        placeWithVector(env, reader, "SCNI", 0, new int[]{0, 1});

        sim.tick();

        assertThat(writer.isInstructionFailed()).as(writer.getFailureReason()).isFalse();
        assertThat(reader.isInstructionFailed()).as(reader.getFailureReason()).isFalse();
        assertThat(env.getMolecule(cellX).toInt()).as("POKI must have written cell X").isEqualTo(payload);
        return (Integer) reader.readOperand(0);
    }

    private static void placeWithVector(Environment env, Organism organism, String name, int register, int[] vector) {
        int[] pos = organism.getIp();
        env.setMolecule(new Molecule(Config.TYPE_CODE, Instruction.getInstructionIdByName(name)), organism.getId(), pos);
        pos = organism.getNextInstructionPosition(pos, organism.getDv(), env);
        env.setMolecule(new Molecule(Config.TYPE_DATA, register), organism.getId(), pos);
        for (int component : vector) {
            pos = organism.getNextInstructionPosition(pos, organism.getDv(), env);
            env.setMolecule(new Molecule(Config.TYPE_DATA, component), organism.getId(), pos);
        }
    }

    // ===================================================================================
    // Resume: rebuild a simulation from a live one the way SimulationRestorer does
    // ===================================================================================

    /**
     * Rebuilds {@code source} at its current tick into the freshly laid-out {@code target}
     * world: root RNG state is transferred via {@code saveState/loadState} and every organism is
     * reconstructed through {@link Organism#restore}. The target's own organisms (created by its
     * layout) are discarded and replaced, leaving the cell layout in place.
     */
    private Simulation resume(JumperWorld source, JumperWorld target) {
        return resumeInto(source.sim, source.provider, target.env, target.provider, target.sim);
    }

    private Simulation resume(RandWorld source, RandWorld target) {
        return resumeInto(source.sim, source.provider, target.env, target.provider, target.sim);
    }

    private Simulation resumeInto(Simulation source, IRandomProvider sourceProvider,
                                  Environment targetEnv, IRandomProvider targetProvider, Simulation layoutSim) {
        Simulation resumed = Simulation.forResume(
                targetEnv,
                source.getCurrentTick(),
                source.getTotalOrganismsCreatedCount(),
                source.getAllGenomesEverSeen(),
                source.getPolicyManager(),
                source.getOrganismConfig(),
                1);
        simulations.add(resumed);
        targetProvider.loadState(sourceProvider.saveState());
        resumed.setRandomProvider(targetProvider);
        for (Organism organism : source.getOrganisms()) {
            Organism restored = Organism.restore(organism.getId(), organism.getBirthTick())
                    .ip(organism.getIp())
                    .dv(organism.getDv())
                    .initialPosition(organism.getInitialPosition())
                    .energy(organism.getEr())
                    .entropy(organism.getSr())
                    .dataPointers(organism.getDps())
                    .activeDpIndex(organism.getActiveDpIndex())
                    .registers(organism.getRegisters())
                    .build(resumed);
            resumed.addOrganism(restored);
        }
        // The layout simulation only served to place cells with the right owner ids.
        layoutSim.shutdown();
        return resumed;
    }

    // ===================================================================================
    // Trajectory helpers
    // ===================================================================================

    /**
     * Ticks {@code n} times and records the instruction pointers of all organisms after each tick.
     * Every instruction must succeed: two runs failing identically would compare equal without
     * exercising the behaviour under test.
     */
    private static List<int[][]> tick(Simulation sim, int n) {
        List<int[][]> trajectory = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            sim.tick();
            for (Organism organism : sim.getOrganisms()) {
                assertThat(organism.isInstructionFailed())
                        .as("organism %d failed at tick %d: %s", organism.getId(), sim.getCurrentTick(), organism.getFailureReason())
                        .isFalse();
            }
            trajectory.add(ipsOf(sim));
        }
        return trajectory;
    }

    private static int[][] ipsOf(Simulation sim) {
        List<Organism> organisms = sim.getOrganisms();
        int[][] ips = new int[organisms.size()][];
        for (int i = 0; i < ips.length; i++) {
            ips[i] = organisms.get(i).getIp();
        }
        return ips;
    }

    private static void assertSameTrajectory(List<int[][]> expected, List<int[][]> actual, String description) {
        assertThat(actual).as("tick count").hasSameSizeAs(expected);
        for (int t = 0; t < expected.size(); t++) {
            assertThat(actual.get(t))
                    .as("instruction pointers after tick %d differ (%s)", t + 1, description)
                    .isDeepEqualTo(expected.get(t));
        }
    }
}
