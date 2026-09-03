// src/main/java/org/evochora/world/Environment.java
package org.evochora.runtime.model;

import java.util.Arrays;
import java.util.BitSet;
import java.util.function.Consumer;

import org.evochora.runtime.Config;
import org.evochora.runtime.ParallelWave;
import org.evochora.runtime.isa.IEnvironmentReader;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.evochora.runtime.label.ILabelMatchingStrategy;
import org.evochora.runtime.label.LabelIndex;
import org.evochora.runtime.label.PreExpandedHammingStrategy;

/**
 * Represents the simulation environment, managing the grid of molecules and their owners.
 * <p>
 * <b>Thread safety:</b> Concurrent reads through coordinates are safe. Writes (e.g.
 * {@code setMolecule}, {@code clearOwnershipFor}) must be serialized — in the tick loop,
 * environment-modifying instructions and death handling always run sequentially on the main
 * thread. The flat-index visits ({@link #forEachOccupiedCellInFlatIndexOrder},
 * {@link #forEachCellChangedSinceLastSample}, {@link #forEachCellChangedSinceLastSnapshot}) share
 * one batch and belong to the single thread that serializes the world; they are not concurrent
 * and not re-entrant, and a nested visit fails fast. The same holds for the owned-cell visit
 * ({@link #visitCellsOwnedBy}): the visits share buffers owned by the environment, and the guard
 * against nesting catches a second visit on the same thread, not one from another thread.
 * <p>
 * <b>Cell addressing:</b> inside this package, every index this class hands out or accepts is a
 * layout index: the position of the cell in the environment's {@link GridLayout}, to be treated as
 * an opaque number that only this class relates to a coordinate. Outside the package there are no
 * indices: callers see coordinates, the {@link CellView} handed to owned-cell visitors, and the
 * flat-index visits used for serialization. The flat index is the row-major numbering of
 * {@link EnvironmentProperties}, in which cells are persisted: a different numbering of the same
 * cells, by which the label index is keyed, and this class converts at that boundary. Every order
 * that may influence a simulation result — the cells of an owner, the candidates of a label, the
 * seeded cells, the persisted cells — is defined over the flat index, so results and persisted
 * bytes do not depend on the layout.
 */
public class Environment implements IEnvironmentReader {
    /**
     * The tile side of production environments: cells are stored in blocks of 32 cells per
     * dimension, 1024 cells per two-dimensional tile, so that the cells an organism touches lie
     * close together in memory. Every world dimension must be a multiple of it. Tests construct
     * other sides through the constructor that takes one; a side of 1 is the persisted row-major
     * order itself. Public so that tests can name the production side instead of its value.
     */
    public static final int TILE_SIDE = 32;

    private final int[] shape;
    private final boolean isToroidal;
    private final int[] grid;
    private final int[] ownerGrid;
    private final GridLayout layout;

    /**
     * One bit per cell, set while the cell holds a molecule or an owner. A bit set gives
     * constant-time updates without hashing, a fixed memory footprint of one bit per cell, and —
     * decisive for reproducible snapshots — iteration in ascending index order, so the order in
     * which cells are serialized depends on the grid's content alone and not on the history of
     * writes.
     */
    private final BitSet occupiedIndices;
    
    // Ownership index: maps ownerId -> set of layout indices owned by that organism
    // Enables O(1) lookup of all cells owned by a specific organism (for FORK transfer, death cleanup)
    private final Int2ObjectOpenHashMap<IntOpenHashSet> cellsByOwner;
    
    // Delta compression: tracks which cells have changed since last reset
    // Used by SimulationEngine to create incremental/accumulated deltas
    // Memory: 1 bit per cell (e.g., 125KB for 1M cells)
    private final BitSet changedSinceLastSample;
    private final BitSet changedSinceLastSnapshot;

    // Label index for fuzzy jump matching
    // Maintains index of all LABEL molecules for O(1) lookup
    private final LabelIndex labelIndex;

    // The batch through which cells are handed out in flat-index order; its buffer is retained
    private final FlatIndexCellOrder flatIndexOrder = new FlatIndexCellOrder();
    // Set while a flat-index visit runs, so that a nested visit fails instead of corrupting it
    private boolean flatIndexVisitRunning = false;
    // Buffers and cursor of the owned-cell visits, retained between visits and therefore not
    // shareable between a visit and one nested inside it; the flag makes a nested visit fail
    private int[] ownedIndices = new int[0];
    private long[] ownedKeys = new long[0];
    private final OwnedCellCursor ownedCursor;
    private boolean ownedVisitRunning = false;

    // Total number of cells (cached for performance)
    private final int totalCells;
    
    /**
     * Environment properties that can be shared with other components.
     * This provides coordinate calculations without exposing the full grid data.
     */
    public final EnvironmentProperties properties;

    // ==================== Static Factory Methods ====================

    /**
     * Creates a label matching strategy from configuration.
     * <p>
     * If config is null or has no className, returns the default {@link PreExpandedHammingStrategy}.
     * Otherwise instantiates the configured strategy class via reflection, passing the options
     * sub-config to the strategy's constructor.
     *
     * @param config The label-matching configuration block (may be null)
     * @return The configured label matching strategy
     * @throws IllegalArgumentException if the configured class cannot be instantiated
     */
    public static ILabelMatchingStrategy createLabelMatchingStrategy(com.typesafe.config.Config config) {
        if (config == null || !config.hasPath("className")) {
            return new PreExpandedHammingStrategy();
        }
        String className = config.getString("className");
        com.typesafe.config.Config options = config.hasPath("options")
            ? config.getConfig("options")
            : com.typesafe.config.ConfigFactory.empty();
        try {
            return (ILabelMatchingStrategy) Class.forName(className)
                .getConstructor(com.typesafe.config.Config.class)
                .newInstance(options);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException(
                "Failed to instantiate label matching strategy: " + className, e);
        }
    }

    // ==================== Constructors ====================

    /**
     * Creates a new environment with the specified shape and toroidal setting.
     * Uses default label matching strategy.
     *
     * @param shape The dimensions of the world.
     * @param toroidal Whether the world wraps around at edges.
     */
    public Environment(int[] shape, boolean toroidal) {
        this(new EnvironmentProperties(shape, toroidal));
    }

    /**
     * Creates a new environment with the specified properties.
     * Uses default label matching strategy.
     *
     * @param properties The environment properties.
     */
    public Environment(EnvironmentProperties properties) {
        this(properties, new org.evochora.runtime.label.PreExpandedHammingStrategy());
    }

    /**
     * Creates a new environment with the specified properties and label matching strategy.
     * This is the primary constructor used by SimulationEngine.
     *
     * @param properties The environment properties.
     * @param labelMatchingStrategy The strategy for fuzzy label matching in jump instructions.
     * @throws IllegalArgumentException if the world shape is not valid for the production tile side,
     *                                  see {@link GridLayout}
     */
    public Environment(EnvironmentProperties properties, org.evochora.runtime.label.ILabelMatchingStrategy labelMatchingStrategy) {
        this(properties, labelMatchingStrategy, TILE_SIDE);
    }

    /**
     * Creates a new environment whose grid is laid out in tiles of the given side. Production
     * environments use the one tile side this class defines; this constructor exists so that tests
     * can prove a simulation result independent of the layout by running it under several, and so
     * that tests of worlds smaller than a production tile can use a side of 1.
     *
     * @param properties The environment properties.
     * @param labelMatchingStrategy The strategy for fuzzy label matching in jump instructions.
     * @param tileSide cells per tile along each dimension, a power of two that divides every
     *                 dimension of the world
     * @throws IllegalArgumentException if the tile side or the world shape is not valid, see
     *                                  {@link GridLayout}
     */
    public Environment(EnvironmentProperties properties, org.evochora.runtime.label.ILabelMatchingStrategy labelMatchingStrategy, int tileSide) {
        this.properties = properties;
        this.shape = properties.getWorldShape();
        this.isToroidal = properties.isToroidal();
        this.layout = new GridLayout(properties, tileSide);
        this.ownedCursor = new OwnedCellCursor();
        int size = layout.totalCells();
        this.totalCells = size;
        this.grid = new int[size];
        this.ownerGrid = new int[size];

        // Initialize sparse cell tracking if enabled (using primitive int indices for performance)
        this.occupiedIndices = new BitSet(totalCells);

        // Initialize ownership index
        this.cellsByOwner = new Int2ObjectOpenHashMap<>();

        // Initialize change tracking for delta compression
        this.changedSinceLastSample = new BitSet(size);
        this.changedSinceLastSnapshot = new BitSet(size);

        // Initialize label index for fuzzy jump matching
        this.labelIndex = new LabelIndex(labelMatchingStrategy);
    }

    /**
     * Normalizes a coordinate based on the environment's toroidal setting.
     * @param coord The coordinate to normalize.
     * @return The normalized coordinate.
     */
    public int[] getNormalizedCoordinate(int... coord) {
        if (coord.length != this.shape.length) {
            throw new IllegalArgumentException("Coordinate dimensions do not match world dimensions.");
        }
        int[] normalized = new int[coord.length];
        for (int i = 0; i < coord.length; i++) {
            int c = coord[i];
            if (isToroidal) {
                c = Math.floorMod(c, this.shape[i]);
            }
            normalized[i] = c;
        }
        return normalized;
    }

    private int getLayoutIndex(int... coord) {
        int[] normalizedCoord = getNormalizedCoordinate(coord);
        if (!isToroidal) {
            for(int i = 0; i < shape.length; i++) {
                if (normalizedCoord[i] < 0 || normalizedCoord[i] >= shape[i]) {
                    return -1;
                }
            }
        }
        return layout.layoutIndex(normalizedCoord);
    }

    /**
     * Reads the packed molecule value of the cell at a coordinate that lies within the world.
     * Unlike {@link #getMolecule(int...)} this neither normalizes nor allocates; every component
     * must already be in range, and a coordinate outside the world is rejected.
     *
     * @param coord the in-range coordinate
     * @return the packed molecule value, {@code 0} for an empty cell
     * @throws IllegalArgumentException if a component lies outside the world
     */
    public int getMoleculeIntAt(int[] coord) {
        return grid[indexOfInRange(coord)];
    }

    /**
     * Reads the owner of the cell at a coordinate that lies within the world; see
     * {@link #getMoleculeIntAt(int[])} for the contract.
     *
     * @param coord the in-range coordinate
     * @return the owner id, {@code 0} for an unowned cell
     * @throws IllegalArgumentException if a component lies outside the world
     */
    public int getOwnerIdAt(int[] coord) {
        return ownerGrid[indexOfInRange(coord)];
    }

    /**
     * Writes a molecule into the cell at a coordinate that lies within the world, keeping the
     * cell's owner. Tracked like every other write. Unlike {@link #setMolecule(Molecule, int...)}
     * this neither normalizes nor allocates; every component must already be in range, and a
     * coordinate outside the world is rejected.
     *
     * @param coord    the in-range coordinate
     * @param molecule the new content
     * @throws IllegalArgumentException if a component lies outside the world
     */
    public void setMoleculeAt(int[] coord, Molecule molecule) {
        setMoleculeByIndex(indexOfInRange(coord), molecule);
    }

    /**
     * Writes a molecule and an owner into the cell at a coordinate that lies within the world;
     * see {@link #setMoleculeAt(int[], Molecule)} for the contract.
     *
     * @param coord    the in-range coordinate
     * @param molecule the new content
     * @param ownerId  the new owner
     * @throws IllegalArgumentException if a component lies outside the world
     */
    public void setMoleculeAt(int[] coord, Molecule molecule, int ownerId) {
        writeMolecule(indexOfInRange(coord), molecule, ownerId);
    }

    /**
     * The size of an organism's body in cells, answered from the owner index without touching the
     * grid. Birth handlers use it to skip organisms that own nothing before starting a visit of
     * their cells.
     *
     * @param ownerId an organism id
     * @return how many cells that organism owns, {@code 0} if none
     */
    public int countCellsOwnedBy(int ownerId) {
        IntOpenHashSet owned = cellsByOwner.get(ownerId);
        return owned == null ? 0 : owned.size();
    }

    /**
     * Hands every cell owned by {@code ownerId} to the visitor through a {@link CellView}, in
     * ascending flat-index order — an order determined by the cells' coordinates alone, independent
     * of write history and of how the grid is laid out in memory. This is the owned-cell access for
     * callers outside this package, which see coordinates and never a layout index.
     * <p>
     * The visit sorts the owner's cells in buffers retained by the environment and moves one
     * retained cursor over them, so after the first visit nothing here allocates. The buffers and
     * the cursor are shared by all owned-cell visits of this environment: a visit started inside a
     * visitor fails fast rather than corrupting the visit in progress. A visitor that needs the
     * cells of a second organism collects what it needs and visits that organism afterwards.
     *
     * @param ownerId the owner whose cells to visit
     * @param visitor receives the view, positioned on one cell after the other
     * @throws IllegalStateException if called from inside an owned-cell visit of this environment
     */
    public void visitCellsOwnedBy(int ownerId, Consumer<CellView> visitor) {
        if (ownedVisitRunning) {
            throw new IllegalStateException("An owned-cell visit is already running on this environment; "
                    + "visits share their buffers and cannot be nested");
        }
        IntOpenHashSet owned = cellsByOwner.get(ownerId);
        if (owned == null || owned.isEmpty()) {
            return;
        }
        ownedVisitRunning = true;
        try {
            int count = owned.size();
            if (ownedIndices.length < count) {
                int capacity = (int) Math.min(Integer.MAX_VALUE - 8, Math.max(count, 2L * ownedIndices.length));
                ownedIndices = new int[capacity];
                ownedKeys = new long[capacity];
            }
            owned.toArray(ownedIndices);
            for (int i = 0; i < count; i++) {
                int index = ownedIndices[i];
                ownedKeys[i] = ((long) layout.flatIndex(index) << 32) | (index & 0xFFFFFFFFL);
            }
            Arrays.sort(ownedKeys, 0, count);
            for (int i = 0; i < count; i++) {
                ownedCursor.moveTo((int) ownedKeys[i]);
                visitor.accept(ownedCursor);
            }
        } finally {
            ownedCursor.leave();
            ownedVisitRunning = false;
        }
    }

    /**
     * The view handed to owned-cell visitors: positioned on one layout index at a time and
     * reading and writing through the environment, so the index never leaves this class.
     */
    private final class OwnedCellCursor implements CellView {
        private final int[] coordinate = new int[shape.length];
        private int index = -1;

        void moveTo(int layoutIndex) {
            this.index = layoutIndex;
            layout.coordinate(layoutIndex, coordinate);
        }

        /** Detaches the cursor from any cell, so that a view retained beyond its visit fails. */
        void leave() {
            this.index = -1;
        }

        private int cell() {
            if (index < 0) {
                throw new IllegalStateException("A CellView is valid only inside the visit that handed it out");
            }
            return index;
        }

        @Override
        public int[] coordinate() {
            cell();
            return coordinate;
        }

        @Override
        public int moleculeInt() {
            return grid[cell()];
        }

        @Override
        public Molecule molecule() {
            return Molecule.fromInt(grid[cell()]);
        }

        @Override
        public int ownerId() {
            return ownerGrid[cell()];
        }

        @Override
        public void setMolecule(Molecule molecule) {
            setMoleculeByIndex(cell(), molecule);
        }
    }

    /**
     * Gets the molecule at the specified coordinate.
     * @param coord The coordinate to get the molecule from.
     * @return The molecule at the specified coordinate.
     */
    public Molecule getMolecule(int... coord) {
        int index = getLayoutIndex(coord);
        if (index == -1) {
            return org.evochora.runtime.model.Molecule.fromInt(0);
        }
        return org.evochora.runtime.model.Molecule.fromInt(this.grid[index]);
    }

    /**
     * Sets the molecule at the specified coordinate.
     * @param molecule The molecule to set.
     * @param coord The coordinate to set the molecule at.
     */
    public void setMolecule(Molecule molecule, int... coord) {
        assert outsideParallelWave();
        int index = getLayoutIndex(coord);
        if (index != -1) {
            setMoleculeByIndex(index, molecule);
        }
    }

    /**
     * Sets the molecule and its owner at the specified coordinate.
     * @param molecule The molecule to set.
     * @param ownerId The ID of the owner.
     * @param coord The coordinate to set the molecule at.
     */
    public void setMolecule(Molecule molecule, int ownerId, int... coord) {
        assert outsideParallelWave();
        int index = getLayoutIndex(coord);
        if (index != -1) {
            writeMolecule(index, molecule, ownerId);
        }
    }

    /** Writes molecule and owner into the cell at a layout index, updating every index structure. */
    private void writeMolecule(int index, Molecule molecule, int ownerId) {
        assert outsideParallelWave();
        int oldMoleculeInt = this.grid[index];
        int newMoleculeInt = molecule.toInt();
        this.grid[index] = newMoleculeInt;

        // Track change for delta compression
        markChanged(index);

        // Update ownership index
        int oldOwner = this.ownerGrid[index];
        if (oldOwner != ownerId) {
            updateOwnershipIndex(index, oldOwner, ownerId);
        }
        this.ownerGrid[index] = ownerId;

        // Update label index for fuzzy jump matching
        labelIndex.onMoleculeSet(toFlatIndex(index), oldMoleculeInt, newMoleculeInt, ownerId);

        // Update sparse cell tracking if enabled
        updateOccupiedIndices(index);
    }

    /**
     * Gets the owner ID of the cell at the specified coordinate.
     * @param coord The coordinate to get the owner ID from.
     * @return The owner ID.
     */
    public int getOwnerId(int... coord) {
        int index = getLayoutIndex(coord);
        if (index == -1) {
            return 0;
        }
        return this.ownerGrid[index];
    }

    /**
     * Sets the owner ID of the cell at the specified coordinate.
     * @param ownerId The owner ID to set.
     * @param coord The coordinate to set the owner ID at.
     */
    public void setOwnerId(int ownerId, int... coord) {
        assert outsideParallelWave();
        int index = getLayoutIndex(coord);
        if (index != -1) {
            // Track change for delta compression (owner change is also a change)
            markChanged(index);

            // Update ownership index
            int oldOwner = this.ownerGrid[index];
            if (oldOwner != ownerId) {
                updateOwnershipIndex(index, oldOwner, ownerId);

                // Update label index for fuzzy jump matching
                int moleculeInt = this.grid[index];
                labelIndex.onOwnerChange(toFlatIndex(index), moleculeInt, ownerId);
            }
            this.ownerGrid[index] = ownerId;

            // Update sparse cell tracking if enabled
            updateOccupiedIndices(index);
        }
    }

    /**
     * Clears the owner of the cell at the specified coordinate.
     * @param coord The coordinate to clear the owner of.
     */
    public void clearOwner(int... coord) {
        setOwnerId(0, coord);
    }

    /**
     * Gets the shape of the environment.
     * @return The shape of the environment.
     */
    public int[] getShape() {
        return Arrays.copyOf(this.shape, this.shape.length);
    }
    
    @Override
    public org.evochora.runtime.model.EnvironmentProperties getProperties() {
        return this.properties;
    }

    /**
     * Checks if a square/cubic area around a central coordinate is completely unowned.
     *
     * @param centerCoord The coordinate of the center of the area.
     * @param radius The radius of the check (e.g., radius 2 checks a 5x5 area in 2D).
     * @return {@code true} if no cell in the area has an owner (ownerId == 0), otherwise {@code false}.
     */
    public boolean isAreaUnowned(int[] centerCoord, int radius) {
        if (centerCoord.length != this.shape.length) {
            throw new IllegalArgumentException("Coordinate dimensions do not match world dimensions.");
        }
        
        // Optimized implementation: reuse arrays and direct array access
        int dims = this.shape.length;
        int[] offsets = new int[dims];
        int[] checkCoord = new int[dims]; // Reuse this array instead of creating new ones
        
        // Initialize offsets
        for (int i = 0; i < dims; i++) {
            offsets[i] = -radius;
        }

        while (true) {
            // Calculate check coordinate by reusing the array
            for (int i = 0; i < dims; i++) {
                checkCoord[i] = centerCoord[i] + offsets[i];
            }
            
            // Direct array access instead of getOwnerId() call
            int layoutIndex = getLayoutIndex(checkCoord);
            if (layoutIndex != -1 && this.ownerGrid[layoutIndex] != 0) {
                return false;
            }
            
            // Increment the offsets like a counter from -radius to +radius per dimension
            int dim = dims - 1;
            while (dim >= 0 && offsets[dim] == radius) {
                offsets[dim] = -radius;
                dim--;
            }
            if (dim < 0) break; // all combinations have been checked
            offsets[dim]++;
        }
        return true;
    }
    
    /**
     * Guards every mutation against the parallel wave of a tick. Inside that wave several
     * organisms execute concurrently against a snapshot of the environment; a write there would
     * race with other threads and make the run depend on scheduling. Only instructions registered
     * as parallel-safe run in the wave, so a failing assertion means an instruction is registered
     * as parallel-safe but modifies the environment. Evaluated only with assertions enabled (test
     * runs); carries no cost in production.
     *
     * @return {@code true} when the calling thread is not inside the parallel wave
     */
    private static boolean outsideParallelWave() {
        if (ParallelWave.isActive()) {
            throw new AssertionError("Environment modified inside the parallel wave of a tick: "
                    + "only organism-local instructions may run there; an instruction registered as "
                    + "parallel-safe must not write to the environment");
        }
        return true;
    }

    /**
     * Updates the occupied indices tracking based on the current state of the cell.
     * @param layoutIndex The layout index to check and update.
     */
    private void updateOccupiedIndices(int layoutIndex) {
        int value = this.grid[layoutIndex];
        int owner = this.ownerGrid[layoutIndex];

        if (value != 0 || owner != 0) {
            // Cell is occupied - add to tracking
            occupiedIndices.set(layoutIndex);
        } else {
            // Cell is empty - remove from tracking
            occupiedIndices.clear(layoutIndex);
        }
    }

    /**
     * Updates the ownership index when a cell's owner changes.
     * @param layoutIndex The layout index of the cell.
     * @param oldOwner The previous owner ID.
     * @param newOwner The new owner ID.
     */
    private void updateOwnershipIndex(int layoutIndex, int oldOwner, int newOwner) {
        // Remove from old owner's set
        if (oldOwner != 0) {
            IntOpenHashSet oldSet = cellsByOwner.get(oldOwner);
            if (oldSet != null) {
                oldSet.remove(layoutIndex);
                if (oldSet.isEmpty()) {
                    cellsByOwner.remove(oldOwner);
                }
            }
        }
        // Add to new owner's set
        if (newOwner != 0) {
            cellsByOwner.computeIfAbsent(newOwner, k -> new IntOpenHashSet()).add(layoutIndex);
        }
    }


    /**
     * Converts a layout index into the coordinate of the cell.
     *
     * @param layoutIndex The layout index to convert
     * @return A new coordinate array
     */
    int[] getCoordinateFromIndex(int layoutIndex) {
        int[] coord = new int[shape.length];
        layout.coordinate(layoutIndex, coord);
        return coord;
    }

    /**
     * Converts a layout index into the coordinate of the cell without allocating.
     *
     * @param layoutIndex The layout index to convert
     * @param outCoord Receives the coordinate; one entry per dimension
     */
    void getCoordinateFromIndex(int layoutIndex, int[] outCoord) {
        layout.coordinate(layoutIndex, outCoord);
    }

    /**
     * Converts an in-range coordinate into the layout index of the cell. Unlike the coordinate-based
     * accessors this performs no toroidal normalization: every component must already lie within
     * the world's shape, and a coordinate outside it is rejected.
     *
     * @param coord The coordinate, one in-range entry per dimension
     * @return The layout index of that cell
     * @throws IllegalArgumentException if a component lies outside the world
     */
    int getIndexFromCoordinate(int[] coord) {
        return indexOfInRange(coord);
    }

    /**
     * The layout index of a coordinate that must name a cell of the world. The layout's tile
     * arithmetic would map an out-of-range component onto a neighbouring tile instead of failing,
     * so this check is what keeps the in-range accessors from silently addressing another cell.
     *
     * @param coord the coordinate, one entry per dimension
     * @return the layout index of that cell
     * @throws IllegalArgumentException if a component lies outside the world
     */
    private int indexOfInRange(int[] coord) {
        if (!layout.contains(coord)) {
            throw new IllegalArgumentException("Coordinate " + Arrays.toString(coord)
                    + " lies outside the world of shape " + Arrays.toString(properties.getWorldShape()));
        }
        return layout.layoutIndex(coord);
    }

    /**
     * Returns the layout index of the cell one step away from an indexed cell along a dimension,
     * wrapping around in a toroidal world.
     *
     * @param layoutIndex The layout index of the cell to step from
     * @param dim The dimension to step along
     * @param forward {@code true} to step towards higher coordinates, {@code false} towards lower
     * @return The layout index of the neighbouring cell, or {@code -1} if the step leaves a bounded
     *         world
     */
    int stepIndex(int layoutIndex, int dim, boolean forward) {
        return layout.step(layoutIndex, dim, forward);
    }


    /**
     * Gets the packed molecule integer at the specified layout index.
     * OPTIMIZATION: Direct array access without coordinate conversion.
     *
     * @param layoutIndex The layout index
     * @return The packed molecule integer
     */
    int getMoleculeInt(int layoutIndex) {
        return this.grid[layoutIndex];
    }




    /**
     * Converts a layout index into the flat index of the same cell: the row-major numbering of
     * {@link EnvironmentProperties}, which is a pure function of the coordinate and the numbering
     * in which cells are persisted. Every order that may influence a simulation result is defined
     * over flat indices, so that results do not depend on how the grid is laid out in memory.
     * Allocation-free.
     *
     * @param layoutIndex a layout index of this environment
     * @return the flat index of the same cell
     */
    int toFlatIndex(int layoutIndex) {
        return layout.flatIndex(layoutIndex);
    }


    /**
     * Returns the set of layout indices owned by the specified organism.
     * <p>
     * Returns the internal set directly (no copy) for performance.
     * The returned set should not be modified by callers.
     * </p>
     *
     * @param ownerId The organism ID
     * @return The set of layout indices, or null if the organism owns no cells
     */
    it.unimi.dsi.fastutil.ints.IntOpenHashSet getCellsOwnedBy(int ownerId) {
        return cellsByOwner.get(ownerId);
    }


    /**
     * Sets the molecule at the specified layout index.
     * <p>
     * OPTIMIZATION: Direct array access without coordinate conversion.
     * Updates all tracking structures (delta compression, label index, sparse tracking).
     * </p>
     *
     * @param layoutIndex The layout index
     * @param molecule The molecule to set
     */
    void setMoleculeByIndex(int layoutIndex, Molecule molecule) {
        assert outsideParallelWave();
        int oldMoleculeInt = this.grid[layoutIndex];
        int newMoleculeInt = molecule.toInt();
        this.grid[layoutIndex] = newMoleculeInt;

        // Track change for delta compression
        markChanged(layoutIndex);

        // Update label index for fuzzy jump matching
        int owner = this.ownerGrid[layoutIndex];
        labelIndex.onMoleculeSet(toFlatIndex(layoutIndex), oldMoleculeInt, newMoleculeInt, owner);

        // Update sparse cell tracking if enabled
        updateOccupiedIndices(layoutIndex);
    }

    /**
     * Transfers ownership of molecules from one organism to another based on marker matching.
     * <p>
     * This method iterates over all occupied cells and transfers ownership from {@code fromOwnerId}
     * to {@code toOwnerId} for molecules where the marker matches {@code markerToMatch}.
     * After transfer, the marker of each transferred molecule is reset to 0.
     * <p>
     * <strong>Performance:</strong> O(occupied cells) - iterates using sparse cell tracking.
     * For typical simulations with ~5% occupancy, this is much faster than full grid iteration.
     *
     * @param fromOwnerId   The current owner ID whose molecules should be transferred.
     * @param toOwnerId     The new owner ID to assign to matching molecules.
     * @param markerToMatch The marker value that molecules must have to be transferred.
     * @return The number of molecules transferred.
     */
    public int transferOwnership(int fromOwnerId, int toOwnerId, int markerToMatch) {
        assert outsideParallelWave();
        IntOpenHashSet fromSet = cellsByOwner.get(fromOwnerId);
        if (fromSet == null || fromSet.isEmpty()) {
            return 0;
        }

        // Collect indices to transfer (can't modify during iteration)
        it.unimi.dsi.fastutil.ints.IntList toTransfer = new it.unimi.dsi.fastutil.ints.IntArrayList();

        fromSet.forEach((int layoutIndex) -> {
            int moleculeInt = grid[layoutIndex];
            // Use unsigned shift (>>>) to avoid sign-extension when bit 31 is set (marker >= 8)
            int marker = (moleculeInt & Config.MARKER_MASK) >>> Config.MARKER_SHIFT;
            if (marker == markerToMatch) {
                toTransfer.add(layoutIndex);
            }
        });

        // Transfer ownership and reset marker
        IntOpenHashSet toSet = cellsByOwner.computeIfAbsent(toOwnerId, k -> new IntOpenHashSet());
        for (int i = 0; i < toTransfer.size(); i++) {
            int layoutIndex = toTransfer.getInt(i);
            ownerGrid[layoutIndex] = toOwnerId;
            // Reset marker to 0: clear marker bits and keep value/type
            grid[layoutIndex] = grid[layoutIndex] & ~Config.MARKER_MASK;
            // Track change for delta compression
            markChanged(layoutIndex);
            // Update ownership index
            fromSet.remove(layoutIndex);
            toSet.add(layoutIndex);
            // Update label index: owner changed and marker reset to 0
            int moleculeInt = grid[layoutIndex];
            labelIndex.onOwnerChange(toFlatIndex(layoutIndex), moleculeInt, toOwnerId);
            labelIndex.onMarkerChange(toFlatIndex(layoutIndex), moleculeInt);
            // An empty cell handed to "nobody" leaves the occupied set
            updateOccupiedIndices(layoutIndex);
        }
        
        // Clean up empty set
        if (fromSet.isEmpty()) {
            cellsByOwner.remove(fromOwnerId);
        }

        return toTransfer.size();
    }

    /**
     * Clears ownership of all cells owned by the specified organism.
     * Sets owner to 0 and resets marker to 0 for all affected cells.
     * Called when an organism dies to release its molecules.
     * 
     * @param ownerId The ID of the organism whose ownership should be cleared.
     * @return The number of cells that were cleared.
     */
    public int clearOwnershipFor(int ownerId) {
        assert outsideParallelWave();
        IntOpenHashSet owned = cellsByOwner.remove(ownerId);
        if (owned == null || owned.isEmpty()) {
            return 0;
        }

        int count = owned.size();
        owned.forEach((int layoutIndex) -> {
            ownerGrid[layoutIndex] = 0;
            // Reset marker to 0
            grid[layoutIndex] = grid[layoutIndex] & ~Config.MARKER_MASK;
            // Track change for delta compression
            markChanged(layoutIndex);
            // Update label index: owner cleared and marker reset to 0
            int moleculeInt = grid[layoutIndex];
            labelIndex.onOwnerChange(toFlatIndex(layoutIndex), moleculeInt, 0);
            labelIndex.onMarkerChange(toFlatIndex(layoutIndex), moleculeInt);
            // A cell that is now empty and unowned leaves the occupied set; otherwise every dead
            // organism's footprint would stay in it (and in every snapshot) forever
            updateOccupiedIndices(layoutIndex);
        });

        return count;
    }

    /**
     * Removes all molecules owned by the specified organism that have a matching marker value.
     * The cells are completely cleared (molecule and owner set to 0).
     * <p>
     * This is used during reproduction when a replication attempt is aborted - the partially
     * replicated molecules are completely removed from the environment.
     * <p>
     * <strong>Performance:</strong> O(occupied cells by owner) - iterates using sparse cell tracking.
     *
     * @param ownerId       The ID of the organism whose molecules should be checked.
     * @param markerToMatch The marker value that molecules must have to be removed.
     * @return The number of molecules that were removed.
     */
    public int clearMarkersFor(int ownerId, int markerToMatch) {
        assert outsideParallelWave();
        IntOpenHashSet owned = cellsByOwner.get(ownerId);
        if (owned == null || owned.isEmpty()) {
            return 0;
        }

        // Collect indices to remove (can't modify during iteration since we're changing ownership)
        it.unimi.dsi.fastutil.ints.IntList toRemove = new it.unimi.dsi.fastutil.ints.IntArrayList();
        owned.forEach((int layoutIndex) -> {
            int moleculeInt = grid[layoutIndex];
            // Use unsigned shift (>>>) to avoid sign-extension when bit 31 is set (marker >= 8)
            int marker = (moleculeInt & Config.MARKER_MASK) >>> Config.MARKER_SHIFT;
            if (marker == markerToMatch) {
                toRemove.add(layoutIndex);
            }
        });

        // Remove the collected cells completely
        for (int i = 0; i < toRemove.size(); i++) {
            int layoutIndex = toRemove.getInt(i);
            int oldMoleculeInt = grid[layoutIndex];
            // Completely clear the cell
            grid[layoutIndex] = 0;
            ownerGrid[layoutIndex] = 0;
            // Track change for delta compression
            markChanged(layoutIndex);
            // Update ownership index: remove from owner's set
            owned.remove(layoutIndex);
            // Update label index: molecule removed
            labelIndex.onMoleculeSet(toFlatIndex(layoutIndex), oldMoleculeInt, 0, 0);
            // Update sparse cell tracking if enabled
            occupiedIndices.clear(layoutIndex);
        }

        // Clean up empty set
        if (owned.isEmpty()) {
            cellsByOwner.remove(ownerId);
        }

        return toRemove.size();
    }

    // ========================================================================
    // Delta Compression Support
    // ========================================================================
    

    /**
     * Forgets every recorded change, both since the last sample and since the last snapshot.
     * Called when a world is rebuilt from persisted data, so that the rebuild itself does not
     * count as change.
     */
    public void resetChangeTracking() {
        changedSinceLastSample.clear();
        changedSinceLastSnapshot.clear();
    }

    /**
     * Records a change to the cell at a layout index in both change sets.
     * <p>
     * Both sets are kept on the write path on purpose: one extra word access per write, next to
     * the grid arrays, the occupancy set and the indices the same write already maintains. The
     * alternative, one set on the write path and an OR of that set into the snapshot set at every
     * sample, costs a pass over every word the set has ever touched — for changes scattered over
     * the world, hundreds of thousands of words per sample, at every tick in the most detailed
     * profile.
     */
    private void markChanged(int layoutIndex) {
        changedSinceLastSample.set(layoutIndex);
        changedSinceLastSnapshot.set(layoutIndex);
    }

    /**
     * Marks that a sample of the world has been taken: changes recorded so far no longer count as
     * "since the last sample". Changes since the last snapshot are kept.
     * <p>
     * The change tracking has exactly one observer, the encoder that takes the samples; it alone
     * reads the changed cells and resets them. A second reader would see an incomplete set and a
     * second caller of the marks would take changes away from the first, undetectably. An
     * architecture test holds that rule for production code.
     */
    public void markSampleTaken() {
        changedSinceLastSample.clear();
    }

    /**
     * Marks that a snapshot of the whole world has been taken: changes recorded so far no longer
     * count as "since the last snapshot". Reserved for the one observer of the change tracking,
     * see {@link #markSampleTaken()}.
     */
    public void markSnapshotTaken() {
        changedSinceLastSnapshot.clear();
    }

    /**
     * Hands every occupied cell to the visitor, in ascending flat-index order.
     *
     * @param visitor receives each cell under its flat index
     */
    public void forEachOccupiedCellInFlatIndexOrder(FlatIndexCellVisitor visitor) {
        visitInFlatIndexOrder(occupiedIndices, visitor);
    }

    /**
     * Hands every cell changed since {@link #markSampleTaken()} was last called to the visitor,
     * in ascending flat-index order. Reserved for the one observer of the change tracking, see
     * {@link #markSampleTaken()}; every other reader uses
     * {@link #forEachOccupiedCellInFlatIndexOrder(FlatIndexCellVisitor)}.
     *
     * @param visitor receives each cell under its flat index
     */
    public void forEachCellChangedSinceLastSample(FlatIndexCellVisitor visitor) {
        visitInFlatIndexOrder(changedSinceLastSample, visitor);
    }

    /**
     * Hands every cell changed since {@link #markSnapshotTaken()} was last called to the visitor,
     * in ascending flat-index order. Reserved for the one observer of the change tracking, see
     * {@link #markSampleTaken()}.
     *
     * @param visitor receives each cell under its flat index
     */
    public void forEachCellChangedSinceLastSnapshot(FlatIndexCellVisitor visitor) {
        visitInFlatIndexOrder(changedSinceLastSnapshot, visitor);
    }

    /**
     * Hands the cells whose layout indices are set in {@code cells} to the visitor in ascending
     * flat-index order. The set is walked in layout order, which is the order of the grid arrays,
     * so their contents are read sequentially, and which groups cells by tile, so the part of the
     * flat index that costs divisions is computed once per tile and each cell adds only its
     * offset. The batch, holding each cell's content, is then ordered and handed out without
     * touching the grid again.
     */
    private void visitInFlatIndexOrder(BitSet cells, FlatIndexCellVisitor visitor) {
        if (flatIndexVisitRunning) {
            throw new IllegalStateException("A flat-index visit is already running on this environment; "
                    + "the visits share one batch and cannot be nested or run concurrently");
        }
        flatIndexVisitRunning = true;
        try {
            int cellsPerTileShift = layout.cellsPerTileShift();
            int offsetMask = (1 << cellsPerTileShift) - 1;
            flatIndexOrder.clear();
            int currentTile = -1;
            int tileFlatIndex = 0;
            for (int i = cells.nextSetBit(0); i >= 0; i = cells.nextSetBit(i + 1)) {
                int tile = i >>> cellsPerTileShift;
                if (tile != currentTile) {
                    currentTile = tile;
                    tileFlatIndex = layout.flatIndexOfTile(tile);
                }
                flatIndexOrder.add(tileFlatIndex + layout.flatIndexOffset(i & offsetMask), grid[i], ownerGrid[i]);
            }
            flatIndexOrder.sort();
            int count = flatIndexOrder.count();
            for (int position = 0; position < count; position++) {
                visitor.visit(flatIndexOrder.flatIndexAt(position), flatIndexOrder.moleculeAt(position),
                        flatIndexOrder.ownerAt(position));
            }
        } finally {
            flatIndexVisitRunning = false;
        }
    }
    
    /**
     * Gets the total number of cells in the environment.
     * <p>
     * This is the product of all dimension sizes.
     *
     * @return total cell count
     */
    public int getTotalCells() {
        return totalCells;
    }

    // ========================================================================
    // Label Index Support (Fuzzy Jump Matching)
    // ========================================================================

    /**
     * Gets the label index for fuzzy jump matching.
     * <p>
     * The label index maintains an index of all LABEL molecules in the environment,
     * enabling O(1) lookup for jump targets using Hamming distance tolerance.
     *
     * @return The label index
     */
    public LabelIndex getLabelIndex() {
        return labelIndex;
    }
}