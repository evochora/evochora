package org.evochora.runtime.worldgen;

import com.typesafe.config.ConfigFactory;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.spi.IRandomProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the {@link SeedEnergyCreator}.
 * Verifies that energy is seeded correctly at tick 0 and respects existing molecules.
 */
public class SeedEnergyCreatorTest {

    private IRandomProvider createDeterministicRandomProvider(long seed) {
        return new IRandomProvider() {
            private final Random random = new Random(seed);
            @Override
            public Random asJavaRandom() {
                return random;
            }
            @Override
            public double nextDouble() { return random.nextDouble(); }
            @Override
            public int nextInt(int bound) { return random.nextInt(bound); }
            @Override
            public IRandomProvider deriveFor(String context, long salt) { return this; }
            @Override
            public byte[] saveState() { return new byte[0]; }
            @Override
            public void loadState(byte[] state) { }
        };
    }

    @Test
    @Tag("unit")
    void seedsCorrectPercentageOfEmptyCells() {
        Environment env = new Environment(new int[]{10, 10}, false); // 100 cells
        Map<String, Object> configMap = new HashMap<>();
        configMap.put("percentage", 0.5); // 50%
        configMap.put("amount", 100);
        configMap.put("amountVariance", 0.0);

        SeedEnergyCreator creator = new SeedEnergyCreator(createDeterministicRandomProvider(42L), ConfigFactory.parseMap(configMap));
        creator.distributeEnergy(env, 0);

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
        creator.distributeEnergy(env, 1); // Try to run at tick 1

        assertThat(countEnergyCells(env)).isZero();

        creator.distributeEnergy(env, 0); // Run at tick 0
        assertThat(countEnergyCells(env)).isEqualTo(50);

        creator.distributeEnergy(env, 0); // Try to run again at tick 0
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
        creator.distributeEnergy(env, 0);

        Molecule molecule = env.getMolecule(0, 0);
        // With a constant seed, the "random" variance is deterministic.
        // The formula is: amount * (1.0 + (random.nextDouble() * 2.0 - 1.0) * amountVariance)
        // With a seed of 42L, the first double from new Random(42L) is ~0.730878
        // 100 * (1.0 + (0.730878 * 2.0 - 1.0) * 0.2) = 100 * (1.0 + 0.461756 * 0.2) = 100 * 1.09235 = 109
        assertThat(molecule.toScalarValue()).isEqualTo(109);
    }

    @Test
    @Tag("unit")
    void doesNotOverwriteExistingMolecules() {
        Environment env = new Environment(new int[]{2, 1}, false);
        env.setMolecule(new Molecule(org.evochora.runtime.Config.TYPE_CODE, 123), new int[]{0, 0});

        Map<String, Object> configMap = new HashMap<>();
        configMap.put("percentage", 1.0); // Try to fill 100% of empty cells
        configMap.put("amount", 100);

        SeedEnergyCreator creator = new SeedEnergyCreator(createDeterministicRandomProvider(42L), ConfigFactory.parseMap(configMap));
        creator.distributeEnergy(env, 0);

        assertThat(env.getMolecule(0, 0).type()).isEqualTo(org.evochora.runtime.Config.TYPE_CODE);
        assertThat(env.getMolecule(1, 0).type()).isEqualTo(org.evochora.runtime.Config.TYPE_ENERGY);
        assertThat(countEnergyCells(env)).isEqualTo(1); // Only the one empty cell should be filled
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
