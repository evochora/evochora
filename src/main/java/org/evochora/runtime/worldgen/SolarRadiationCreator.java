package org.evochora.runtime.worldgen;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.spi.IRandomProvider;
import org.evochora.runtime.spi.ITickPlugin;

import java.util.Random;

/**
 * A solar radiation-based energy distribution tick plugin. It randomly spawns
 * energy in free cells based on a given probability.
 */
public class SolarRadiationCreator implements ITickPlugin {

    private final Random random;
    private final double spawnProbability;
    private final int spawnAmount;
    private final int safetyRadius;
    /**
     * Number of independent execution attempts per tick. Each attempt applies the
     * configured probability gate. Must be >= 1.
     */
    private final int executionsPerTick;

    /**
     * Creates a solar radiation distributor from its configuration.
     *
     * @param randomProvider Source of randomness.
     * @param config Configuration with {@code probability} (per execution, to spawn energy in a
     *               random free cell), {@code amount} (energy placed when an execution succeeds),
     *               {@code safetyRadius} (radius around the placement that must be unowned) and
     *               {@code executionsPerTick} (independent executions per tick, each with its own
     *               probability check; at least one runs).
     */
    public SolarRadiationCreator(IRandomProvider randomProvider, com.typesafe.config.Config config) {
        this.random = randomProvider.asJavaRandom();
        this.spawnProbability = config.getDouble("probability");
        this.spawnAmount = config.getInt("amount");
        this.safetyRadius = config.getInt("safetyRadius");
        this.executionsPerTick = Math.max(1, config.getInt("executionsPerTick"));
    }

    @Override
    public void execute(Simulation simulation) {
        Environment environment = simulation.getEnvironment();
        for (int attempt = 0; attempt < this.executionsPerTick; attempt++) {
            if (random.nextDouble() < this.spawnProbability) {
                int[] shape = environment.getShape();
                int[] coord = new int[shape.length];
                for (int i = 0; i < shape.length; i++) {
                    coord[i] = random.nextInt(shape[i]);
                }

                // Area must be unowned (distance to organism cells)
                if (environment.getMolecule(coord).isEmpty() && environment.isAreaUnowned(coord, this.safetyRadius)) {
                    environment.setMolecule(new Molecule(Config.TYPE_ENERGY, spawnAmount), coord);
                }
            }
        }
    }

    @Override
    public byte[] saveState() {
        // SolarRadiationCreator is stateless - no state to serialize
        return new byte[0];
    }

    @Override
    public void loadState(byte[] state) {
        // SolarRadiationCreator is stateless - nothing to restore
    }
}