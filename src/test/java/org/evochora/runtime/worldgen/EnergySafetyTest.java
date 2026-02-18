package org.evochora.runtime.worldgen;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Contains unit tests for the "safety radius" feature of world generation,
 * ensuring that energy sources are not placed too close to owned cells.
 * These tests operate on an in-memory environment and do not use external resources.
 */
public class EnergySafetyTest {

    /**
     * Verifies that the {@link SolarRadiationCreator} does not place energy within the
     * configured safety radius of an already owned cell.
     * This is a unit test for world generation logic.
     */
    @Test
    @Tag("unit")
    void solar_does_not_place_within_safety_radius_of_owned_cells() {
        Environment env = new Environment(new int[]{5, 5}, true);
        // Mark center owned
        env.setOwnerId(99, 2, 2);

        // High probability, radius 2
        SolarRadiationCreator solar = new SolarRadiationCreator(1.0, 11, 2);

        // Create mock Simulation
        Simulation sim = mock(Simulation.class);
        when(sim.getEnvironment()).thenReturn(env);

        // Run several attempts
        for (int i = 0; i < 50; i++) {
            when(sim.getCurrentTick()).thenReturn((long) (i + 1));
            solar.execute(sim);
        }

        // Assert any placed ENERGY cell satisfies the unowned-area predicate for the configured radius
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 5; y++) {
                Molecule m = env.getMolecule(x, y);
                if (m.type() == Config.TYPE_ENERGY) {
                    assertThat(env.isAreaUnowned(new int[]{x, y}, 2)).isTrue();
                }
            }
        }
    }

    /**
     * Verifies that the {@link GeyserCreator} respects the safety radius for both the
     * initial placement of the geyser source (a STRUCTURE) and the subsequent eruption
     * of energy.
     * This is a unit test for world generation logic.
     */
    @Test
    @Tag("unit")
    void geyser_sources_and_eruptions_respect_safety_radius() {
        Environment env = new Environment(new int[]{7, 7}, true);
        // Create an owned block in the middle (3x3) to create a forbidden zone with radius 1-2
        for (int x = 2; x <= 4; x++) {
            for (int y = 2; y <= 4; y++) {
                env.setOwnerId(7, x, y);
            }
        }

        // Deterministic RNG and safety radius 2
        GeyserCreator geyser = new GeyserCreator(new SeededRandomProvider(0L), 0.021, 1, 13, 2);

        // Create mock Simulation
        Simulation sim = mock(Simulation.class);
        when(sim.getEnvironment()).thenReturn(env);

        // Initialize and then erupt
        when(sim.getCurrentTick()).thenReturn(0L);
        geyser.execute(sim);
        when(sim.getCurrentTick()).thenReturn(1L);
        geyser.execute(sim);

        // Check any STRUCTURE (geyser source) was not placed violating safety radius
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                Molecule m = env.getMolecule(x, y);
                if (m.type() == Config.TYPE_STRUCTURE) {
                    assertThat(env.isAreaUnowned(new int[]{x, y}, 2)).isTrue();
                }
            }
        }
        // Check any ENERGY from eruption respects radius as well
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                Molecule m = env.getMolecule(x, y);
                if (m.type() == Config.TYPE_ENERGY) {
                    assertThat(env.isAreaUnowned(new int[]{x, y}, 2)).isTrue();
                }
            }
        }
    }
}