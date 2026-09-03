package org.evochora.runtime.model;

import java.util.Arrays;

/**
 * The memory layout of the environment's cell grid: the mapping between an n-dimensional cell
 * coordinate and the position of that cell in the environment's flat arrays.
 * <p>
 * Cells are stored in cubic tiles of {@code tileSide} cells per dimension. Inside a tile dimension
 * 0 is contiguous, so a step along the organisms' default direction of execution moves to the
 * neighbouring array element until the tile edge. The tiles themselves are ordered like the
 * persisted row-major numbering of {@link EnvironmentProperties}, with the last dimension varying
 * fastest; with a tile side of 1 the layout therefore reproduces that numbering exactly, and for
 * every tile side {@link #canonical(int)} converts an internal index into it.
 * <p>
 * Key features:
 * <ul>
 *   <li>coordinate to index and index to coordinate without allocation,</li>
 *   <li>a single-cell step along one dimension that honours tile edges and the world's topology,</li>
 *   <li>conversion to the persisted row-major index,</li>
 *   <li>the toroidal Manhattan distance between a coordinate and an indexed cell,</li>
 *   <li>validation of the world shape against the tile side at construction.</li>
 * </ul>
 * <p>
 * Architectural notes: this is the only class that knows how cells are laid out. The environment's
 * index-based accessors hand out and accept indices of this layout, and no other component may
 * derive an index from a coordinate or a coordinate from an index by its own arithmetic. The class
 * is final, holds only immutable primitive state and has no polymorphism, so that the JIT compiles
 * the primitives to plain integer arithmetic on the hot path. The tile side must be a power of two
 * so that offsets inside a tile are shifts and masks; a tile-boundary crossing, once per
 * {@code tileSide} steps, is the only place a division occurs on a step.
 * <p>
 * Thread safety: immutable, safe for concurrent use.
 */
final class GridLayout {

    private final int[] shape;
    /** Distance, in cells, between neighbours along each dimension in the persisted row-major numbering. */
    private final int[] canonicalStrides;
    private final boolean toroidal;
    private final int dimensions;
    private final int tileSide;
    /** log2 of the tile side. */
    private final int tileShift;
    /** {@code tileSide - 1}: masks the offset of a cell inside its tile along one dimension. */
    private final int tileMask;
    /** log2 of the cells per tile, {@code tileShift * dimensions}. */
    private final int cellsPerTileShift;
    /** Number of tiles along each dimension. */
    private final int[] tileCounts;
    /** Distance, in tiles, between neighbouring tiles along each dimension; row-major over the tile grid. */
    private final int[] tileStrides;
    private final int totalCells;

    /**
     * Creates the layout for a world.
     *
     * @param properties the world's shape and topology; its row-major numbering is the persisted
     *                   index this layout converts to
     * @param tileSide   cells per tile along each dimension; a power of two, at least 1
     * @throws IllegalArgumentException if the tile side is not a power of two, if a world dimension
     *                                  is not a multiple of the tile side, if a tile would hold more
     *                                  cells than an {@code int} can index, or if the world holds
     *                                  more cells than an {@code int} can index
     */
    GridLayout(EnvironmentProperties properties, int tileSide) {
        if (tileSide < 1 || Integer.bitCount(tileSide) != 1) {
            throw new IllegalArgumentException("Tile side must be a power of two, got " + tileSide);
        }
        this.shape = properties.getWorldShape();
        this.canonicalStrides = new int[shape.length];
        for (int i = 0; i < shape.length; i++) {
            canonicalStrides[i] = properties.getStride(i);
        }
        this.toroidal = properties.isToroidal();
        this.dimensions = shape.length;
        this.tileSide = tileSide;
        this.tileShift = Integer.numberOfTrailingZeros(tileSide);
        this.tileMask = tileSide - 1;
        if ((long) tileShift * dimensions >= Integer.SIZE - 1) {
            throw new IllegalArgumentException("A tile of " + tileSide + " cells per side in "
                    + dimensions + " dimensions holds more cells than an int can index");
        }
        this.cellsPerTileShift = tileShift * dimensions;

        this.tileCounts = new int[dimensions];
        for (int i = 0; i < dimensions; i++) {
            int size = shape[i];
            if (size < 1) {
                throw new IllegalArgumentException("World dimension " + i + " is " + size
                        + "; every dimension must be at least " + tileSide);
            }
            if (size % tileSide != 0) {
                int below = size - size % tileSide;
                int above = below + tileSide;
                String nearest = below > 0 ? below + " and " + above : String.valueOf(above);
                throw new IllegalArgumentException("World dimension " + i + " is " + size
                        + ", which is not a multiple of " + tileSide + "; the nearest valid sizes are " + nearest);
            }
            tileCounts[i] = size / tileSide;
        }

        this.tileStrides = new int[dimensions];
        long tiles = 1L;
        for (int i = dimensions - 1; i >= 0; i--) {
            tileStrides[i] = (int) tiles;
            tiles *= tileCounts[i];
        }
        long cells = tiles << cellsPerTileShift;
        if (tiles > Integer.MAX_VALUE || cells > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("World too large: " + cells
                    + " cells exceeds Integer.MAX_VALUE (2.1 billion). Reduce environment dimensions. Shape: "
                    + Arrays.toString(shape));
        }
        this.totalCells = (int) cells;
    }

    /**
     * @return cells per tile along each dimension
     */
    int tileSide() {
        return tileSide;
    }

    /**
     * @return the number of cells in the world, the product of all dimension sizes
     */
    int totalCells() {
        return totalCells;
    }

    /**
     * @return the number of dimensions
     */
    int dimensions() {
        return dimensions;
    }

    /**
     * Whether a coordinate names a cell of the world: every component lies in
     * {@code [0, shape[i])}. No normalization takes place, so in a toroidal world a coordinate
     * that would wrap onto a cell is still outside.
     *
     * @param coord the coordinate, one entry per dimension
     * @return {@code true} if every component is in range
     */
    boolean contains(int[] coord) {
        for (int i = 0; i < dimensions; i++) {
            if (Integer.compareUnsigned(coord[i], shape[i]) >= 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Converts a coordinate into its internal index.
     *
     * @param coord the coordinate; every component must lie within the world, no normalization
     *              takes place
     * @return the internal index of that cell
     */
    int index(int[] coord) {
        int tile = 0;
        int offset = 0;
        for (int i = 0; i < dimensions; i++) {
            int c = coord[i];
            tile += (c >> tileShift) * tileStrides[i];
            offset |= (c & tileMask) << (tileShift * i);
        }
        return (tile << cellsPerTileShift) | offset;
    }

    /**
     * Converts an internal index into its coordinate without allocating.
     *
     * @param index the internal index
     * @param out   receives the coordinate; one entry per dimension
     */
    void coordinate(int index, int[] out) {
        int tile = index >>> cellsPerTileShift;
        int offset = index & ((1 << cellsPerTileShift) - 1);
        for (int i = 0; i < dimensions; i++) {
            int tilePosition = tile / tileStrides[i];
            tile -= tilePosition * tileStrides[i];
            out[i] = (tilePosition << tileShift) | ((offset >>> (tileShift * i)) & tileMask);
        }
    }

    /**
     * Returns the index of the cell one step away along a dimension.
     * <p>
     * Inside a tile the step is an addition; at a tile edge the step continues in the neighbouring
     * tile, and at the world edge it wraps around in a toroidal world.
     *
     * @param index   the internal index of the cell to step from
     * @param dim     the dimension to step along
     * @param forward {@code true} to step towards higher coordinates, {@code false} towards lower
     * @return the internal index of the neighbouring cell, or {@code -1} if the step leaves a
     *         bounded world
     */
    int step(int index, int dim, boolean forward) {
        int unit = 1 << (tileShift * dim);
        int offset = (index >>> (tileShift * dim)) & tileMask;
        if (forward) {
            if (offset < tileMask) {
                return index + unit;
            }
            int tilePosition = (index >>> cellsPerTileShift) / tileStrides[dim] % tileCounts[dim];
            int backToTileStart = tileMask * unit;
            if (tilePosition < tileCounts[dim] - 1) {
                return index + (tileStrides[dim] << cellsPerTileShift) - backToTileStart;
            }
            if (!toroidal) {
                return -1;
            }
            return index - ((tileCounts[dim] - 1) * tileStrides[dim] << cellsPerTileShift) - backToTileStart;
        }
        if (offset > 0) {
            return index - unit;
        }
        int tilePosition = (index >>> cellsPerTileShift) / tileStrides[dim] % tileCounts[dim];
        int toTileEnd = tileMask * unit;
        if (tilePosition > 0) {
            return index - (tileStrides[dim] << cellsPerTileShift) + toTileEnd;
        }
        if (!toroidal) {
            return -1;
        }
        return index + ((tileCounts[dim] - 1) * tileStrides[dim] << cellsPerTileShift) + toTileEnd;
    }

    /**
     * Converts an internal index into the persisted row-major index of
     * {@link EnvironmentProperties}, the numbering in which cells are serialized.
     *
     * @param index the internal index
     * @return the persisted index of the same cell
     */
    int canonical(int index) {
        int tile = index >>> cellsPerTileShift;
        int offset = index & ((1 << cellsPerTileShift) - 1);
        int canonical = 0;
        for (int i = 0; i < dimensions; i++) {
            int tilePosition = tile / tileStrides[i];
            tile -= tilePosition * tileStrides[i];
            int c = (tilePosition << tileShift) | ((offset >>> (tileShift * i)) & tileMask);
            canonical += c * canonicalStrides[i];
        }
        return canonical;
    }

    /**
     * The canonical index of the first cell of a tile: the cell at offset 0, whose coordinate is
     * the tile position times the tile side in every dimension. With {@link #canonicalOffset(int)}
     * this splits {@link #canonical(int)} into a part that costs one division per dimension and is
     * shared by all cells of a tile, and a part that costs only shifts.
     *
     * @param tile the tile number, {@code index >>> cellsPerTileShift()}
     * @return the canonical index of the tile's first cell
     */
    int canonicalOfTile(int tile) {
        int canonical = 0;
        for (int i = 0; i < dimensions; i++) {
            int tilePosition = tile / tileStrides[i];
            tile -= tilePosition * tileStrides[i];
            canonical += (tilePosition << tileShift) * canonicalStrides[i];
        }
        return canonical;
    }

    /**
     * The canonical distance of a cell from the first cell of its tile, from the cell's offset
     * inside the tile. Adding it to {@link #canonicalOfTile(int)} gives the cell's canonical index.
     *
     * @param offset the offset inside the tile, {@code index & (cellsPerTile - 1)}
     * @return the canonical index difference to the tile's first cell
     */
    int canonicalOffset(int offset) {
        int canonical = 0;
        for (int i = 0; i < dimensions; i++) {
            canonical += ((offset >>> (tileShift * i)) & tileMask) * canonicalStrides[i];
        }
        return canonical;
    }

    /**
     * @return log2 of the cells per tile: an internal index shifted right by it is the tile number,
     *         its low bits are the offset inside the tile
     */
    int cellsPerTileShift() {
        return cellsPerTileShift;
    }

}
