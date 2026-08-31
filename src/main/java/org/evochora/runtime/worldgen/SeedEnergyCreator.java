package org.evochora.runtime.worldgen;

import java.util.Random;

import org.evochora.runtime.Simulation;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.spi.IRandomProvider;
import org.evochora.runtime.spi.ITickPlugin;

import com.typesafe.config.Config;

/**
 * A tick plugin that seeds a percentage of the environment's cells with a specified amount
 * of energy at the beginning of the simulation (tick 0).
 * <p>
 * The cells are drawn at random and an occupied one is passed over, so the requested number is
 * placed on empty cells without the world ever being listed. This plugin runs only once and is
 * stateless.
 * <ul>
 *   <li><b>percentage:</b> The percentage of the environment's cells to fill with energy.</li>
 *   <li><b>amount:</b> The base amount of energy for each seeded molecule.</li>
 *   <li><b>amountVariance:</b> A factor to vary the energy amount randomly. For
 *   example, 0.2 with an amount of 100 will result in energy values between 80
 *   and 120.</li>
 * </ul>
 */
public class SeedEnergyCreator implements ITickPlugin {

    /**
     * Draw attempts per cell to be seeded before the search is abandoned. Every draw but a
     * vanishing few hits an empty cell in a world that is empty except for its founding
     * organisms, so the limit is never approached; it exists so that a world too full to hold the
     * requested amount fails instead of looping forever.
     */
    private static final int MAX_ATTEMPTS_PER_CELL = 100;

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
        int totalCells = environment.getTotalCells();
        int cellsToSeed = (int) (totalCells * percentage);

        // Cells are drawn rather than collected: listing the world to sample a fraction of a
        // percent of it costs memory proportional to the world, and the draw costs none. A cell
        // that is already taken is skipped and the next one drawn, so a cell seeded a moment ago
        // is passed over the same way an organism's cell is - the environment holds that
        // knowledge, and nothing here has to repeat it.
        long attemptsLeft = (long) cellsToSeed * MAX_ATTEMPTS_PER_CELL;
        int seeded = 0;
        while (seeded < cellsToSeed) {
            if (attemptsLeft-- <= 0) {
                throw new IllegalStateException(
                        "Seeding stopped after " + ((long) cellsToSeed * MAX_ATTEMPTS_PER_CELL)
                        + " attempts with " + seeded + " of " + cellsToSeed
                        + " cells placed: the environment holds too few empty cells");
            }

            int flatIndex = random.nextInt(totalCells);
            if (environment.getMoleculeInt(flatIndex) != 0) {
                continue;
            }

            int finalAmount = amount;
            if (amountVariance > 0.0) {
                double variance = (random.nextDouble() * 2.0 - 1.0) * amountVariance; // -1.0 to 1.0
                finalAmount = (int) (amount * (1.0 + variance));
            }
            if (finalAmount > 0) {
                environment.setMoleculeByIndex(flatIndex,
                        new Molecule(org.evochora.runtime.Config.TYPE_ENERGY, finalAmount));
            }
            seeded++;
        }

        this.hasRun = true;
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
