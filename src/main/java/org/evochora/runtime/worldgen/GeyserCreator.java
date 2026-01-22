package org.evochora.runtime.worldgen;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.spi.IRandomProvider;
import org.evochora.runtime.spi.ITickPlugin;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * A geyser-based energy distribution tick plugin. It creates geysers that erupt
 * at regular intervals, distributing energy to nearby cells.
 */
public class GeyserCreator implements ITickPlugin {

    private final int geyserCount;
    private final int tickInterval;
    private final int energyAmount;
    /** Radius around a candidate cell that must be free of organism-owned cells. */
    private final int safetyRadius;
    private final Random random;
    private List<int[]> geyserLocations = null; // Initialized on first call

    /**
     * Creates a geyser-based energy distributor.
     *
     * @param randomProvider Source of randomness.
     * @param count Number of geysers to spawn.
     * @param interval Tick interval for eruptions.
     * @param amount Energy amount placed per eruption.
     * @param safetyRadius Radius around placement that must be unowned.
     */
    public GeyserCreator(IRandomProvider randomProvider, int count, int interval, int amount, int safetyRadius) {
        this.random = randomProvider.asJavaRandom();
        this.geyserCount = count;
        this.tickInterval = interval;
        this.energyAmount = amount;
        this.safetyRadius = Math.max(0, safetyRadius);
    }

    /**
     * Backward-compatible constructor for existing callers.
     * @param randomProvider Source of randomness.
     * @param count Number of geysers to spawn.
     * @param interval Tick interval for eruptions.
     * @param amount Energy amount placed per eruption.
     */
    public GeyserCreator(IRandomProvider randomProvider, int count, int interval, int amount) {
        this(randomProvider, count, interval, amount, 2);
    }

    /**
     * Config-based constructor for the new data pipeline.
     * @param randomProvider Source of randomness.
     * @param config Configuration object containing geyser parameters.
     */
    public GeyserCreator(IRandomProvider randomProvider, com.typesafe.config.Config config) {
        this(
            randomProvider,
            config.getInt("count"),
            config.getInt("interval"),
            config.getInt("amount"),
            config.getInt("safetyRadius")
        );
    }

    /**
     * Backward-compatible constructor for legacy code/tests.
     * @param count Number of geysers to spawn.
     * @param interval Tick interval for eruptions.
     * @param amount Energy amount placed per eruption.
     */
    public GeyserCreator(int count, int interval, int amount) {
        this(new org.evochora.runtime.internal.services.SeededRandomProvider(0L), count, interval, amount, 2);
    }

    @Override
    public void execute(Simulation simulation) {
        Environment environment = simulation.getEnvironment();
        long currentTick = simulation.getCurrentTick();

        if (geyserLocations == null) {
            initializeGeysers(environment);
        }

        if (currentTick > 0 && currentTick % tickInterval == 0) {
            for (int[] geyserPos : geyserLocations) {
                // Find all valid neighbor cells (empty and safe distance from owned cells) in N dimensions
                List<int[]> validTargets = new ArrayList<>();
                int dims = environment.getShape().length;
                for (int axis = 0; axis < dims; axis++) {
                    for (int delta : new int[]{-1, 1}) {
                        int[] checkPos = java.util.Arrays.copyOf(geyserPos, dims);
                        checkPos[axis] = checkPos[axis] + delta;
                        if (environment.getMolecule(checkPos).isEmpty() && environment.isAreaUnowned(checkPos, this.safetyRadius)) {
                            validTargets.add(checkPos);
                        }
                    }
                }

                // Choose a random valid target and place the energy
                if (!validTargets.isEmpty()) {
                    Collections.shuffle(validTargets, random);
                    int[] targetCell = validTargets.get(0);
                    environment.setMolecule(new Molecule(Config.TYPE_ENERGY, energyAmount), targetCell);
                }
            }
        }
    }

    private void initializeGeysers(Environment environment) {
        geyserLocations = new ArrayList<>();
        int[] shape = environment.getShape();
        for (int i = 0; i < geyserCount; i++) {
            int[] coord = null;
            // Try to find a safe source: cell is empty and safety radius is unowned
            for (int attempt = 0; attempt < 1000; attempt++) {
                int[] c = new int[shape.length];
                for (int d = 0; d < shape.length; d++) {
                    c[d] = random.nextInt(shape[d]);
                }
                if (environment.getMolecule(c).isEmpty() && environment.isAreaUnowned(c, this.safetyRadius)) {
                    coord = c;
                    break;
                }
            }
            if (coord == null) {
                // Fallback: no safe position found, skip
                continue;
            }
            geyserLocations.add(coord);
            // Mark the source itself as indestructible to avoid conflicts
            environment.setMolecule(new Molecule(Config.TYPE_STRUCTURE, -1), coord);
        }
    }

    @Override
    public byte[] saveState() {
        if (geyserLocations == null || geyserLocations.isEmpty()) {
            return new byte[0]; // Not initialized yet
        }

        // Calculate buffer size: count(4) + dimension(4) + (count * dimension * 4)
        int dimension = geyserLocations.get(0).length;
        int bufferSize = 4 + 4 + (geyserLocations.size() * dimension * 4);
        ByteBuffer buffer = ByteBuffer.allocate(bufferSize);

        // Write count and dimension
        buffer.putInt(geyserLocations.size());
        buffer.putInt(dimension);

        // Write all coordinates
        for (int[] coord : geyserLocations) {
            for (int c : coord) {
                buffer.putInt(c);
            }
        }

        return buffer.array();
    }

    @Override
    public void loadState(byte[] state) {
        if (state == null) {
            throw new IllegalArgumentException("GeyserCreator state cannot be null");
        }

        if (state.length == 0) {
            geyserLocations = null;
            return;
        }

        ByteBuffer buffer = ByteBuffer.wrap(state);
        int count = buffer.getInt();
        int dimension = buffer.getInt();

        geyserLocations = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int[] coord = new int[dimension];
            for (int d = 0; d < dimension; d++) {
                coord[d] = buffer.getInt();
            }
            geyserLocations.add(coord);
        }
    }
}