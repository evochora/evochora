package org.evochora.runtime.worldgen;

import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.label.PreExpandedHammingStrategy;
import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Contains unit tests for the {@link GeyserCreator} world generation strategy.
 * These tests verify the logic of geyser placement and energy eruption.
 * They operate on an in-memory environment and do not require external resources.
 */
public class GeyserStrategyTest {

    /**
     * Verifies that the GeyserCreator correctly initializes a geyser source and subsequently
     * places energy in the environment when it erupts. The test checks a 3D environment
     * to ensure the logic is dimension-agnostic.
     * This is a unit test for the geyser world generation logic.
     */
    @Test
    @Tag("unit")
    void initializesGeysersAndPlacesEnergyOnAxisAdjacentCells_inND() {
        // 3D world 3x3x3 to allow neighbors around random geyser locations
        // A world this small cannot be tiled; the row-major layout (tile side 1) keeps the test's cell count.
        Environment env = new Environment(new EnvironmentProperties(new int[]{3, 3, 3}, true), new PreExpandedHammingStrategy(), 1);
        // percentage yields 1 geyser for 27 cells, interval=1 to trigger on tick 1, amount=77
        GeyserCreator strat = new GeyserCreator(
                new org.evochora.runtime.internal.services.SeededRandomProvider(0L),
                0.04, 1, 77, 2);

        // Create mock Simulation
        Simulation sim = mock(Simulation.class);
        when(sim.getEnvironment()).thenReturn(env);

        // First call initializes geysers but does not place energy until tick%interval==0 and >0
        when(sim.getCurrentTick()).thenReturn(0L);
        strat.execute(sim);
        when(sim.getCurrentTick()).thenReturn(1L);
        strat.execute(sim);

        // Verify at least one ENERGY cell exists with the correct amount
        boolean found = false;
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                for (int z = 0; z < 3; z++) {
                    Molecule m = env.getMolecule(x, y, z);
                    if (m.type() == Config.TYPE_ENERGY && m.toScalarValue() == 77) {
                        found = true;
                        break;
                    }
                }
                if (found) break;
            }
            if (found) break;
        }
        assertThat(found).isTrue();
    }

    /**
     * Verifies that every valid neighbour of a geyser is chosen over many eruptions: the
     * neighbours are visited in random order and the first valid one erupts, so none of the
     * four may be structurally excluded.
     */
    @Test
    @Tag("unit")
    void eruptionsReachEveryValidNeighbour() {
        Environment env = new Environment(new EnvironmentProperties(new int[]{8, 8}, true), new PreExpandedHammingStrategy(), 1);
        GeyserCreator geyser = geyserAt(env, new int[]{4, 4}, 1);

        int[] hits = eruptFor(geyser, env, new int[]{4, 4}, 200);

        assertThat(hits[0] + hits[1] + hits[2] + hits[3]).isEqualTo(200);
        // Uniform over four neighbours: 50 expected each; 20 is far below any plausible draw.
        for (int h : hits) {
            assertThat(h).isGreaterThan(20);
        }
    }

    /**
     * Verifies that a neighbour whose safety radius contains an organism-owned cell is never
     * chosen, while the neighbours with a free surrounding still erupt.
     */
    @Test
    @Tag("unit")
    void eruptionsSkipNeighbourWithOwnedSurrounding() {
        Environment env = new Environment(new EnvironmentProperties(new int[]{8, 8}, true), new PreExpandedHammingStrategy(), 1);
        GeyserCreator geyser = geyserAt(env, new int[]{4, 4}, 1);
        // Owned cell within radius 1 of the neighbour (5,4) only.
        env.setMolecule(new Molecule(Config.TYPE_DATA, 1), 7, new int[]{6, 4});

        int[] hits = eruptFor(geyser, env, new int[]{4, 4}, 200);

        assertThat(hits[1]).isZero();          // (5,4)
        assertThat(hits[0]).isPositive();      // (3,4)
        assertThat(hits[2]).isPositive();      // (4,3)
        assertThat(hits[3]).isPositive();      // (4,5)
    }

    /** A geyser plugin whose single geyser sits at a known position, restored through its state. */
    private static GeyserCreator geyserAt(Environment env, int[] position, int safetyRadius) {
        GeyserCreator geyser = new GeyserCreator(
                new org.evochora.runtime.internal.services.SeededRandomProvider(7L), 0.0, 1, 5, safetyRadius);
        java.nio.ByteBuffer state = java.nio.ByteBuffer.allocate(8 + 4 * position.length);
        state.putInt(1).putInt(position.length);
        for (int c : position) {
            state.putInt(c);
        }
        geyser.loadState(state.array());
        env.setMolecule(new Molecule(Config.TYPE_STRUCTURE, -1), position);
        return geyser;
    }

    /**
     * Erupts the geyser the given number of times, clearing the placed energy after each
     * eruption, and counts the hits on the four axis-adjacent neighbours in the order
     * -x, +x, -y, +y.
     */
    private static int[] eruptFor(GeyserCreator geyser, Environment env, int[] g, int eruptions) {
        Simulation sim = mock(Simulation.class);
        when(sim.getEnvironment()).thenReturn(env);
        when(sim.getCurrentTick()).thenReturn(1L);
        int[][] neighbours = {{g[0] - 1, g[1]}, {g[0] + 1, g[1]}, {g[0], g[1] - 1}, {g[0], g[1] + 1}};
        int[] hits = new int[4];
        for (int i = 0; i < eruptions; i++) {
            geyser.execute(sim);
            for (int n = 0; n < 4; n++) {
                if (env.getMolecule(neighbours[n]).type() == Config.TYPE_ENERGY) {
                    hits[n]++;
                    env.setMolecule(new Molecule(Config.TYPE_CODE, 0), neighbours[n]);
                }
            }
        }
        return hits;
    }
}