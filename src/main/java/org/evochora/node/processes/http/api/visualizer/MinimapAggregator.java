package org.evochora.node.processes.http.api.visualizer;

import java.util.HashMap;
import java.util.Map;

import java.util.Arrays;

import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.runtime.Config;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.MoleculeTypeRegistry;

/**
 * Aggregates environment cell data into a minimap representation.
 * <p>
 * This class is stateless and thread-safe. It performs downsampling of the environment
 * grid into a fixed-size minimap while preserving the aspect ratio. Cell types are
 * aggregated using majority voting - the most common cell type in each block wins.
 * Owner IDs are aggregated similarly - the most frequent non-zero owner per pixel wins.
 * <p>
 * <strong>Cell Types:</strong> A pixel whose winning cell carries a molecule type registered in
 * {@link MoleculeTypeRegistry} is encoded as that type's raw index, the type bits of the packed
 * molecule shifted down: 0 = CODE, 1 = DATA, 2 = ENERGY and so on. Two bytes outside that range
 * stand for what is not a type: {@link #TYPE_EMPTY} for a pixel holding no molecule, which
 * includes CODE with value 0, and {@link #TYPE_UNKNOWN} for a molecule whose type the registry
 * does not know.
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
     * Number of vote slots per minimap pixel: one for each registered molecule type, plus one for
     * empty cells and one for molecules of an unregistered type.
     */
    private static final int NUM_SLOTS = MoleculeTypeRegistry.typeCount() + 2;

    /** Vote slot for empty cells, the second-to-last slot. */
    private static final int EMPTY_SLOT = NUM_SLOTS - 2;

    /** Vote slot for molecules whose type is not registered, the last slot. */
    private static final int UNKNOWN_SLOT = NUM_SLOTS - 1;

    /**
     * Byte reported for a pixel holding no molecule. Outside the range of raw type indices, so the
     * client tells it apart from every molecule type.
     */
    public static final byte TYPE_EMPTY = (byte) 255;

    /**
     * Byte reported for a pixel whose molecules carry a type the registry does not know. Outside
     * the range of raw type indices, so a mismatch between server and client is visible instead of
     * being drawn as empty space.
     */
    public static final byte TYPE_UNKNOWN = (byte) 254;

    /** Encoded byte per vote slot: the raw type index of a registered type, or one of the two sentinels. */
    private static final byte[] BYTE_BY_SLOT = buildByteBySlot();

    /** Vote slot per raw type index; the unknown slot where the index belongs to no registered type. */
    private static final int[] SLOT_BY_RAW_INDEX = buildSlotByRawIndex();

    /**
     * Builds the encoded minimap byte of every vote slot.
     *
     * @return The byte a winning slot is reported as, indexed by slot.
     */
    private static byte[] buildByteBySlot() {
        byte[] bytes = new byte[NUM_SLOTS];
        for (int type : MoleculeTypeRegistry.orderedTypes()) {
            bytes[MoleculeTypeRegistry.indexOf(type)] = (byte) rawIndex(type);
        }
        bytes[EMPTY_SLOT] = TYPE_EMPTY;
        bytes[UNKNOWN_SLOT] = TYPE_UNKNOWN;
        return bytes;
    }

    /**
     * Builds the lookup from a molecule's raw type index to its vote slot.
     *
     * @return An array whose entry is the vote slot of that raw type index.
     */
    private static int[] buildSlotByRawIndex() {
        int highest = 0;
        for (int type : MoleculeTypeRegistry.orderedTypes()) {
            highest = Math.max(highest, rawIndex(type));
        }
        int[] slots = new int[highest + 1];
        Arrays.fill(slots, UNKNOWN_SLOT);
        for (int type : MoleculeTypeRegistry.orderedTypes()) {
            slots[rawIndex(type)] = MoleculeTypeRegistry.indexOf(type);
        }
        return slots;
    }

    /**
     * Returns the position of a molecule type's bits within the packed molecule integer, shifted
     * down to a small index.
     *
     * @param type The shifted type constant.
     * @return The raw type index.
     */
    private static int rawIndex(int type) {
        return (type & Config.TYPE_MASK) >>> Config.TYPE_SHIFT;
    }

    /**
     * Result of minimap aggregation containing dimensions, cell type data, and ownership data.
     *
     * @param width     Width of the minimap in pixels
     * @param height    Height of the minimap in pixels
     * @param cellTypes Encoded cell types, one byte per pixel in row-major order: the raw type
     *                  index of a registered type, {@link #TYPE_EMPTY} or {@link #TYPE_UNKNOWN}
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
        // counts[pixelIndex * NUM_SLOTS + slot] = count of that slot
        final short[] counts = new short[minimapSize * NUM_SLOTS];

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

            // Determine the vote slot (with EMPTY detection)
            final int slot = classifyCellSlot(moleculeData);

            // Increment count for this slot at this pixel
            counts[mIdx * NUM_SLOTS + slot]++;

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
            final int baseIdx = i * NUM_SLOTS;
            for (int t = 0; t < NUM_SLOTS; t++) {
                totalCounted += counts[baseIdx + t];
            }
            // Add missing cells as EMPTY with 2.5% weight
            final int backgroundCells = cellsPerBlock - totalCounted;
            if (backgroundCells > 0) {
                final int weightedEmpty = backgroundCells / 25;  // 4% weight
                counts[baseIdx + EMPTY_SLOT] += (short) Math.min(weightedEmpty, Short.MAX_VALUE);
            }
        }

        // Build cell type result by selecting majority type for each pixel
        final byte[] minimap = new byte[minimapSize];
        for (int i = 0; i < minimapSize; i++) {
            minimap[i] = BYTE_BY_SLOT[findMajoritySlot(counts, i)];
        }

        // Build ownership result by selecting dominant owner per pixel
        final int[] ownerIds = resolveOwnerIds(ownerVotes, minimapSize);

        return new MinimapResult(minimapWidth, minimapHeight, minimap, ownerIds);
    }

    /**
     * Classifies a cell's molecule data into the vote slot it counts towards.
     * A CODE molecule with value 0 is empty space, and a molecule whose type the registry does not
     * know counts as unknown.
     *
     * @param moleculeData Raw molecule data from the environment
     * @return The vote slot, in {@code 0 .. NUM_SLOTS - 1}
     */
    private int classifyCellSlot(final int moleculeData) {
        final int rawType = (moleculeData & Config.TYPE_MASK) >>> Config.TYPE_SHIFT;

        // Check for EMPTY: CODE type with value 0
        if (rawType == rawIndex(Config.TYPE_CODE) && (moleculeData & Config.VALUE_MASK) == 0) {
            return EMPTY_SLOT;
        }

        return rawType < SLOT_BY_RAW_INDEX.length ? SLOT_BY_RAW_INDEX[rawType] : UNKNOWN_SLOT;
    }

    /**
     * Finds the majority vote slot for a minimap pixel.
     * If no cells were counted (all empty background), returns the empty slot.
     *
     * @param counts Slot count array
     * @param pixelIndex Minimap pixel index
     * @return The slot with the most votes
     */
    private int findMajoritySlot(final short[] counts, final int pixelIndex) {
        final int baseIdx = pixelIndex * NUM_SLOTS;
        int maxCount = 0;
        int majoritySlot = EMPTY_SLOT;  // Default to EMPTY if no cells

        for (int t = 0; t < NUM_SLOTS; t++) {
            final int count = counts[baseIdx + t];
            if (count > maxCount) {
                maxCount = count;
                majoritySlot = t;
            }
        }

        return majoritySlot;
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
