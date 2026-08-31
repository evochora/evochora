package org.evochora.runtime.worldgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.evochora.runtime.Simulation;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.spi.IRandomProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.typesafe.config.ConfigFactory;

/**
 * Unit tests for the {@link SeedEnergyCreator}.
 * Verifies that energy is seeded correctly at tick 0 and respects existing molecules.
 */
public class SeedEnergyCreatorTest {

    private IRandomProvider createDeterministicRandomProvider(long seed) {
        return new IRandomProvider() {
            private final Random random = new Random(seed);
            @Override
            public long seed() { return seed; }
            @Override
            public Random asJavaRandom() {
                return random;
            }
            @Override
            public double nextDouble() { return random.nextDouble(); }
            @Override
            public int nextInt(int bound) { return random.nextInt(bound); }
            @Override
            public byte[] saveState() { return new byte[0]; }
            @Override
            public void loadState(byte[] state) { }
        };
    }

    @Test
    @Tag("unit")
    void seedsTheConfiguredPercentageOfTheEnvironment() {
        Environment env = new Environment(new int[]{10, 10}, false); // 100 cells
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("percentage", 0.5); // 50%
        configMap.put("amount", 100);
        configMap.put("amountVariance", 0.0);

        SeedEnergyCreator creator = new SeedEnergyCreator(createDeterministicRandomProvider(42L), ConfigFactory.parseMap(configMap));

        // Create mock Simulation
        Simulation sim = mock(Simulation.class);
        when(sim.getEnvironment()).thenReturn(env);
        when(sim.getCurrentTick()).thenReturn(0L);

        creator.execute(sim);

        long energyCellCount = countEnergyCells(env);
        assertThat(energyCellCount).isEqualTo(50);
    }

    @Test
    @Tag("unit")
    void runsOnlyAtTickZero() {
        Environment env = new Environment(new int[]{10, 10}, false);
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("percentage", 0.5);
        configMap.put("amount", 100);

        SeedEnergyCreator creator = new SeedEnergyCreator(createDeterministicRandomProvider(42L), ConfigFactory.parseMap(configMap));

        // Create mock Simulation
        Simulation sim = mock(Simulation.class);
        when(sim.getEnvironment()).thenReturn(env);

        when(sim.getCurrentTick()).thenReturn(1L);
        creator.execute(sim); // Try to run at tick 1

        assertThat(countEnergyCells(env)).isZero();

        when(sim.getCurrentTick()).thenReturn(0L);
        creator.execute(sim); // Run at tick 0
        assertThat(countEnergyCells(env)).isEqualTo(50);

        creator.execute(sim); // Try to run again at tick 0
        assertThat(countEnergyCells(env)).isEqualTo(50); // Count should not change
    }

    @Test
    @Tag("unit")
    void appliesAmountVarianceCorrectly() {
        Environment env = new Environment(new int[]{1, 1}, false);
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("percentage", 1.0); // 100%
        configMap.put("amount", 100);
        configMap.put("amountVariance", 0.2);

        // Use a predictable random provider
        IRandomProvider seededRandom = createDeterministicRandomProvider(42L);
        SeedEnergyCreator creator = new SeedEnergyCreator(seededRandom, ConfigFactory.parseMap(configMap));

        // Create mock Simulation
        Simulation sim = mock(Simulation.class);
        when(sim.getEnvironment()).thenReturn(env);
        when(sim.getCurrentTick()).thenReturn(0L);

        creator.execute(sim);

        Molecule molecule = env.getMolecule(0, 0);
        // With a constant seed the variance is deterministic. The cell is drawn before its
        // amount, so the draw that decides the variance is the second one taken from the seeded
        // source, following the one that picked the cell:
        // amount * (1.0 + (random.nextDouble() * 2.0 - 1.0) * amountVariance)
        assertThat(molecule.toScalarValue()).isEqualTo(82);
    }

    @Test
    @Tag("unit")
    void doesNotOverwriteExistingMolecules() {
        Environment env = new Environment(new int[]{2, 1}, false);
        env.setMolecule(new Molecule(org.evochora.runtime.Config.TYPE_CODE, 123), new int[]{0, 0});

        Map<String, Object> configMap = new HashMap<>();
        // Half of the two cells, so exactly one is asked for - and the only empty one is where
        // it has to land.
        configMap.put("percentage", 0.5);
        configMap.put("amount", 100);

        SeedEnergyCreator creator = new SeedEnergyCreator(createDeterministicRandomProvider(42L), ConfigFactory.parseMap(configMap));

        // Create mock Simulation
        Simulation sim = mock(Simulation.class);
        when(sim.getEnvironment()).thenReturn(env);
        when(sim.getCurrentTick()).thenReturn(0L);

        creator.execute(sim);

        assertThat(env.getMolecule(0, 0).type()).isEqualTo(org.evochora.runtime.Config.TYPE_CODE);
        assertThat(env.getMolecule(1, 0).type()).isEqualTo(org.evochora.runtime.Config.TYPE_ENERGY);
        assertThat(countEnergyCells(env)).isEqualTo(1); // Only the one empty cell should be filled
    }

    @Test
    @Tag("unit")
    void failsWhenTheEnvironmentHasTooFewEmptyCells() {
        // Every cell is taken, so no draw can ever succeed and the requested amount is
        // unreachable. The search has to end in a statement about that, not in a hanging loop.
        Environment env = new Environment(new int[]{4, 1}, false);
        for (int x = 0; x < 4; x++) {
            env.setMolecule(new Molecule(org.evochora.runtime.Config.TYPE_CODE, 123), new int[]{x, 0});
        }

        Map<String, Object> configMap = new HashMap<>();
        configMap.put("percentage", 0.5);
        configMap.put("amount", 100);

        SeedEnergyCreator creator = new SeedEnergyCreator(
                createDeterministicRandomProvider(42L), ConfigFactory.parseMap(configMap));

        Simulation sim = mock(Simulation.class);
        when(sim.getEnvironment()).thenReturn(env);
        when(sim.getCurrentTick()).thenReturn(0L);

        assertThatThrownBy(() -> creator.execute(sim))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too few empty cells");
    }

    private long countEnergyCells(Environment env) {
        final long[] count = {0};
        final int[] shape = env.getShape();
        final int dims = shape.length;
        int[] currentCoord = new int[dims];

        iterateCoordinates(shape, currentCoord, 0, () -> {
            if (env.getMolecule(currentCoord).type() == org.evochora.runtime.Config.TYPE_ENERGY) {
                count[0]++;
            }
        });
        return count[0];
    }

    private void iterateCoordinates(int[] shape, int[] coord, int dim, Runnable action) {
        if (dim == shape.length) {
            action.run();
            return;
        }

        for (int i = 0; i < shape[dim]; i++) {
            coord[dim] = i;
            iterateCoordinates(shape, coord, dim + 1, action);
        }
    }
}