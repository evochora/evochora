package org.evochora.runtime.label;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.evochora.runtime.Config;
import org.evochora.runtime.model.Environment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Pre-expanded Hamming distance matching strategy for O(1) label lookup.
 * <p>
 * This strategy pre-expands the index: for each label with value V, entries are stored
 * under V and all ~210 Hamming neighbors (for tolerance=2). This enables O(1) lookup
 * at the cost of increased memory usage (~211 entries per label).
 * <p>
 * Memory usage for tolerance=2:
 * <ul>
 *   <li>10,000 labels: ~50 MB</li>
 *   <li>100,000 labels: ~500 MB</li>
 * </ul>
 * <p>
 * Thread Safety: Not thread-safe. All operations are expected to be called from
 * the main simulation thread.
 */
public class PreExpandedHammingStrategy implements ILabelMatchingStrategy {

    private static final int VALUE_BITS = Config.VALUE_BITS; // 20 bits

    private final int tolerance;
    private final int foreignPenalty;
    private final int hammingWeight;

    /**
     * Maps label value -> set of LabelEntry objects.
     * Pre-expanded: each label appears under its own value AND all Hamming neighbors.
     */
    private final Int2ObjectOpenHashMap<List<LabelEntry>> valueToLabels;

    /**
     * Maps flatIndex -> (labelValue, LabelEntry) for efficient updates.
     * Enables O(1) lookup when owner/marker changes.
     */
    private final Int2ObjectOpenHashMap<LabelEntryWithValue> indexToEntry;

    /**
     * Helper record to track original label value for index updates.
     */
    private record LabelEntryWithValue(int labelValue, LabelEntry entry) {}

    /** Default Hamming distance tolerance. */
    public static final int DEFAULT_TOLERANCE = 2;

    /** Default score penalty for foreign labels. */
    public static final int DEFAULT_FOREIGN_PENALTY = 100;

    /** Default score weight per Hamming distance. */
    public static final int DEFAULT_HAMMING_WEIGHT = 50;

    /**
     * Creates a new pre-expanded Hamming strategy with default settings.
     */
    public PreExpandedHammingStrategy() {
        this(DEFAULT_TOLERANCE, DEFAULT_FOREIGN_PENALTY, DEFAULT_HAMMING_WEIGHT);
    }

    /**
     * Creates a new pre-expanded Hamming strategy from configuration.
     * <p>
     * Reads "tolerance", "foreignPenalty", and "hammingWeight" from the config,
     * using defaults if not specified.
     *
     * @param options The configuration options
     */
    public PreExpandedHammingStrategy(com.typesafe.config.Config options) {
        this(
            options.hasPath("tolerance") ? options.getInt("tolerance") : DEFAULT_TOLERANCE,
            options.hasPath("foreignPenalty") ? options.getInt("foreignPenalty") : DEFAULT_FOREIGN_PENALTY,
            options.hasPath("hammingWeight") ? options.getInt("hammingWeight") : DEFAULT_HAMMING_WEIGHT
        );
    }

    /**
     * Creates a new pre-expanded Hamming strategy with specified settings.
     *
     * @param tolerance The Hamming distance tolerance (2 = ~211 neighbors, 3 = ~1351 neighbors)
     * @param foreignPenalty The score penalty for foreign labels
     * @param hammingWeight The score weight per Hamming distance
     */
    public PreExpandedHammingStrategy(int tolerance, int foreignPenalty, int hammingWeight) {
        this.tolerance = tolerance;
        this.foreignPenalty = foreignPenalty;
        this.hammingWeight = hammingWeight;
        this.valueToLabels = new Int2ObjectOpenHashMap<>();
        this.indexToEntry = new Int2ObjectOpenHashMap<>();
    }

    @Override
    public int findTarget(int searchValue, int codeOwner, int[] callerCoords, Environment environment) {
        List<LabelEntry> candidates = valueToLabels.get(searchValue);
        if (candidates == null || candidates.isEmpty()) {
            return -1;
        }

        // Get environment shape for toroidal distance calculation
        int[] shape = environment.getShape();

        // === PHASE 1: Early exit - find best own label with Hamming=0 ===
        // Own exact match always wins, so we can skip full scoring if one exists
        int bestOwnExactDistance = Integer.MAX_VALUE;
        int bestOwnExactIndex = -1;
        int bestOwnExactOwner = Integer.MAX_VALUE;

        for (LabelEntry entry : candidates) {
            LabelEntryWithValue entryWithValue = indexToEntry.get(entry.flatIndex());
            if (entryWithValue == null) {
                continue;
            }

            int actualValue = entryWithValue.labelValue();
            int hamming = hammingDistance(searchValue, actualValue);

            // Only consider own labels with exact match
            if (hamming == 0 && !entry.isForeign(codeOwner)) {
                int[] labelCoords = environment.getCoordinateFromIndex(entry.flatIndex());
                int distance = toroidalManhattanDistance(callerCoords, labelCoords, shape);

                if (distance < bestOwnExactDistance ||
                    (distance == bestOwnExactDistance && entry.owner() < bestOwnExactOwner)) {
                    bestOwnExactDistance = distance;
                    bestOwnExactIndex = entry.flatIndex();
                    bestOwnExactOwner = entry.owner();
                }
            }
        }

        // If we found an own exact match, it always wins
        if (bestOwnExactIndex != -1) {
            return bestOwnExactIndex;
        }

        // === PHASE 2: Full scoring with combined formula ===
        // score = (hamming × hammingWeight) + distance + (foreign ? foreignPenalty : 0)
        int bestScore = Integer.MAX_VALUE;
        int bestFlatIndex = -1;
        int bestOwner = Integer.MAX_VALUE;

        for (LabelEntry entry : candidates) {
            LabelEntryWithValue entryWithValue = indexToEntry.get(entry.flatIndex());
            if (entryWithValue == null) {
                continue;
            }

            int actualValue = entryWithValue.labelValue();
            int hamming = hammingDistance(searchValue, actualValue);

            if (hamming > tolerance) {
                continue;
            }

            int[] labelCoords = environment.getCoordinateFromIndex(entry.flatIndex());
            int distance = toroidalManhattanDistance(callerCoords, labelCoords, shape);

            // Combined score formula
            int score = (hamming * hammingWeight) + distance + (entry.isForeign(codeOwner) ? foreignPenalty : 0);

            if (score < bestScore || (score == bestScore && entry.owner() < bestOwner)) {
                bestScore = score;
                bestFlatIndex = entry.flatIndex();
                bestOwner = entry.owner();
            }
        }

        return bestFlatIndex;
    }

    /**
     * Calculates the toroidal Manhattan distance between two coordinates.
     * <p>
     * For each dimension, uses the shorter path (direct or wrap-around).
     *
     * @param a First coordinate
     * @param b Second coordinate
     * @param shape The environment shape (for wrap-around calculation)
     * @return The toroidal Manhattan distance
     */
    private static int toroidalManhattanDistance(int[] a, int[] b, int[] shape) {
        int distance = 0;
        for (int i = 0; i < a.length; i++) {
            int diff = Math.abs(a[i] - b[i]);
            int wrapDiff = shape[i] - diff;
            distance += Math.min(diff, wrapDiff);
        }
        return distance;
    }

    @Override
    public void addLabel(int labelValue, LabelEntry entry) {
        // Store in the flat index lookup
        indexToEntry.put(entry.flatIndex(), new LabelEntryWithValue(labelValue, entry));

        // Add to the value and all Hamming neighbors
        IntOpenHashSet neighbors = getHammingNeighbors(labelValue, tolerance);
        neighbors.add(labelValue); // Include the value itself

        neighbors.forEach((int neighborValue) -> {
            valueToLabels.computeIfAbsent(neighborValue, k -> new ArrayList<>()).add(entry);
        });
    }

    @Override
    public void removeLabel(int labelValue, int flatIndex) {
        LabelEntryWithValue entryWithValue = indexToEntry.remove(flatIndex);
        if (entryWithValue == null) {
            return;
        }

        // Remove from the value and all Hamming neighbors
        IntOpenHashSet neighbors = getHammingNeighbors(labelValue, tolerance);
        neighbors.add(labelValue);

        neighbors.forEach((int neighborValue) -> {
            List<LabelEntry> list = valueToLabels.get(neighborValue);
            if (list != null) {
                list.removeIf(e -> e.flatIndex() == flatIndex);
                if (list.isEmpty()) {
                    valueToLabels.remove(neighborValue);
                }
            }
        });
    }

    @Override
    public void updateOwner(int labelValue, int flatIndex, int newOwner) {
        LabelEntryWithValue oldEntryWithValue = indexToEntry.get(flatIndex);
        if (oldEntryWithValue == null) {
            return;
        }

        LabelEntry oldEntry = oldEntryWithValue.entry();
        LabelEntry newEntry = new LabelEntry(flatIndex, newOwner, oldEntry.marker());

        // Update the flat index lookup
        indexToEntry.put(flatIndex, new LabelEntryWithValue(labelValue, newEntry));

        // Update in all neighbor lists
        IntOpenHashSet neighbors = getHammingNeighbors(labelValue, tolerance);
        neighbors.add(labelValue);

        neighbors.forEach((int neighborValue) -> {
            List<LabelEntry> list = valueToLabels.get(neighborValue);
            if (list != null) {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i).flatIndex() == flatIndex) {
                        list.set(i, newEntry);
                        break;
                    }
                }
            }
        });
    }

    @Override
    public void updateMarker(int labelValue, int flatIndex, int newMarker) {
        LabelEntryWithValue oldEntryWithValue = indexToEntry.get(flatIndex);
        if (oldEntryWithValue == null) {
            return;
        }

        LabelEntry oldEntry = oldEntryWithValue.entry();
        LabelEntry newEntry = new LabelEntry(flatIndex, oldEntry.owner(), newMarker);

        // Update the flat index lookup
        indexToEntry.put(flatIndex, new LabelEntryWithValue(labelValue, newEntry));

        // Update in all neighbor lists
        IntOpenHashSet neighbors = getHammingNeighbors(labelValue, tolerance);
        neighbors.add(labelValue);

        neighbors.forEach((int neighborValue) -> {
            List<LabelEntry> list = valueToLabels.get(neighborValue);
            if (list != null) {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i).flatIndex() == flatIndex) {
                        list.set(i, newEntry);
                        break;
                    }
                }
            }
        });
    }

    @Override
    public Collection<LabelEntry> getCandidates(int searchValue) {
        List<LabelEntry> candidates = valueToLabels.get(searchValue);
        return candidates != null ? Collections.unmodifiableList(candidates) : Collections.emptyList();
    }

    @Override
    public int getTolerance() {
        return tolerance;
    }

    @Override
    public int getForeignPenalty() {
        return foreignPenalty;
    }

    @Override
    public int getHammingWeight() {
        return hammingWeight;
    }

    /**
     * Calculates the Hamming distance between two values.
     *
     * @param a First value
     * @param b Second value
     * @return Number of differing bits
     */
    private static int hammingDistance(int a, int b) {
        return Integer.bitCount(a ^ b);
    }

    /**
     * Gets all Hamming neighbors of a value within the given tolerance.
     * <p>
     * For tolerance=2, this returns ~210 values (20 single-bit flips + 190 double-bit flips).
     *
     * @param value The center value
     * @param tolerance Maximum Hamming distance
     * @return Set of all neighbor values (excluding the center value itself)
     */
    private static IntOpenHashSet getHammingNeighbors(int value, int tolerance) {
        IntOpenHashSet neighbors = new IntOpenHashSet();

        // Distance 1: flip each bit
        for (int i = 0; i < VALUE_BITS; i++) {
            neighbors.add(value ^ (1 << i));
        }

        // Distance 2: flip each pair of bits
        if (tolerance >= 2) {
            for (int i = 0; i < VALUE_BITS; i++) {
                for (int j = i + 1; j < VALUE_BITS; j++) {
                    neighbors.add(value ^ (1 << i) ^ (1 << j));
                }
            }
        }

        // Distance 3: flip each triple of bits (optional, much more memory)
        if (tolerance >= 3) {
            for (int i = 0; i < VALUE_BITS; i++) {
                for (int j = i + 1; j < VALUE_BITS; j++) {
                    for (int k = j + 1; k < VALUE_BITS; k++) {
                        neighbors.add(value ^ (1 << i) ^ (1 << j) ^ (1 << k));
                    }
                }
            }
        }

        return neighbors;
    }
}
