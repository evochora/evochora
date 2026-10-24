package org.evochora.runtime.worldgen;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.spi.IRandomProvider;
import org.evochora.runtime.spi.ITickPlugin;

import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * A solar radiation-based energy distribution tick plugin. Every tick it spawns energy in random
 * free cells, at a rate that scales with the size of the world.
 * <p>
 * The rate is a fraction of all cells per tick: a world twice the size receives twice the
 * attempts, so the density of energy does not depend on the world's shape. Each attempt draws a
 * cell; an occupied cell, or one with an owned cell within the safety radius, takes nothing, so a
 * fuller world receives less.
 * <ul>
 *   <li><b>fraction:</b> Fraction of all cells that receive an attempt per tick (0.0–1.0).</li>
 *   <li><b>energy-amount:</b> Energy placed by an attempt that succeeds.</li>
 *   <li><b>safety-radius:</b> Radius around the placement that must be unowned.</li>
 * </ul>
 */
public class SolarRadiationCreator implements ITickPlugin {

    /** The keys the options block may carry. */
    private static final Set<String> OPTION_KEYS = Set.of("fraction", "energy-amount", "safety-radius");

    private final Random random;
    private final double fraction;
    private final int energyAmount;
    private final int safetyRadius;

    /**
     * Creates a solar radiation distributor from its configuration.
     *
     * @param randomProvider Source of randomness.
     * @param config Configuration with {@code fraction} (fraction of all cells that receive an
     *               attempt per tick), {@code energy-amount} (energy placed by an attempt that
     *               succeeds) and {@code safety-radius} (radius around the placement that must be
     *               unowned).
     * @throws IllegalArgumentException if the block carries an unknown key or {@code fraction}
     *         lies outside 0.0–1.0.
     */
    public SolarRadiationCreator(IRandomProvider randomProvider, com.typesafe.config.Config config) {
        requireKnownKeys(config);
        this.random = randomProvider.asJavaRandom();
        this.fraction = config.getDouble("fraction");
        if (!(fraction >= 0.0 && fraction <= 1.0)) {
            throw new IllegalArgumentException("fraction must lie between 0.0 and 1.0, is " + fraction);
        }
        this.energyAmount = config.getInt("energy-amount");
        this.safetyRadius = config.getInt("safety-radius");
    }

    @Override
    public void execute(Simulation simulation) {
        Environment environment = simulation.getEnvironment();
        int attempts = attemptsThisTick(environment.getTotalCells() * fraction, random);
        int[] shape = environment.getShape();
        for (int attempt = 0; attempt < attempts; attempt++) {
            int[] coord = new int[shape.length];
            for (int i = 0; i < shape.length; i++) {
                coord[i] = random.nextInt(shape[i]);
            }

            // Area must be unowned (distance to organism cells)
            if (environment.getMolecule(coord).isEmpty() && environment.isAreaUnowned(coord, this.safetyRadius)) {
                environment.setMolecule(new Molecule(Config.TYPE_ENERGY, energyAmount), coord);
            }
        }
    }

    /**
     * Turns an expected number of attempts into the number made this tick: the whole part always,
     * and one more with the probability of the remainder, so the mean over many ticks is the
     * expectation itself.
     *
     * @param expected The expected number of attempts per tick; not negative.
     * @param random The source the remainder is drawn from; one draw per call.
     * @return The number of attempts to make this tick.
     */
    static int attemptsThisTick(double expected, Random random) {
        int whole = (int) expected;
        return random.nextDouble() < expected - whole ? whole + 1 : whole;
    }

    private static void requireKnownKeys(com.typesafe.config.Config config) {
        for (String key : config.root().keySet()) {
            if (!OPTION_KEYS.contains(key)) {
                throw new IllegalArgumentException("Unknown key '" + key
                        + "' in the options of SolarRadiationCreator. Accepted keys: " + new TreeSet<>(OPTION_KEYS));
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
