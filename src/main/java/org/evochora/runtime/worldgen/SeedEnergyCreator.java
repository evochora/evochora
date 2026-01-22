package org.evochora.runtime.worldgen;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.evochora.runtime.Simulation;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.spi.IRandomProvider;
import org.evochora.runtime.spi.ITickPlugin;

import com.typesafe.config.Config;

/**
 * A tick plugin that seeds a percentage of empty cells with a specified amount
 * of energy at the beginning of the simulation (tick 0).
 * <p>
 * This plugin runs only once and is stateless.
 * <ul>
 *   <li><b>percentage:</b> The percentage of empty cells to fill with energy.</li>
 *   <li><b>amount:</b> The base amount of energy for each seeded molecule.</li>
 *   <li><b>amountVariance:</b> A factor to vary the energy amount randomly. For
 *   example, 0.2 with an amount of 100 will result in energy values between 80
 *   and 120.</li>
 * </ul>
 */
public class SeedEnergyCreator implements ITickPlugin {

    private final Random random;
    private final double percentage;
    private final int amount;
    private final double amountVariance;
    private boolean hasRun = false;

    /**
     * Creates a new SeedEnergyCreator based on the given configuration.
     *
     * @param randomProvider The source of randomness.
     * @param config The configuration object containing the creator's parameters.
     *               Must contain 'percentage' and 'amount'. 'amountVariance' is optional.
     */
    public SeedEnergyCreator(IRandomProvider randomProvider, Config config) {
        this.random = randomProvider.asJavaRandom();
        this.percentage = config.getDouble("percentage");
        this.amount = config.getInt("amount");
        if (config.hasPath("amountVariance")) {
            this.amountVariance = config.getDouble("amountVariance");
        } else {
            this.amountVariance = 0.0;
        }
    }

    @Override
    public void execute(Simulation simulation) {
        long currentTick = simulation.getCurrentTick();
        if (currentTick != 0 || hasRun) {
            return;
        }

        Environment environment = simulation.getEnvironment();
        final List<int[]> emptyCells = new ArrayList<>();
        final int[] shape = environment.getShape();
        final int dims = shape.length;
        int[] currentCoord = new int[dims];

        // Manually iterate over all coordinates in the N-dimensional space
        iterateCoordinates(shape, currentCoord, 0, () -> {
            if (environment.getMolecule(currentCoord).isEmpty()) {
                emptyCells.add(currentCoord.clone());
            }
        });

        Collections.shuffle(emptyCells, random);

        int cellsToSeed = (int) (emptyCells.size() * percentage);

        for (int i = 0; i < cellsToSeed; i++) {
            int[] coord = emptyCells.get(i);
            int finalAmount = amount;
            if (amountVariance > 0.0) {
                double variance = (random.nextDouble() * 2.0 - 1.0) * amountVariance; // -1.0 to 1.0
                finalAmount = (int) (amount * (1.0 + variance));
            }
            if (finalAmount > 0) {
                environment.setMolecule(new Molecule(org.evochora.runtime.Config.TYPE_ENERGY, finalAmount), coord);
            }
        }

        this.hasRun = true;
    }

    /**
     * Recursively iterates through all coordinates of an N-dimensional space.
     *
     * @param shape The shape of the space (dimensions).
     * @param coord The current coordinate being built.
     * @param dim The current dimension to iterate.
     * @param action The action to perform for each complete coordinate.
     */
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

    @Override
    public byte[] saveState() {
        // This creator is stateless as it runs only once at the beginning.
        return new byte[0];
    }

    @Override
    public void loadState(byte[] state) {
        // This creator is stateless, so there is nothing to restore.
        // hasRun is not part of the state because if a simulation is loaded
        // from a state > tick 0, this creator should not run again anyway.
    }
}
