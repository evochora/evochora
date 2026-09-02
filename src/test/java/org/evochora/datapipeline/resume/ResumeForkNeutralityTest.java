package org.evochora.datapipeline.resume;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.evochora.runtime.Simulation;
import org.evochora.runtime.isa.Instruction;
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

    /** Mutations always apply, so the single birth exercises all four plugins. */
    private static final String MUTATING = ResumeNeutralityHarness.configJson(SIZE, 1.0);

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
        int totalTicks = ForkProgram.FORK_TICK + 12;
        int pauseBeforeBirth = ForkProgram.FORK_TICK - 3;
        int pauseAfterBirth = ForkProgram.FORK_TICK + 4;

        ResumeNeutralityHarness.Fixture reference = newWorld(parallelism);
        List<List<String>> expected = ResumeNeutralityHarness.tick(reference.sim(), reference.plugins(), totalTicks, true);

        ResumeNeutralityHarness.Fixture interrupted = newWorld(parallelism);
        List<List<String>> actual = new ArrayList<>(
                ResumeNeutralityHarness.tick(interrupted.sim(), interrupted.plugins(), pauseBeforeBirth, true));

        SimulationRestorer.RestoredState beforeBirth = ResumeNeutralityHarness.restore(
                interrupted.sim(), interrupted.provider(), interrupted.plugins(), MUTATING, parallelism);
        simulations.add(beforeBirth.simulation());
        actual.addAll(ResumeNeutralityHarness.tick(beforeBirth.simulation(),
                ResumeNeutralityHarness.uniquePlugins(beforeBirth),
                pauseAfterBirth - pauseBeforeBirth, true));

        SimulationRestorer.RestoredState afterBirth = ResumeNeutralityHarness.restore(
                beforeBirth.simulation(), beforeBirth.randomProvider(),
                ResumeNeutralityHarness.uniquePlugins(beforeBirth), MUTATING, parallelism);
        simulations.add(afterBirth.simulation());
        actual.addAll(ResumeNeutralityHarness.tick(afterBirth.simulation(),
                ResumeNeutralityHarness.uniquePlugins(afterBirth),
                totalTicks - pauseAfterBirth, true));

        assertThat(actual.get(totalTicks - 1))
                .as("the run must have produced a child")
                .hasSizeGreaterThan(expected.get(0).size());

        ResumeNeutralityHarness.assertSameTrajectory(expected, actual, "parallelism " + parallelism);
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
            ResumeNeutralityHarness.Fixture rowMajor = newWorld(parallelism, 1);
            assertThat(rowMajor.plugins().stream().map(plugin -> plugin.getClass().getSimpleName()).toList())
                    .as("every production plugin takes part, so that none can depend on the layout unnoticed")
                    .containsExactlyInAnyOrder("SeedEnergyCreator", "GeyserCreator", "SolarRadiationCreator",
                            "DecayOnDeath", "LabelRewritePlugin", "GeneDuplicationPlugin", "GeneDeletionPlugin",
                            "GeneInsertionPlugin", "GeneSubstitutionPlugin");
            List<List<String>> expected = ResumeNeutralityHarness.tick(rowMajor.sim(), rowMajor.plugins(), totalTicks, true);
            ResumeNeutralityHarness.Fixture tiled = newWorld(parallelism, 32);
            List<List<String>> actual = ResumeNeutralityHarness.tick(tiled.sim(), tiled.plugins(), totalTicks, true);

            assertThat(actual.get(totalTicks - 1))
                    .as("the run must have produced a child")
                    .hasSizeGreaterThan(expected.get(0).size());
            ResumeNeutralityHarness.assertSameTrajectory(expected, actual,
                    "tile side 1 vs 32 at parallelism " + parallelism);
        }
    }

    private ResumeNeutralityHarness.Fixture newWorld(int parallelism) {
        return newWorld(parallelism, 1);
    }

    private ResumeNeutralityHarness.Fixture newWorld(int parallelism, int tileSide) {
        ResumeNeutralityHarness.Fixture fixture =
                ResumeNeutralityHarness.newFixture(MUTATING, SIZE, parallelism, tileSide);
        simulations.add(fixture.sim());
        ForkProgram.place(fixture.sim(), fixture.env(), new int[]{0, 0}, PARENT_ENERGY);
        return fixture;
    }

    private static Organism child(Simulation simulation, Organism parent) {
        return simulation.getOrganisms().stream()
                .filter(o -> Integer.valueOf(parent.getId()).equals(o.getParentId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the parent did not reproduce"));
    }

    /** The child's cells as sorted text, so two runs can be compared and differences read off. */
    private static List<String> cellsOf(Environment environment, int organismId) {
        List<String> cells = new ArrayList<>();
        int totalCells = environment.getTotalCells();
        for (int index = 0; index < totalCells; index++) {
            int[] coord = environment.getCoordinateFromIndex(index);
            if (environment.getOwnerId(coord) == organismId) {
                cells.add(index + ":" + environment.getMolecule(coord).toInt());
            }
        }
        return cells;
    }
}
