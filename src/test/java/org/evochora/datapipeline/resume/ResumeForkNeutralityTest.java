package org.evochora.datapipeline.resume;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.evochora.runtime.Simulation;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.label.LabelRewrite;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Organism;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Resume neutrality across a birth, including the mutations applied to the newborn.
 * <p>
 * Mutation is what the model is about, and it is the one part of a run that a resume can corrupt
 * without leaving a trace: the mutation plugins draw from the same random stream as everything else,
 * so a random state that is off by one draw places different mutations at different sites. The data
 * would still look like a plausible run. This scenario therefore interrupts once before the birth
 * and once after it, and compares the newborn molecule by molecule.
 * <p>
 * Kept apart from {@link ResumeNeutralityTest} because it needs a different configuration — mutation
 * rates raised so the plugins fire on the single birth instead of once in forty — and a genome laid
 * out for them to act on.
 */
@Tag("unit")
class ResumeForkNeutralityTest {

    private static final int SIZE = 64;
    private static final int PARENT_ENERGY = 30_000;

    /** Rows of inheritable genome when the operators must choose among several scan lines. */
    private static final int SEVERAL_GENOME_ROWS = 8;

    /** Mutations always apply, so the single birth exercises all four plugins. */
    private static final String MUTATING = ResumeNeutralityHarness.configJson(SIZE, 1.0);

    /**
     * As {@link #MUTATING}, with a namespace flip at every birth: the label matching strategy draws
     * the newborn's mask from the root random provider, after the mutation operators have drawn.
     */
    private static final String MUTATING_AND_FLIPPING = MUTATING.replace(
            "\"selectionSpread\": 50", "\"selectionSpread\": 50, \"namespaceFlipRate\": 1.0");

    /** Mutations never apply — the reference for showing that the plugins actually change something. */
    private static final String QUIET = ResumeNeutralityHarness.configJson(SIZE, 0.0);

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
    void resumedRun_reproducesTheBirthAndItsMutations() {
        assertForkNeutral(1);
    }

    @Test
    void resumedRun_reproducesTheBirthAndItsMutations_twoThreads() {
        assertForkNeutral(2);
    }

    /**
     * The operators of an uninterrupted run have handled many newborns before any given birth; the
     * operators of a resumed run are rebuilt fresh. Here the uninterrupted run's operators - and the
     * interrupted run's up to the pause - process a large body first, so the birth after the pause
     * meets freshly restored operators on one side and experienced ones on the other. The newborn
     * inherits several rows, so that duplication and insertion choose among several scan lines.
     */
    @Test
    void resumedRun_reproducesTheBirthAndItsMutations_afterTheOperatorsProcessedALargeBody() {
        assertForkNeutral(1, true);
    }

    /**
     * Without this, the scenario could pass while proving nothing: if no plugin ever acted on the
     * newborn, the run would be neutral for a birth that carries no mutation, and the hardest part
     * of the claim would go untested.
     */
    @Test
    void theBirthActuallyCarriesMutations() {
        List<String> mutated = genomeOfChildAfterBirth(MUTATING);
        List<String> untouched = genomeOfChildAfterBirth(QUIET);

        assertThat(untouched).as("inherited genome without mutation").isNotEmpty();
        assertThat(mutated)
                .as("the mutation plugins must change the newborn's genome; if this holds, the "
                        + "neutrality scenarios above are comparing a genome that mutation touched")
                .isNotEqualTo(untouched);
    }

    /** Runs past the fork and returns the child's cells, so two configurations can be compared. */
    private List<String> genomeOfChildAfterBirth(String configJson) {
        ResumeNeutralityHarness.Fixture fixture =
                ResumeNeutralityHarness.newFixture(configJson, SIZE, 1);
        simulations.add(fixture.sim());
        Organism parent = ForkProgram.place(fixture.sim(), fixture.env(), new int[]{0, 0}, PARENT_ENERGY);

        ResumeNeutralityHarness.tick(fixture.sim(), fixture.plugins(), ForkProgram.FORK_TICK + 2, true);

        Organism child = child(fixture.sim(), parent);
        return cellsOf(fixture.env(), child.getId());
    }

    /**
     * Runs the scenario twice — once uninterrupted, once with a pause before and after the birth —
     * and requires the trajectories to match tick for tick.
     */
    private void assertForkNeutral(int parallelism) {
        assertForkNeutral(parallelism, false);
    }

    /**
     * As {@link #assertForkNeutral(int)}; with {@code operatorsProcessedALargeBody} the birth handlers
     * of both runs first process a large newborn in a world of their own, before the first tick, and
     * the child inherits {@value #SEVERAL_GENOME_ROWS} rows instead of one.
     */
    private void assertForkNeutral(int parallelism, boolean operatorsProcessedALargeBody) {
        assertForkNeutral(parallelism, operatorsProcessedALargeBody, MUTATING);
    }

    /**
     * As {@link #assertForkNeutral(int, boolean)}, under a given configuration.
     *
     * @return The uninterrupted simulation, for what a scenario wants to check beyond the trajectory.
     */
    private Simulation assertForkNeutral(int parallelism, boolean operatorsProcessedALargeBody, String configJson) {
        int genomeRows = operatorsProcessedALargeBody ? SEVERAL_GENOME_ROWS : 1;
        int totalTicks = ForkProgram.FORK_TICK + 12;
        int pauseBeforeBirth = ForkProgram.FORK_TICK - 3;
        int pauseAfterBirth = ForkProgram.FORK_TICK + 4;

        ResumeNeutralityHarness.Fixture reference = newWorld(parallelism, Environment.TILE_SIDE, genomeRows, configJson);
        if (operatorsProcessedALargeBody) {
            ResumeNeutralityHarness.processLargeNewborn(reference.plugins());
        }
        List<List<String>> expected = ResumeNeutralityHarness.tick(reference.sim(), reference.plugins(), totalTicks, true);

        ResumeNeutralityHarness.Fixture interrupted = newWorld(parallelism, Environment.TILE_SIDE, genomeRows, configJson);
        if (operatorsProcessedALargeBody) {
            ResumeNeutralityHarness.processLargeNewborn(interrupted.plugins());
        }
        List<List<String>> actual = new ArrayList<>(
                ResumeNeutralityHarness.tick(interrupted.sim(), interrupted.plugins(), pauseBeforeBirth, true));

        SimulationRestorer.RestoredState beforeBirth = ResumeNeutralityHarness.restore(
                interrupted.sim(), interrupted.provider(), interrupted.plugins(), configJson, parallelism);
        simulations.add(beforeBirth.simulation());
        actual.addAll(ResumeNeutralityHarness.tick(beforeBirth.simulation(),
                ResumeNeutralityHarness.uniquePlugins(beforeBirth),
                pauseAfterBirth - pauseBeforeBirth, true));

        SimulationRestorer.RestoredState afterBirth = ResumeNeutralityHarness.restore(
                beforeBirth.simulation(), beforeBirth.randomProvider(),
                ResumeNeutralityHarness.uniquePlugins(beforeBirth), configJson, parallelism);
        simulations.add(afterBirth.simulation());
        actual.addAll(ResumeNeutralityHarness.tick(afterBirth.simulation(),
                ResumeNeutralityHarness.uniquePlugins(afterBirth),
                totalTicks - pauseAfterBirth, true));

        assertThat(actual.get(totalTicks - 1))
                .as("the run must have produced a child")
                .hasSizeGreaterThan(expected.get(0).size());

        ResumeNeutralityHarness.assertSameTrajectory(expected, actual, "parallelism " + parallelism);
        return reference.sim();
    }

    /**
     * The namespace flip draws from the root random provider like the mutation operators do, one
     * step after them. A resume must leave that draw where it was: the newborn gets the same mask,
     * and everything that draws afterwards continues as in the uninterrupted run.
     */
    @Test
    void resumedRun_reproducesABirthWhoseNamespaceFlipFires() {
        Simulation uninterrupted = assertForkNeutral(1, false, MUTATING_AND_FLIPPING);

        Organism parent = uninterrupted.getOrganisms().get(0);
        assertThat(child(uninterrupted, parent).getBirthMutations())
                .as("the flip must have fired, or the scenario shows nothing")
                .anyMatch(record -> LabelRewrite.MUTATION_KIND.equals(record.kind()));
    }

    /**
     * The label index is not persisted; a resume rebuilds it from the cells. Three ticks before the
     * fork the child's body stands in the world with its marker set, and its labels are no jump
     * targets — in the rebuilt index as little as in the one that was built cell by cell.
     */
    @Test
    void resumedRun_keepsTheLabelsOfABodyUnderConstructionOutOfTheLabelIndex() {
        int pauseBeforeBirth = ForkProgram.FORK_TICK - 3;
        ResumeNeutralityHarness.Fixture interrupted = newWorld(1);
        ResumeNeutralityHarness.tick(interrupted.sim(), interrupted.plugins(), pauseBeforeBirth, true);
        assertThat(jumpTargetOfTheGenomeLabel(interrupted.sim()))
                .as("uninterrupted: a marked label is no target")
                .isEqualTo(-1);

        SimulationRestorer.RestoredState restored = ResumeNeutralityHarness.restore(
                interrupted.sim(), interrupted.provider(), interrupted.plugins(), MUTATING, 1);
        simulations.add(restored.simulation());

        assertThat(jumpTargetOfTheGenomeLabel(restored.simulation()))
                .as("resumed: the rebuilt index leaves the marked label out as well")
                .isEqualTo(-1);
    }

    /** Resolves the inheritable genome's label value for the parent, as a jump of the parent would. */
    private static int jumpTargetOfTheGenomeLabel(Simulation simulation) {
        Organism parent = simulation.getOrganisms().get(0);
        return simulation.getEnvironment().getLabelIndex().findTarget(
                ForkProgram.GENOME_LABEL_HASH, parent.getId(), parent.getIp(), parent.getRandom());
    }

    /**
     * The memory layout of the grid is not part of the contract. A birth with mutations is where a
     * layout could leak into the trajectory: the mutation operators visit the newborn's cells and
     * draw randomness on the way, and the label index orders its candidates.
     */
    @Test
    void trajectoryAcrossTheBirth_isLayoutInvariant() {
        for (int parallelism : new int[]{1, 2}) {
            int totalTicks = ForkProgram.FORK_TICK + 12;
            ResumeNeutralityHarness.Fixture tiled = newWorld(parallelism);
            assertThat(tiled.plugins().stream().map(plugin -> plugin.getClass().getSimpleName()).toList())
                    .as("every production plugin takes part, so that none can depend on the layout unnoticed")
                    .containsExactlyInAnyOrder("SeedEnergyCreator", "GeyserCreator", "SolarRadiationCreator",
                            "EnergyVaultCreator", "DecayOnDeath", "GeneDuplicationPlugin",
                            "GeneDeletionPlugin", "GeneInsertionPlugin", "GeneSubstitutionPlugin");
            List<List<String>> expected = ResumeNeutralityHarness.tick(tiled.sim(), tiled.plugins(), totalTicks, true);
            ResumeNeutralityHarness.Fixture rowMajor = newWorld(parallelism, 1);
            List<List<String>> actual = ResumeNeutralityHarness.tick(rowMajor.sim(), rowMajor.plugins(), totalTicks, true);

            assertThat(actual.get(totalTicks - 1))
                    .as("the run must have produced a child")
                    .hasSizeGreaterThan(expected.get(0).size());
            ResumeNeutralityHarness.assertSameTrajectory(expected, actual,
                    "tile side " + Environment.TILE_SIDE + " vs 1 at parallelism " + parallelism);
        }
    }

    private ResumeNeutralityHarness.Fixture newWorld(int parallelism) {
        return newWorld(parallelism, Environment.TILE_SIDE);
    }

    private ResumeNeutralityHarness.Fixture newWorld(int parallelism, int tileSide) {
        return newWorld(parallelism, tileSide, 1);
    }

    private ResumeNeutralityHarness.Fixture newWorld(int parallelism, int tileSide, int genomeRows) {
        return newWorld(parallelism, tileSide, genomeRows, MUTATING);
    }

    private ResumeNeutralityHarness.Fixture newWorld(int parallelism, int tileSide, int genomeRows, String configJson) {
        ResumeNeutralityHarness.Fixture fixture =
                ResumeNeutralityHarness.newFixture(configJson, SIZE, parallelism, tileSide);
        simulations.add(fixture.sim());
        ForkProgram.place(fixture.sim(), fixture.env(), new int[]{0, 0}, PARENT_ENERGY, genomeRows);
        return fixture;
    }

    private static Organism child(Simulation simulation, Organism parent) {
        return simulation.getOrganisms().stream()
                .filter(o -> Integer.valueOf(parent.getId()).equals(o.getParentId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the parent did not reproduce"));
    }

    /** The child's cells as text in flat-index order, so two runs can be compared and differences read off. */
    private static List<String> cellsOf(Environment environment, int organismId) {
        List<String> cells = new ArrayList<>();
        environment.visitCellsOwnedBy(organismId, cell ->
                cells.add(environment.getProperties().toFlatIndex(cell.coordinate()) + ":" + cell.moleculeInt()));
        return cells;
    }
}
