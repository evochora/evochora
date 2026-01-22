package org.evochora.runtime.worldgen;

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
 * Contains unit tests for the {@link SolarRadiationCreator} world generation strategy.
 * These tests verify that energy is placed correctly and that safety rules are respected.
 * They operate on an in-memory environment and do not require external resources.
 */
public class SolarRadiationStrategyTest {

    /**
     * Verifies that the solar radiation strategy correctly places an energy molecule
     * in an available, unowned cell in a dimension-agnostic way.
     * This is a unit test for the world generation logic.
     */
    @Test
    @Tag("unit")
    void placesEnergyInNDEnvironment_whenAreaIsUnowned() {
        // 3D world with a single cell ensures deterministic coordinate selection
        Environment env = new Environment(new int[]{1, 1, 1}, true);
        SolarRadiationCreator strat = new SolarRadiationCreator(1.0, 42, 0);

        // Create mock Simulation
        Simulation sim = mock(Simulation.class);
        when(sim.getEnvironment()).thenReturn(env);
        when(sim.getCurrentTick()).thenReturn(1L);

        strat.execute(sim);

        Molecule cell = env.getMolecule(0, 0, 0);
        assertThat(cell.type()).isEqualTo(Config.TYPE_ENERGY);
        assertThat(cell.toScalarValue()).isEqualTo(42);
    }

    /**
     * Verifies that the solar radiation strategy respects the configured safety radius
     * and does not place energy in or near an already owned cell.
     * This is a unit test for the world generation safety logic.
     */
    @Test
    @Tag("unit")
    void respectsSafetyRadius_andDoesNotPlaceEnergyNearOwnedCells() {
        // 3D world with single cell; mark it as owned to violate safety radius
        Environment env = new Environment(new int[]{1, 1, 1}, true);
        env.setOwnerId(7, 0, 0, 0);
        SolarRadiationCreator strat = new SolarRadiationCreator(1.0, 99, 1);

        // Create mock Simulation
        Simulation sim = mock(Simulation.class);
        when(sim.getEnvironment()).thenReturn(env);
        when(sim.getCurrentTick()).thenReturn(1L);

        strat.execute(sim);

        Molecule cell = env.getMolecule(0, 0, 0);
        // remains empty (CODE:0) because area is not unowned
        assertThat(cell.isEmpty()).isTrue();
    }
}