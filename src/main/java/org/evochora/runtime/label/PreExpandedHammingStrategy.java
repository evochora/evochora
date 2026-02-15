package org.evochora.runtime.label;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.evochora.runtime.Config;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.spi.IRandomProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.IntConsumer;

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

    /**
     * Pre-computed bit masks for Hamming distance 1 (single-bit flips).
     * Fixed size: 20 ints = 80 bytes.
     */
    private static final int[] SINGLE_BIT_MASKS = new int[VALUE_BITS];

    /**
     * Pre-computed bit masks for Hamming distance 2 (double-bit flips).
     * Fixed size: 20*19/2 = 190 ints = 760 bytes.
     */
    private static final int[] DOUBLE_BIT_MASKS = new int[VALUE_BITS * (VALUE_BITS - 1) / 2];

    /**
     * Pre-computed bit masks for Hamming distance 3 (triple-bit flips).
     * Fixed size: 20*19*18/6 = 1140 ints = 4560 bytes.
     */
    private static final int[] TRIPLE_BIT_MASKS = new int[VALUE_BITS * (VALUE_BITS - 1) * (VALUE_BITS - 2) / 6];

    static {
        // Initialize single-bit masks
        for (int i = 0; i < VALUE_BITS; i++) {
            SINGLE_BIT_MASKS[i] = 1 << i;
        }

        // Initialize double-bit masks
        int idx = 0;
        for (int i = 0; i < VALUE_BITS; i++) {
            for (int j = i + 1; j < VALUE_BITS; j++) {
                DOUBLE_BIT_MASKS[idx++] = (1 << i) | (1 << j);
            }
        }

        // Initialize triple-bit masks
        idx = 0;
        for (int i = 0; i < VALUE_BITS; i++) {
            for (int j = i + 1; j < VALUE_BITS; j++) {
                for (int k = j + 1; k < VALUE_BITS; k++) {
                    TRIPLE_BIT_MASKS[idx++] = (1 << i) | (1 << j) | (1 << k);
                }
            }
        }
    }

    private final int tolerance;
    private final int foreignPenalty;
    private final int hammingWeight;
    private final int selectionSpread;

    /**
     * Random provider for stochastic label selection.
     * Must be set via {@link #setRandomProvider} before {@link #findTarget} is called
     * when {@code selectionSpread > 0}.
     */
    private IRandomProvider randomProvider;

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

    /** Default selection spread (0 = deterministic, matching legacy behavior). */
    public static final int DEFAULT_SELECTION_SPREAD = 0;

    /**
     * Internal scaling constant for integer weight calculation.
     * Weights are computed as {@code WEIGHT_PRECISION * selectionSpread / (distance + selectionSpread)}.
     */
    private static final int WEIGHT_PRECISION = 10000;

    /**
     * Creates a new pre-expanded Hamming strategy with default settings.
     */
    public PreExpandedHammingStrategy() {
        this(DEFAULT_TOLERANCE, DEFAULT_FOREIGN_PENALTY, DEFAULT_HAMMING_WEIGHT);
    }

    /**
     * Creates a new pre-expanded Hamming strategy from configuration.
     * <p>
     * Reads "tolerance", "foreignPenalty", "hammingWeight", and "selectionSpread" from the config,
     * using defaults if not specified.
     *
     * @param options The configuration options
     */
    public PreExpandedHammingStrategy(com.typesafe.config.Config options) {
        this(
            options.hasPath("tolerance") ? options.getInt("tolerance") : DEFAULT_TOLERANCE,
            options.hasPath("foreignPenalty") ? options.getInt("foreignPenalty") : DEFAULT_FOREIGN_PENALTY,
            options.hasPath("hammingWeight") ? options.getInt("hammingWeight") : DEFAULT_HAMMING_WEIGHT,
            options.hasPath("selectionSpread") ? options.getInt("selectionSpread") : DEFAULT_SELECTION_SPREAD
        );
    }

    /**
     * Creates a new pre-expanded Hamming strategy with specified settings and deterministic selection.
     *
     * @param tolerance The Hamming distance tolerance (2 = ~211 neighbors, 3 = ~1351 neighbors)
     * @param foreignPenalty The score penalty for foreign labels
     * @param hammingWeight The score weight per Hamming distance
     */
    public PreExpandedHammingStrategy(int tolerance, int foreignPenalty, int hammingWeight) {
        this(tolerance, foreignPenalty, hammingWeight, DEFAULT_SELECTION_SPREAD);
    }

    /**
     * Creates a new pre-expanded Hamming strategy with specified settings.
     *
     * @param tolerance The Hamming distance tolerance (2 = ~211 neighbors, 3 = ~1351 neighbors)
     * @param foreignPenalty The score penalty for foreign labels
     * @param hammingWeight The score weight per Hamming distance
     * @param selectionSpread The selection spread for stochastic label selection among own exact matches.
     *                        0 = deterministic (closest wins). {@literal >}0 = weighted-random selection where
     *                        the value is the half-weight distance (requires a random provider via
     *                        {@link #setRandomProvider}).
     */
    public PreExpandedHammingStrategy(int tolerance, int foreignPenalty, int hammingWeight, int selectionSpread) {
        this.tolerance = tolerance;
        this.foreignPenalty = foreignPenalty;
        this.hammingWeight = hammingWeight;
        this.selectionSpread = selectionSpread;
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
        // Own exact match always wins, so we can skip full scoring if one exists.
        // When selectionSpread > 0, uses weighted reservoir sampling among own exact matches
        // to enable "duplication + divergence": after gene duplication, both label copies
        // get a chance to be jumped to, weighted by inverse distance.
        if (selectionSpread > 0 && randomProvider == null) {
            throw new IllegalStateException(
                "selectionSpread > 0 requires a random provider to be set via setRandomProvider()");
        }

        int bestOwnExactDistance = Integer.MAX_VALUE;
        int bestOwnExactIndex = -1;
        int bestOwnExactOwner = Integer.MAX_VALUE;
        long totalWeight = 0;

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

                if (selectionSpread > 0) {
                    // Weighted reservoir sampling: weight = K * S / (d + S)
                    // Closer labels have higher weight but distant ones still get a chance.
                    long weight = (long) WEIGHT_PRECISION * selectionSpread / (distance + selectionSpread);
                    if (weight < 1) {
                        weight = 1;
                    }
                    totalWeight += weight;
                    if (randomProvider.nextInt((int) Math.min(totalWeight, Integer.MAX_VALUE)) < weight) {
                        bestOwnExactIndex = entry.flatIndex();
                    }
                } else {
                    // Deterministic: closest wins, tie-break by owner ID
                    if (distance < bestOwnExactDistance ||
                        (distance == bestOwnExactDistance && entry.owner() < bestOwnExactOwner)) {
                        bestOwnExactDistance = distance;
                        bestOwnExactIndex = entry.flatIndex();
                        bestOwnExactOwner = entry.owner();
                    }
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

        // Add to the value and all Hamming neighbors (no allocation)
        forEachNeighborValue(labelValue, tolerance, neighborValue ->
            valueToLabels.computeIfAbsent(neighborValue, k -> new ArrayList<>()).add(entry)
        );
    }

    @Override
    public void removeLabel(int labelValue, int flatIndex) {
        LabelEntryWithValue entryWithValue = indexToEntry.remove(flatIndex);
        if (entryWithValue == null) {
            return;
        }

        // Remove from the value and all Hamming neighbors (no allocation)
        forEachNeighborValue(labelValue, tolerance, neighborValue -> {
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

        // Update in all neighbor lists (no allocation)
        forEachNeighborValue(labelValue, tolerance, neighborValue -> {
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

        // Update in all neighbor lists (no allocation)
        forEachNeighborValue(labelValue, tolerance, neighborValue -> {
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
     * Gets the selection spread parameter for this strategy.
     * <p>
     * The selection spread controls stochastic label selection among own exact matches.
     * It represents the half-weight distance: the distance at which a label has 50% of
     * the maximum selection weight.
     *
     * @return The selection spread (0 = deterministic, {@literal >}0 = stochastic)
     */
    public int getSelectionSpread() {
        return selectionSpread;
    }

    @Override
    public void setRandomProvider(IRandomProvider randomProvider) {
        this.randomProvider = randomProvider;
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
     * Iterates over a value and all its Hamming neighbors within the given tolerance.
     * <p>
     * Uses pre-computed bit masks to avoid any heap allocation.
     * For tolerance=2, this calls the consumer 211 times (1 + 20 + 190).
     *
     * @param value The center value
     * @param tolerance Maximum Hamming distance
     * @param consumer The consumer to call for each neighbor value (including the value itself)
     */
    private static void forEachNeighborValue(int value, int tolerance, IntConsumer consumer) {
        // The value itself
        consumer.accept(value);

        // Distance 1: apply single-bit masks
        for (int mask : SINGLE_BIT_MASKS) {
            consumer.accept(value ^ mask);
        }

        // Distance 2: apply double-bit masks
        if (tolerance >= 2) {
            for (int mask : DOUBLE_BIT_MASKS) {
                consumer.accept(value ^ mask);
            }
        }

        // Distance 3: apply triple-bit masks
        if (tolerance >= 3) {
            for (int mask : TRIPLE_BIT_MASKS) {
                consumer.accept(value ^ mask);
            }
        }
    }
}
