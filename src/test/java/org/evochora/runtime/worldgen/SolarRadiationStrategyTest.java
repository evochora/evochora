package org.evochora.runtime.worldgen;

import org.evochora.runtime.internal.services.SeededRandomProvider;
import java.util.Map;
import java.util.Random;
import com.typesafe.config.ConfigFactory;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.label.HammingLabelMatchingStrategy;
import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
    /** Builds a solar radiation creator from its configuration. */
    private static SolarRadiationCreator solar(double fraction, int amount, int safetyRadius) {
        return new SolarRadiationCreator(new SeededRandomProvider(0L), ConfigFactory.parseMap(Map.of(
                "fraction", fraction, "energy-amount", amount, "safety-radius", safetyRadius)));
    }

    @Test
    @Tag("unit")
    void placesEnergyInNDEnvironment_whenAreaIsUnowned() {
        // 3D world with a single cell ensures deterministic coordinate selection
        // A world this small cannot be tiled; the row-major layout (tile side 1) keeps the test's cell count.
        Environment env = new Environment(new EnvironmentProperties(new int[]{1, 1, 1}, true), new HammingLabelMatchingStrategy(), 1);
        SolarRadiationCreator strat = solar(1.0, 42, 0);

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
        // A world this small cannot be tiled; the row-major layout (tile side 1) keeps the test's cell count.
        Environment env = new Environment(new EnvironmentProperties(new int[]{1, 1, 1}, true), new HammingLabelMatchingStrategy(), 1);
        env.setOwnerId(7, 0, 0, 0);
        SolarRadiationCreator strat = solar(1.0, 99, 1);

        // Create mock Simulation
        Simulation sim = mock(Simulation.class);
        when(sim.getEnvironment()).thenReturn(env);
        when(sim.getCurrentTick()).thenReturn(1L);

        strat.execute(sim);

        Molecule cell = env.getMolecule(0, 0, 0);
        // remains empty (CODE:0) because area is not unowned
        assertThat(cell.isEmpty()).isTrue();
    }

    /**
     * A whole expected number of attempts is made every tick, with no draw deciding it.
     */
    @Test
    @Tag("unit")
    void aWholeExpectationIsMadeEveryTick() {
        Random random = new Random(1L);
        for (int tick = 0; tick < 100; tick++) {
            assertThat(SolarRadiationCreator.attemptsThisTick(2.0, random)).isEqualTo(2);
        }
    }

    /**
     * The remainder of the expectation is one more attempt on part of the ticks, so that the mean
     * over many ticks is the expectation.
     */
    @Test
    @Tag("unit")
    void theRemainderAddsAnAttemptOnItsShareOfTicks() {
        Random random = new Random(1L);
        int ticks = 10_000;
        long total = 0;
        for (int tick = 0; tick < ticks; tick++) {
            int attempts = SolarRadiationCreator.attemptsThisTick(2.3, random);
            assertThat(attempts).isBetween(2, 3);
            total += attempts;
        }
        assertThat((double) total / ticks).isCloseTo(2.3, org.assertj.core.data.Offset.offset(0.02));
    }

    /**
     * The fraction is taken of all cells: a fraction that gives one attempt per tick in a world of
     * a hundred cells places exactly one cell of energy in a tick.
     */
    @Test
    @Tag("unit")
    void theFractionIsTakenOfAllCells() {
        // A world this small cannot be tiled; the row-major layout (tile side 1) keeps the test's cell count.
        Environment env = new Environment(new EnvironmentProperties(new int[]{10, 10}, true), new HammingLabelMatchingStrategy(), 1);
        SolarRadiationCreator strat = solar(0.01, 5, 0);
        Simulation sim = mock(Simulation.class);
        when(sim.getEnvironment()).thenReturn(env);
        when(sim.getCurrentTick()).thenReturn(1L);

        strat.execute(sim);

        int energyCells = 0;
        for (int x = 0; x < 10; x++) {
            for (int y = 0; y < 10; y++) {
                if (env.getMolecule(x, y).type() == Config.TYPE_ENERGY) {
                    energyCells++;
                }
            }
        }
        assertThat(energyCells).isEqualTo(1);
    }

    /**
     * A fraction outside 0.0 to 1.0 is rejected at construction.
     */
    @Test
    @Tag("unit")
    void aFractionOutsideTheUnitRangeIsRejected() {
        assertThatThrownBy(() -> solar(1.5, 5, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fraction");
        assertThatThrownBy(() -> solar(-0.1, 5, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fraction");
    }

    /**
     * A key the plugin does not know is rejected, so that a renamed or misspelled key fails at
     * start instead of being ignored.
     */
    @Test
    @Tag("unit")
    void anUnknownKeyIsRejected() {
        assertThatThrownBy(() -> new SolarRadiationCreator(new SeededRandomProvider(0L), ConfigFactory.parseMap(Map.of(
                "fraction", 0.01, "energy-amount", 5, "safety-radius", 0, "probability", 0.25))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("probability")
                .hasMessageContaining("energy-amount");
    }
}
