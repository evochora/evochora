package org.evochora.cli.rendering.frame;

import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.runtime.Config;

/**
 * Reusable environment cell background renderer with aggregation and majority voting.
 * <p>
 * Manages cell type state for a scaled-down output grid and renders the background
 * using majority voting among cell types that map to each output pixel. Used as a
 * composable layer by renderers that need an environment background (e.g.
 * {@link MinimapFrameRenderer}, {@link DensityMapRenderer}).
 * <p>
 * <strong>Performance:</strong> Uses generation numbers for O(1) state reset instead of
 * O(worldSize) Arrays.fill. Aggregation counts are built incrementally during cell processing.
 * <p>
 * <strong>Thread Safety:</strong> Not thread-safe. Create one instance per renderer/thread.
 */
public class EnvironmentBackgroundLayer {

    // ─────────────────────────────────────────────────────────────────────────────
    // Cell type constants and colors
    // ─────────────────────────────────────────────────────────────────────────────

    /** Cell type index for CODE molecules. */
    public static final int TYPE_CODE = 0;
    /** Cell type index for DATA molecules. */
    public static final int TYPE_DATA = 1;
    /** Cell type index for ENERGY molecules. */
    public static final int TYPE_ENERGY = 2;
    /** Cell type index for STRUCTURE molecules. */
    public static final int TYPE_STRUCTURE = 3;
    /** Cell type index for LABEL molecules. */
    public static final int TYPE_LABEL = 4;
    /** Cell type index for LABELREF molecules. */
    public static final int TYPE_LABELREF = 5;
    /** Cell type index for REGISTER molecules. */
    public static final int TYPE_REGISTER = 6;
    /** Cell type index for empty cells (no molecule). */
    public static final int TYPE_EMPTY = 7;

    /** Number of non-empty types tracked for aggregation. */
    static final int NUM_NON_EMPTY_TYPES = 7;

    /**
     * RGB colors for each cell type, indexed by type constant.
     * Matches the web visualizer minimap colors.
     */
    public static final int[] CELL_COLORS = {
        0x3c5078,  // CODE - blue-gray
        0x32323c,  // DATA - dark gray
        0xffe664,  // ENERGY - yellow
        0xff7878,  // STRUCTURE - red/pink
        0xa0a0a8,  // LABEL - light gray
        0xa0a0a8,  // LABELREF - same as LABEL
        0x506080,  // REGISTER - medium blue-gray
        0x1e1e28   // EMPTY - dark background
    };

    // ─────────────────────────────────────────────────────────────────────────────
    // Dimensions
    // ─────────────────────────────────────────────────────────────────────────────

    private final int worldWidth;
    private final int worldHeight;
    private final int outputWidth;
    private final int outputHeight;

    // ─────────────────────────────────────────────────────────────────────────────
    // Cell state (generation-based O(1) reset)
    // ─────────────────────────────────────────────────────────────────────────────

    private final int[] cellTypes;
    private final int[] cellGenerations;
    private int currentGeneration;

    // ─────────────────────────────────────────────────────────────────────────────
    // Aggregation counts per output pixel (generation-based O(1) reset)
    // ─────────────────────────────────────────────────────────────────────────────

    private final int[] aggregationCounts;
    private final int[] pixelGenerations;

    /**
     * Creates a new environment background layer.
     *
     * @param worldWidth  World width in cells.
     * @param worldHeight World height in cells.
     * @param outputWidth Output width in pixels.
     * @param outputHeight Output height in pixels.
     */
    public EnvironmentBackgroundLayer(int worldWidth, int worldHeight,
                                      int outputWidth, int outputHeight) {
        this.worldWidth = worldWidth;
        this.worldHeight = worldHeight;
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;

        int worldSize = worldWidth * worldHeight;
        this.cellTypes = new int[worldSize];
        this.cellGenerations = new int[worldSize];
        this.currentGeneration = 0;

        int outputSize = outputWidth * outputHeight;
        this.aggregationCounts = new int[outputSize * NUM_NON_EMPTY_TYPES];
        this.pixelGenerations = new int[outputSize];
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Cell processing
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Processes all cells from a snapshot. Increments the generation to invalidate
     * all previous cell state (O(1) reset).
     *
     * @param columns Cell data columns from a snapshot tick.
     */
    public void processSnapshotCells(CellDataColumns columns) {
        currentGeneration++;

        int cellCount = columns.getFlatIndicesCount();
        for (int i = 0; i < cellCount; i++) {
            int flatIndex = columns.getFlatIndices(i);
            int typeIndex = getCellTypeIndex(columns.getMoleculeData(i));

            cellTypes[flatIndex] = typeIndex;
            cellGenerations[flatIndex] = currentGeneration;

            if (typeIndex != TYPE_EMPTY) {
                updateAggregationForNewCell(flatIndex, typeIndex);
            }
        }
    }

    /**
     * Processes only changed cells from a delta tick. Does not increment the
     * generation — unchanged cells retain their state from the last snapshot.
     *
     * @param changed Cell data columns containing only changed cells.
     */
    public void processDeltaCells(CellDataColumns changed) {
        int changedCount = changed.getFlatIndicesCount();
        for (int i = 0; i < changedCount; i++) {
            int flatIndex = changed.getFlatIndices(i);
            int newTypeIndex = getCellTypeIndex(changed.getMoleculeData(i));

            int oldTypeIndex = (cellGenerations[flatIndex] == currentGeneration)
                    ? cellTypes[flatIndex]
                    : TYPE_EMPTY;

            if (oldTypeIndex != newTypeIndex) {
                updateAggregationForTypeChange(flatIndex, oldTypeIndex, newTypeIndex);
            }

            cellTypes[flatIndex] = newTypeIndex;
            cellGenerations[flatIndex] = currentGeneration;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Rendering
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Renders the aggregated cell background into the provided frame buffer using
     * majority voting. Each output pixel gets the color of the most common cell
     * type in the corresponding world region, with a 2.5% weighting for empty cells.
     *
     * @param frameBuffer Pixel buffer to write into (must be outputWidth * outputHeight).
     */
    public void renderTo(int[] frameBuffer) {
        int totalPixels = outputWidth * outputHeight;
        int cellsPerPixel = (worldWidth / outputWidth) * (worldHeight / outputHeight);

        for (int pixelIdx = 0; pixelIdx < totalPixels; pixelIdx++) {
            if (pixelGenerations[pixelIdx] != currentGeneration) {
                frameBuffer[pixelIdx] = CELL_COLORS[TYPE_EMPTY];
                continue;
            }

            int baseIdx = pixelIdx * NUM_NON_EMPTY_TYPES;

            int nonEmptyTotal = 0;
            for (int t = 0; t < NUM_NON_EMPTY_TYPES; t++) {
                nonEmptyTotal += aggregationCounts[baseIdx + t];
            }

            // Background weighting (2.5%)
            int backgroundCells = cellsPerPixel - nonEmptyTotal;
            int weightedEmpty = backgroundCells / 40;

            int maxCount = weightedEmpty;
            int winningType = TYPE_EMPTY;
            for (int t = 0; t < NUM_NON_EMPTY_TYPES; t++) {
                int count = aggregationCounts[baseIdx + t];
                if (count > maxCount) {
                    maxCount = count;
                    winningType = t;
                }
            }

            frameBuffer[pixelIdx] = CELL_COLORS[winningType];
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Coordinate mapping
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Converts world coordinates to an output pixel index (row-major: y * outputWidth + x).
     * <p>
     * Public so renderers can map organism positions to pixel coordinates using
     * the same mapping as cell aggregation.
     *
     * @param wx World x coordinate.
     * @param wy World y coordinate.
     * @return Output pixel index.
     */
    public int worldCoordsToPixelIndex(int wx, int wy) {
        int mx = (int) ((double) wx / worldWidth * outputWidth);
        int my = (int) ((double) wy / worldHeight * outputHeight);
        if (mx >= outputWidth) mx = outputWidth - 1;
        if (my >= outputHeight) my = outputHeight - 1;
        return my * outputWidth + mx;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private int worldToPixelIndex(int flatIndex) {
        int wx = flatIndex / worldHeight;
        int wy = flatIndex % worldHeight;
        return worldCoordsToPixelIndex(wx, wy);
    }

    private void ensurePixelInitialized(int pixelIdx) {
        if (pixelGenerations[pixelIdx] != currentGeneration) {
            int baseIdx = pixelIdx * NUM_NON_EMPTY_TYPES;
            for (int t = 0; t < NUM_NON_EMPTY_TYPES; t++) {
                aggregationCounts[baseIdx + t] = 0;
            }
            pixelGenerations[pixelIdx] = currentGeneration;
        }
    }

    private void updateAggregationForNewCell(int flatIndex, int typeIndex) {
        int pixelIdx = worldToPixelIndex(flatIndex);
        ensurePixelInitialized(pixelIdx);
        aggregationCounts[pixelIdx * NUM_NON_EMPTY_TYPES + typeIndex]++;
    }

    private void updateAggregationForTypeChange(int flatIndex, int oldType, int newType) {
        int pixelIdx = worldToPixelIndex(flatIndex);

        if (oldType != TYPE_EMPTY && pixelGenerations[pixelIdx] == currentGeneration) {
            aggregationCounts[pixelIdx * NUM_NON_EMPTY_TYPES + oldType]--;
        }

        if (newType != TYPE_EMPTY) {
            ensurePixelInitialized(pixelIdx);
            aggregationCounts[pixelIdx * NUM_NON_EMPTY_TYPES + newType]++;
        }
    }

    /**
     * Maps a raw molecule integer to a cell type index.
     * Returns {@link #TYPE_EMPTY} for empty cells (moleculeInt == 0).
     *
     * @param moleculeInt Raw molecule data from CellDataColumns.
     * @return Cell type index (0-7).
     */
    static int getCellTypeIndex(int moleculeInt) {
        if (moleculeInt == 0) return TYPE_EMPTY;
        int moleculeType = moleculeInt & Config.TYPE_MASK;
        if (moleculeType == Config.TYPE_CODE) return TYPE_CODE;
        if (moleculeType == Config.TYPE_DATA) return TYPE_DATA;
        if (moleculeType == Config.TYPE_ENERGY) return TYPE_ENERGY;
        if (moleculeType == Config.TYPE_STRUCTURE) return TYPE_STRUCTURE;
        if (moleculeType == Config.TYPE_LABEL) return TYPE_LABEL;
        if (moleculeType == Config.TYPE_LABELREF) return TYPE_LABELREF;
        if (moleculeType == Config.TYPE_REGISTER) return TYPE_REGISTER;
        return TYPE_EMPTY;
    }
}
