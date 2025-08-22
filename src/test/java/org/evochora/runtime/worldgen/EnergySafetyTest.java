package org.evochora.runtime.worldgen;

import org.evochora.runtime.Config;
import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class EnergySafetyTest {

    @Test
    void solar_does_not_place_within_safety_radius_of_owned_cells() {
        Environment env = new Environment(new int[]{5, 5}, true);
        // Mark center owned
        env.setOwnerId(99, 2, 2);

        // High probability, radius 2
        SolarRadiationCreator solar = new SolarRadiationCreator(1.0, 11, 2);

        // Run several attempts
        for (int i = 0; i < 50; i++) {
            solar.distributeEnergy(env, i + 1);
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

    @Test
    void geyser_sources_and_eruptions_respect_safety_radius() {
        Environment env = new Environment(new int[]{7, 7}, true);
        // Create an owned block in the middle (3x3) to create a forbidden zone with radius 1-2
        for (int x = 2; x <= 4; x++) {
            for (int y = 2; y <= 4; y++) {
                env.setOwnerId(7, x, y);
            }
        }

        // Deterministic RNG and safety radius 2
        GeyserCreator geyser = new GeyserCreator(new SeededRandomProvider(0L), 1, 1, 13, 2);

        // Initialize and then erupt
        geyser.distributeEnergy(env, 0);
        geyser.distributeEnergy(env, 1);

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


