package org.evochora.runtime.worldgen;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.spi.IRandomProvider;
import org.evochora.runtime.spi.ITickPlugin;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A geyser-based energy distribution tick plugin. It creates geysers that erupt
 * at regular intervals, distributing energy to nearby cells.
 * <p>
 * The number of geysers scales with the environment size via a configurable
 * percentage of total cells. An eruption goes to one of the geyser's axis-adjacent
 * neighbours: the neighbours are visited in random order, and the first one that is empty
 * and has no organism-owned cell within the safety radius receives the energy. Every valid
 * neighbour is equally likely to be chosen; a geyser with no valid neighbour skips the
 * eruption. In a bounded world a neighbour beyond the edge counts as empty and unowned, and
 * energy placed there is lost.
 * <ul>
 *   <li><b>percentage:</b> Fraction of total cells to place as geyser sources (0.0–1.0).</li>
 *   <li><b>interval:</b> Tick interval between eruptions.</li>
 *   <li><b>amount:</b> Energy amount placed per eruption.</li>
 *   <li><b>safetyRadius:</b> Radius around placement that must be unowned.</li>
 * </ul>
 */
public class GeyserCreator implements ITickPlugin {

    private final double geyserPercentage;
    private final int tickInterval;
    /** Radius around a candidate cell that must be free of organism-owned cells. */
    private final int safetyRadius;
    private final Random random;
    /** The molecule every eruption places; the amount is fixed, so one instance serves all. */
    private final Molecule eruption;
    private List<int[]> geyserLocations = null; // Initialized on first call
    /** Visiting order of the {@code 2 * dims} neighbour directions, permuted per eruption. */
    private int[] directionOrder;
    /** Unit vector of the direction currently checked. */
    private int[] direction;
    /** The neighbour cell currently checked. */
    private int[] neighbour;

    /**
     * Creates a geyser-based energy distributor.
     *
     * @param randomProvider Source of randomness.
     * @param percentage Fraction of total environment cells to place as geyser sources.
     * @param interval Tick interval for eruptions.
     * @param amount Energy amount placed per eruption.
     * @param safetyRadius Radius around placement that must be unowned.
     */
    public GeyserCreator(IRandomProvider randomProvider, double percentage, int interval, int amount, int safetyRadius) {
        this.random = randomProvider.asJavaRandom();
        this.geyserPercentage = percentage;
        this.tickInterval = interval;
        this.safetyRadius = Math.max(0, safetyRadius);
        this.eruption = new Molecule(Config.TYPE_ENERGY, amount);
    }

    /**
     * Config-based constructor used by the simulation engine plugin loader.
     *
     * @param randomProvider Source of randomness.
     * @param config Configuration object containing geyser parameters.
     */
    public GeyserCreator(IRandomProvider randomProvider, com.typesafe.config.Config config) {
        this(
            randomProvider,
            config.getDouble("percentage"),
            config.getInt("interval"),
            config.getInt("amount"),
            config.getInt("safetyRadius")
        );
    }

    @Override
    public void execute(Simulation simulation) {
        Environment environment = simulation.getEnvironment();
        long currentTick = simulation.getCurrentTick();

        if (geyserLocations == null) {
            initializeGeysers(environment);
        }

        if (currentTick > 0 && currentTick % tickInterval == 0) {
            int dims = environment.getProperties().getDimensions();
            if (neighbour == null || neighbour.length != dims) {
                directionOrder = new int[2 * dims];
                direction = new int[dims];
                neighbour = new int[dims];
            }
            for (int[] geyserPos : geyserLocations) {
                erupt(geyserPos, environment);
            }
        }
    }

    /**
     * Places one eruption next to a geyser: the first neighbour in a random visiting order
     * that is empty and has no owned cell within the safety radius. Visiting the neighbours in
     * a uniformly random order and stopping at the first valid one chooses uniformly among the
     * valid neighbours, without checking the ones after it.
     */
    private void erupt(int[] geyserPos, Environment environment) {
        int count = directionOrder.length;
        for (int i = 0; i < count; i++) {
            directionOrder[i] = i;
        }
        for (int i = count - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int swapped = directionOrder[i];
            directionOrder[i] = directionOrder[j];
            directionOrder[j] = swapped;
        }
        for (int k = 0; k < count; k++) {
            int axis = directionOrder[k] >> 1;
            direction[axis] = (directionOrder[k] & 1) == 0 ? -1 : 1;
            boolean inside = environment.getProperties().getTargetCoordinate(geyserPos, direction, neighbour);
            direction[axis] = 0;
            boolean empty = !inside || environment.getMoleculeIntAt(neighbour) == 0;
            if (empty && environment.isAreaUnowned(neighbour, this.safetyRadius)) {
                if (inside) {
                    environment.setMoleculeAt(neighbour, eruption);
                }
                return;
            }
        }
    }

    private void initializeGeysers(Environment environment) {
        geyserLocations = new ArrayList<>();
        int[] shape = environment.getShape();
        long totalCells = 1;
        for (int dim : shape) {
            totalCells *= dim;
        }
        int geyserCount = Math.max(1, (int) (geyserPercentage * totalCells));

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
