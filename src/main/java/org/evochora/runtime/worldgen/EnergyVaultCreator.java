package org.evochora.runtime.worldgen;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

import org.evochora.runtime.Simulation;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.spi.IRandomProvider;
import org.evochora.runtime.spi.ITickPlugin;

import com.typesafe.config.Config;
import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * A tick plugin that places vaults of energy behind a shell of structure molecules and refills
 * the energy they have lost at a fixed interval.
 * <p>
 * A vault consists of a core of {@code ENERGY} molecules that belongs to nobody, wrapped in a
 * shell of {@code STRUCTURE} molecules that carries a negative owner id. An organism can tell a
 * cell it owns from an unowned one and from a foreign one, and nothing in the instruction set
 * lets it read the owner value itself, so a shell owned by a non-organism is indistinguishable
 * from another organism's body: {@code IFF} matches it, {@code IFV} does not, {@code SEEK} cannot
 * enter it, and reading it is charged the foreign rate rather than the unowned one. A reproduction
 * loop that clears unowned molecules out of its way therefore treats a vault as a body to go
 * around, while the same molecules left unowned would simply be swept aside.
 * <p>
 * <strong>The shell's shape.</strong> Cells are addressed by the Manhattan distance to the vault's
 * centre, which makes the shell an octahedron rather than a cube. Every vector operand passes
 * through {@code UnitVector.nearest} before it addresses a cell, so a step, a read and a write all
 * move along one axis by one cell; a path from outside changes its Manhattan distance by exactly
 * one per step and cannot cross the shell without entering it. The diagonal cells a cube would
 * also cover are unreachable and are left out, which in a world of {@code n} dimensions replaces
 * {@code 3^n - 1} shell cells with {@code 2n}.
 * <p>
 * <strong>The shell's hardness.</strong> A {@code PEEK} is charged per molecule value, so
 * {@code structureValue} is what a breach costs: with the read rules of the shipped configuration
 * a foreign structure molecule costs ten times its value in energy and as much in entropy. The
 * value is therefore chosen against the energy a core holds and against {@code max-entropy}, not
 * as a cosmetic weight.
 * <ul>
 *   <li><b>percentage:</b> Fraction of total cells to place as vault centres.</li>
 *   <li><b>coreRadius:</b> Manhattan radius of the energy core; {@code 0} is a single cell.</li>
 *   <li><b>wallThickness:</b> Number of shell layers around the core.</li>
 *   <li><b>energyAmount:</b> Energy placed in each core cell.</li>
 *   <li><b>structureValue:</b> Value of each shell molecule, which is what a breach costs.</li>
 *   <li><b>structureOwner:</b> Owner id of the shell; must be negative, because organism ids
 *       start at one and a positive value would collide with a living organism.</li>
 *   <li><b>interval:</b> Tick interval at which emptied core cells are refilled.</li>
 *   <li><b>safetyRadius:</b> Additional distance from organism-owned cells a placement keeps.</li>
 * </ul>
 */
public class EnergyVaultCreator implements ITickPlugin {

    /**
     * Draw attempts per vault before the placement is abandoned. A world holding only its founding
     * organisms offers a free spot on nearly every draw, so the limit is never approached; it
     * exists so that a world with no room for the requested vaults fails instead of looping.
     */
    private static final int MAX_PLACEMENT_ATTEMPTS = 1000;

    private static final int[] NO_CELLS = new int[0];

    private final Random random;
    private final double vaultPercentage;
    private final int coreRadius;
    private final int wallThickness;
    private final int structureOwner;
    private final int refillInterval;
    private final int safetyRadius;

    /**
     * The two molecules the plugin writes, built once. Both are immutable, so the refill path
     * writes them without allocating.
     */
    private final Molecule energyMolecule;
    private final Molecule shellMolecule;

    private boolean placed = false;

    /**
     * The next tick on which the refill runs. Every other tick leaves {@code execute} after one
     * comparison. The value follows {@code tick % interval == 0}, so it is a function of the tick
     * alone and a resume lands on the same grid as an uninterrupted run.
     */
    private long nextRefillTick = 0L;

    private int dimensions = 0;

    /**
     * The coordinates of every core cell of every vault, normalized and laid out one cell after
     * another. The refill walks this array; the shell is written once at placement and never read
     * back, so its cells are not kept.
     */
    private int[] coreCells = NO_CELLS;

    /** Receives one cell's coordinate for each grid access, so the refill allocates nothing. */
    private int[] coordBuffer = NO_CELLS;

    /**
     * Creates an energy vault creator.
     *
     * @param randomProvider Source of randomness.
     * @param percentage Fraction of total environment cells to place as vault centres.
     * @param coreRadius Manhattan radius of the energy core; 0 places a single cell.
     * @param wallThickness Number of shell layers around the core; at least 1.
     * @param energyAmount Energy placed in each core cell; positive.
     * @param structureValue Value of each shell molecule; positive.
     * @param structureOwner Owner id of the shell; must be negative.
     * @param interval Tick interval at which emptied core cells are refilled; at least 1.
     * @param safetyRadius Additional distance from organism-owned cells a placement keeps.
     */
    public EnergyVaultCreator(IRandomProvider randomProvider, double percentage, int coreRadius,
                              int wallThickness, int energyAmount, int structureValue,
                              int structureOwner, int interval, int safetyRadius) {
        if (percentage < 0.0) {
            throw new IllegalArgumentException("percentage must not be negative, is " + percentage);
        }
        if (coreRadius < 0) {
            throw new IllegalArgumentException("coreRadius must not be negative, is " + coreRadius);
        }
        if (wallThickness < 1) {
            throw new IllegalArgumentException("wallThickness must be at least 1, is " + wallThickness);
        }
        if (energyAmount <= 0) {
            throw new IllegalArgumentException("energyAmount must be positive, is " + energyAmount);
        }
        if (structureValue <= 0) {
            throw new IllegalArgumentException("structureValue must be positive, is " + structureValue);
        }
        if (structureOwner >= 0) {
            throw new IllegalArgumentException(
                    "structureOwner must be negative so that it cannot collide with an organism id, is "
                    + structureOwner);
        }
        if (interval < 1) {
            throw new IllegalArgumentException("interval must be at least 1, is " + interval);
        }
        if (safetyRadius < 0) {
            throw new IllegalArgumentException("safetyRadius must not be negative, is " + safetyRadius);
        }
        this.random = randomProvider.asJavaRandom();
        this.vaultPercentage = percentage;
        this.coreRadius = coreRadius;
        this.wallThickness = wallThickness;
        this.structureOwner = structureOwner;
        this.refillInterval = interval;
        this.safetyRadius = safetyRadius;
        this.energyMolecule = new Molecule(org.evochora.runtime.Config.TYPE_ENERGY, energyAmount);
        this.shellMolecule = new Molecule(org.evochora.runtime.Config.TYPE_STRUCTURE, structureValue);
    }

    /**
     * Config-based constructor used by the simulation engine plugin loader.
     *
     * @param randomProvider Source of randomness.
     * @param config Configuration object containing the vault parameters.
     */
    public EnergyVaultCreator(IRandomProvider randomProvider, Config config) {
        this(
            randomProvider,
            config.getDouble("percentage"),
            config.getInt("coreRadius"),
            config.getInt("wallThickness"),
            config.getInt("energyAmount"),
            config.getInt("structureValue"),
            config.getInt("structureOwner"),
            config.getInt("interval"),
            config.getInt("safetyRadius")
        );
    }

    @Override
    public void execute(Simulation simulation) {
        long currentTick = simulation.getCurrentTick();
        if (currentTick < nextRefillTick) {
            return;
        }

        Environment environment = simulation.getEnvironment();
        if (!placed) {
            place(environment);
            placed = true;
        } else if (currentTick % refillInterval == 0) {
            refill(environment);
        }
        nextRefillTick = ((currentTick / refillInterval) + 1) * (long) refillInterval;
    }

    /**
     * Draws the vault centres and writes core and shell.
     *
     * @param environment The environment to place the vaults in.
     */
    private void place(Environment environment) {
        EnvironmentProperties properties = environment.getProperties();
        this.dimensions = properties.getDimensions();
        this.coordBuffer = new int[dimensions];

        int totalCells = environment.getTotalCells();
        int vaultCount = (int) (vaultPercentage * totalCells);
        if (vaultCount == 0 && vaultPercentage > 0.0) {
            vaultCount = 1;
        }
        if (vaultCount == 0) {
            return;
        }

        int outerRadius = coreRadius + wallThickness;
        // Read once: both accessors hand out a copy of the shape.
        int[] shape = properties.getWorldShape();
        boolean toroidal = properties.isToroidal();
        for (int d = 0; d < dimensions; d++) {
            if (shape[d] < 2 * outerRadius + 1) {
                throw new IllegalStateException(
                        "A vault of radius " + outerRadius + " does not fit into a world of "
                        + Arrays.toString(shape) + ": axis " + d + " holds " + shape[d]
                        + " cells, " + (2 * outerRadius + 1) + " are needed");
            }
        }

        // The cell count of a vault is known only after the offsets have been walked, so the
        // coordinates are collected and handed to a flat array once, at the end of the placement.
        IntArrayList collectedCoreCells = new IntArrayList();
        int[] candidate = new int[dimensions];
        int[] offsets = new int[dimensions];
        for (int vault = 0; vault < vaultCount; vault++) {
            boolean found = false;
            for (int attempt = 0; attempt < MAX_PLACEMENT_ATTEMPTS; attempt++) {
                // Drawing a flat index makes the centres depend on the random stream and the
                // world's shape alone, not on how the grid is laid out in memory.
                properties.flatIndexToCoordinates(random.nextInt(totalCells), candidate);
                if (!isVaultAreaFree(environment, candidate, offsets, outerRadius, shape, toroidal)) {
                    continue;
                }
                writeVault(environment, candidate, offsets, outerRadius, shape, toroidal, collectedCoreCells);
                found = true;
                break;
            }
            if (!found) {
                throw new IllegalStateException(
                        "No room for vault " + (vault + 1) + " of " + vaultCount + " after "
                        + MAX_PLACEMENT_ATTEMPTS + " attempts: the environment holds too few free areas");
            }
        }
        this.coreCells = collectedCoreCells.toIntArray();
    }

    /**
     * Reports whether every cell of a vault around a centre is empty and unowned, and whether the
     * area the placement keeps clear of organisms is unowned.
     *
     * @param environment The environment to inspect.
     * @param center The candidate centre.
     * @param offsets Scratch array of one entry per dimension, overwritten by the walk.
     * @param outerRadius The Manhattan radius of the whole vault.
     * @param shape The world's shape.
     * @param toroidal Whether the world wraps at its edges.
     * @return {@code true} if the vault can be written here.
     */
    private boolean isVaultAreaFree(Environment environment, int[] center, int[] offsets, int outerRadius,
                                    int[] shape, boolean toroidal) {
        // A world that does not wrap has no cells beyond its edge, so a vault reaching past one
        // would be written incompletely; such a centre is passed over rather than clipped.
        if (!toroidal) {
            for (int d = 0; d < dimensions; d++) {
                if (center[d] - outerRadius < 0 || center[d] + outerRadius >= shape[d]) {
                    return false;
                }
            }
        }
        if (!environment.isAreaUnowned(center, outerRadius + safetyRadius)) {
            return false;
        }
        Arrays.fill(offsets, -outerRadius);
        while (true) {
            if (manhattanLength(offsets) <= outerRadius) {
                toCell(center, offsets, shape, toroidal);
                if (environment.getMoleculeIntAt(coordBuffer) != 0
                        || environment.getOwnerIdAt(coordBuffer) != 0) {
                    return false;
                }
            }
            if (!advance(offsets, outerRadius)) {
                return true;
            }
        }
    }

    /**
     * Writes the core and the shell of one vault and collects the core's coordinates.
     *
     * @param environment The environment to write to.
     * @param center The vault's centre.
     * @param offsets Scratch array of one entry per dimension, overwritten by the walk.
     * @param outerRadius The Manhattan radius of the whole vault.
     * @param shape The world's shape.
     * @param toroidal Whether the world wraps at its edges.
     * @param collectedCoreCells Receives the coordinates of every core cell.
     */
    private void writeVault(Environment environment, int[] center, int[] offsets, int outerRadius,
                            int[] shape, boolean toroidal, IntArrayList collectedCoreCells) {
        Arrays.fill(offsets, -outerRadius);
        while (true) {
            int distance = manhattanLength(offsets);
            if (distance <= coreRadius) {
                toCell(center, offsets, shape, toroidal);
                environment.setMoleculeAt(coordBuffer, energyMolecule, 0);
                for (int d = 0; d < dimensions; d++) {
                    collectedCoreCells.add(coordBuffer[d]);
                }
            } else if (distance <= outerRadius) {
                toCell(center, offsets, shape, toroidal);
                environment.setMoleculeAt(coordBuffer, shellMolecule, structureOwner);
            }
            if (!advance(offsets, outerRadius)) {
                return;
            }
        }
    }

    /**
     * Refills every core cell that has been emptied and is unowned. A cell an organism has taken
     * over is left alone: writing into it would overwrite a body, and the owner is what says so.
     *
     * @param environment The environment to refill in.
     */
    private void refill(Environment environment) {
        int[] cells = this.coreCells;
        int[] buffer = this.coordBuffer;
        int dims = this.dimensions;
        for (int offset = 0; offset < cells.length; offset += dims) {
            System.arraycopy(cells, offset, buffer, 0, dims);
            if (environment.getMoleculeIntAt(buffer) == 0 && environment.getOwnerIdAt(buffer) == 0) {
                environment.setMoleculeAt(buffer, energyMolecule, 0);
            }
        }
    }

    /**
     * Writes {@code center + offsets}, normalized for a toroidal world, into {@link #coordBuffer}.
     * A non-toroidal world needs no normalization: a candidate whose vault would reach past an
     * edge is rejected in {@link #isVaultAreaFree}.
     *
     * @param center The vault's centre.
     * @param offsets The offset from that centre.
     * @param shape The world's shape.
     * @param toroidal Whether the world wraps at its edges.
     */
    private void toCell(int[] center, int[] offsets, int[] shape, boolean toroidal) {
        for (int d = 0; d < dimensions; d++) {
            int component = center[d] + offsets[d];
            coordBuffer[d] = toroidal ? Math.floorMod(component, shape[d]) : component;
        }
    }

    /**
     * The Manhattan length of an offset, which is the number of axis steps needed to walk it.
     *
     * @param offsets The offset, one entry per dimension.
     * @return The sum of the absolute components.
     */
    private static int manhattanLength(int[] offsets) {
        int length = 0;
        for (int offset : offsets) {
            length += Math.abs(offset);
        }
        return length;
    }

    /**
     * Advances the offsets like a counter whose every digit runs from {@code -radius} to
     * {@code +radius}, so that the walk covers the cube the vault is inscribed in.
     *
     * @param offsets The offsets to advance, one entry per dimension.
     * @param radius The largest value a component takes.
     * @return {@code false} once every combination has been visited.
     */
    private static boolean advance(int[] offsets, int radius) {
        int dimension = offsets.length - 1;
        while (dimension >= 0 && offsets[dimension] == radius) {
            offsets[dimension] = -radius;
            dimension--;
        }
        if (dimension < 0) {
            return false;
        }
        offsets[dimension]++;
        return true;
    }

    @Override
    public byte[] saveState() {
        if (!placed) {
            return new byte[0];
        }
        // dimensions(4) + cell count(4) + one int per coordinate component
        ByteBuffer buffer = ByteBuffer.allocate(8 + coreCells.length * 4);
        buffer.putInt(dimensions);
        buffer.putInt(dimensions == 0 ? 0 : coreCells.length / dimensions);
        for (int component : coreCells) {
            buffer.putInt(component);
        }
        return buffer.array();
    }

    @Override
    public void loadState(byte[] state) {
        if (state == null) {
            throw new IllegalArgumentException("EnergyVaultCreator state cannot be null");
        }
        if (state.length == 0) {
            placed = false;
            coreCells = NO_CELLS;
            coordBuffer = NO_CELLS;
            dimensions = 0;
            return;
        }

        ByteBuffer buffer = ByteBuffer.wrap(state);
        dimensions = buffer.getInt();
        int cellCount = buffer.getInt();
        coreCells = new int[cellCount * dimensions];
        for (int i = 0; i < coreCells.length; i++) {
            coreCells[i] = buffer.getInt();
        }
        coordBuffer = new int[dimensions];
        // The shell is not written again: a breach an organism has paid for stays open across a
        // resume, exactly as it stays open across the ticks of an uninterrupted run.
        placed = true;
    }
}
