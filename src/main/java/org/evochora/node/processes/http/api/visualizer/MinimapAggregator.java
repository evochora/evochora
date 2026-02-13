package org.evochora.node.processes.http.api.visualizer;

import java.util.HashMap;
import java.util.Map;

import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.runtime.Config;
import org.evochora.runtime.model.EnvironmentProperties;

/**
 * Aggregates environment cell data into a minimap representation.
 * <p>
 * This class is stateless and thread-safe. It performs downsampling of the environment
 * grid into a fixed-size minimap while preserving the aspect ratio. Cell types are
 * aggregated using majority voting - the most common cell type in each block wins.
 * Owner IDs are aggregated similarly - the most frequent non-zero owner per pixel wins.
 * <p>
 * <strong>Cell Types:</strong>
 * <ul>
 *   <li>0 = CODE (non-empty instruction)</li>
 *   <li>1 = DATA</li>
 *   <li>2 = ENERGY</li>
 *   <li>3 = STRUCTURE</li>
 *   <li>4 = LABEL</li>
 *   <li>5 = LABELREF</li>
 *   <li>6 = REGISTER</li>
 *   <li>7 = EMPTY (CODE with value 0)</li>
 * </ul>
 * <p>
 * <strong>Performance:</strong> For a 4000x3000 environment with 5% occupancy (~600K cells),
 * aggregation completes in approximately 2-5ms.
 */
public class MinimapAggregator {

    /**
     * Maximum dimension (width or height) of the generated minimap in pixels.
     * The actual dimensions preserve the environment's aspect ratio.
     */
    private static final int MAX_SIZE = 300;

    /**
     * Number of cell types tracked for majority voting.
     * Types 0-6 are molecule types (CODE, DATA, ENERGY, STRUCTURE, LABEL, LABELREF, REGISTER),
     * type 7 is EMPTY (CODE with value 0).
     */
    private static final int NUM_TYPES = 8;

    /**
     * Type value used for EMPTY cells (CODE with value 0).
     */
    private static final byte TYPE_EMPTY = 7;

    /**
     * Result of minimap aggregation containing dimensions, cell type data, and ownership data.
     *
     * @param width     Width of the minimap in pixels
     * @param height    Height of the minimap in pixels
     * @param cellTypes Cell type values (0-7), one byte per pixel in row-major order
     * @param ownerIds  Dominant owner organism ID per pixel (0 = unowned), in row-major order
     */
    public record MinimapResult(int width, int height, byte[] cellTypes, int[] ownerIds) {}

    /**
     * Aggregates environment cell data into a minimap.
     * <p>
     * The minimap dimensions are calculated to fit within {@link #MAX_SIZE} while
     * preserving the environment's aspect ratio. Each minimap pixel represents a
     * block of environment cells, with the cell type determined by majority voting
     * (the most common type wins). Ownership is aggregated similarly - the most
     * frequent non-zero owner per pixel wins.
     *
     * @param columns  The cell data in columnar format from {@code TickData.getCellColumns()}
     * @param envProps Environment properties containing world shape
     * @return Minimap result with dimensions and cell type data, or null if environment is invalid
     */
    public MinimapResult aggregate(final CellDataColumns columns, final EnvironmentProperties envProps) {
        final int[] shape = envProps.getWorldShape();
        if (shape == null || shape.length < 2) {
            return null;
        }

        final int worldWidth = shape[0];
        final int worldHeight = shape[1];

        if (worldWidth <= 0 || worldHeight <= 0) {
            return null;
        }

        // Calculate minimap dimensions preserving aspect ratio
        final int minimapWidth;
        final int minimapHeight;
        if (worldWidth >= worldHeight) {
            minimapWidth = MAX_SIZE;
            minimapHeight = Math.max(1, Math.round((float) MAX_SIZE * worldHeight / worldWidth));
        } else {
            minimapHeight = MAX_SIZE;
            minimapWidth = Math.max(1, Math.round((float) MAX_SIZE * worldWidth / worldHeight));
        }

        final int minimapSize = minimapWidth * minimapHeight;

        // Track type counts for each minimap pixel (for majority voting)
        // counts[pixelIndex * NUM_TYPES + typeIndex] = count of that type
        final short[] counts = new short[minimapSize * NUM_TYPES];

        // Track owner votes per pixel using composite key: (pixelIndex << 32 | ownerId)
        // Only non-zero owners participate in voting.
        final Map<Long, Short> ownerVotes = new HashMap<>();

        // Calculate scale factors as floats to ensure the entire world is covered.
        // Using integer division would cause cells at the edge to wrap around.
        // E.g., for 800x600 world with 300x225 minimap:
        //   - Integer: scale=2, covers only 600x450 cells, rest wraps
        //   - Float: scale=2.67, covers all 800x600 cells correctly
        final float scaleX = (float) worldWidth / minimapWidth;
        final float scaleY = (float) worldHeight / minimapHeight;

        // Iterate only over occupied cells (sparse iteration)
        final int cellCount = columns.getFlatIndicesCount();
        final boolean hasOwners = columns.getOwnerIdsCount() == cellCount;

        for (int i = 0; i < cellCount; i++) {
            final int flatIndex = columns.getFlatIndices(i);
            final int moleculeData = columns.getMoleculeData(i);

            // Extract world coordinates from flat index (row-major: flatIndex = x * height + y)
            final int x = flatIndex / worldHeight;
            final int y = flatIndex % worldHeight;

            // Map to minimap coordinates using float scale
            // Clamp to minimap bounds to handle edge cases (rounding at world edge)
            final int mx = Math.min((int) (x / scaleX), minimapWidth - 1);
            final int my = Math.min((int) (y / scaleY), minimapHeight - 1);
            final int mIdx = my * minimapWidth + mx;

            // Determine cell type (with EMPTY detection)
            final int cellType = classifyCellType(moleculeData);

            // Increment count for this type at this pixel
            counts[mIdx * NUM_TYPES + cellType]++;

            // Track ownership votes (skip unowned cells)
            if (hasOwners) {
                final int ownerId = columns.getOwnerIds(i);
                if (ownerId != 0) {
                    final long key = ((long) mIdx << 32) | (ownerId & 0xFFFFFFFFL);
                    ownerVotes.merge(key, (short) 1, (a, b) -> (short) (a + b));
                }
            }
        }

        // Add background (empty) cells to the count with reduced weight
        // Each minimap pixel represents approximately scaleX * scaleY environment cells
        // Cells not in the data are truly empty background
        // Empty cells count at 4% weight to avoid always winning in sparse areas
        final int cellsPerBlock = (int) (scaleX * scaleY);
        for (int i = 0; i < minimapSize; i++) {
            int totalCounted = 0;
            final int baseIdx = i * NUM_TYPES;
            for (int t = 0; t < NUM_TYPES; t++) {
                totalCounted += counts[baseIdx + t];
            }
            // Add missing cells as EMPTY with 6.25% weight
            final int backgroundCells = cellsPerBlock - totalCounted;
            if (backgroundCells > 0) {
                final int weightedEmpty = backgroundCells / 25;  // 4% weight
                counts[baseIdx + TYPE_EMPTY] += (short) Math.min(weightedEmpty, Short.MAX_VALUE);
            }
        }

        // Build cell type result by selecting majority type for each pixel
        final byte[] minimap = new byte[minimapSize];
        for (int i = 0; i < minimapSize; i++) {
            minimap[i] = findMajorityType(counts, i);
        }

        // Build ownership result by selecting dominant owner per pixel
        final int[] ownerIds = resolveOwnerIds(ownerVotes, minimapSize);

        return new MinimapResult(minimapWidth, minimapHeight, minimap, ownerIds);
    }

    /**
     * Classifies a cell's molecule data into a type for the minimap.
     * Distinguishes EMPTY (CODE with value 0) from regular CODE.
     *
     * @param moleculeData Raw molecule data from the environment
     * @return Cell type (0-7)
     */
    private int classifyCellType(final int moleculeData) {
        final int rawType = (moleculeData & Config.TYPE_MASK) >> Config.TYPE_SHIFT;

        // Check for EMPTY: CODE type with value 0
        if (rawType == 0) {  // TYPE_CODE
            final int value = moleculeData & Config.VALUE_MASK;
            if (value == 0) {
                return TYPE_EMPTY;
            }
        }

        // Guard against unknown types overflowing the counts array
        if (rawType >= TYPE_EMPTY) {
            return TYPE_EMPTY;
        }

        return rawType;
    }

    /**
     * Finds the majority cell type for a minimap pixel.
     * If no cells were counted (all empty background), returns EMPTY.
     *
     * @param counts Type count array
     * @param pixelIndex Minimap pixel index
     * @return The most common cell type (0-7)
     */
    private byte findMajorityType(final short[] counts, final int pixelIndex) {
        final int baseIdx = pixelIndex * NUM_TYPES;
        int maxCount = 0;
        byte majorityType = TYPE_EMPTY;  // Default to EMPTY if no cells

        for (int t = 0; t < NUM_TYPES; t++) {
            final int count = counts[baseIdx + t];
            if (count > maxCount) {
                maxCount = count;
                majorityType = (byte) t;
            }
        }

        return majorityType;
    }

    /**
     * Resolves the dominant (most frequent) owner per minimap pixel from the vote map.
     * Pixels with no ownership votes get owner ID 0 (unowned).
     *
     * @param ownerVotes Composite-key map: (pixelIndex &lt;&lt; 32 | ownerId) → vote count
     * @param minimapSize Total number of minimap pixels
     * @return Array of dominant owner IDs, one per pixel (0 = unowned)
     */
    private int[] resolveOwnerIds(final Map<Long, Short> ownerVotes, final int minimapSize) {
        final int[] ownerIds = new int[minimapSize];
        final short[] maxCounts = new short[minimapSize];

        for (final var entry : ownerVotes.entrySet()) {
            final long key = entry.getKey();
            final int pixelIndex = (int) (key >>> 32);
            final int ownerId = (int) key;
            final short count = entry.getValue();

            if (count > maxCounts[pixelIndex]) {
                maxCounts[pixelIndex] = count;
                ownerIds[pixelIndex] = ownerId;
            }
        }

        return ownerIds;
    }
}
