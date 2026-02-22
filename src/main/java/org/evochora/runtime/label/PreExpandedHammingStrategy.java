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
 * Query-expansion Hamming distance matching strategy for fuzzy label lookup.
 * <p>
 * Each label is stored only under its exact value. At search time, the query is expanded
 * to check Hamming neighbors in stages with increasing distance, enabling early exit
 * and pruning:
 * <ul>
 *   <li>Stage 0: exact value (1 lookup)</li>
 *   <li>Stage 1: single-bit flips, hamming=1 (20 lookups)</li>
 *   <li>Stage 2: double-bit flips, hamming=2 (190 lookups)</li>
 *   <li>Stage 3: triple-bit flips, hamming=3 (1140 lookups)</li>
 * </ul>
 * <p>
 * Pruning skips stage K entirely when the current best score is already below
 * {@code K * hammingWeight}, since no candidate at that distance can improve the result.
 * <p>
 * Memory usage is proportional to the number of labels (one entry per label),
 * independent of tolerance. Insert, remove, and update operations are O(1).
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
     * Maps label value to its entries. Each label is stored only under its exact value.
     * Query-expansion at search time checks neighbor values via Hamming distance iteration.
     */
    private final Int2ObjectOpenHashMap<List<LabelEntry>> valueToLabels;

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
     * Creates a new Hamming strategy with default settings.
     */
    public PreExpandedHammingStrategy() {
        this(DEFAULT_TOLERANCE, DEFAULT_FOREIGN_PENALTY, DEFAULT_HAMMING_WEIGHT);
    }

    /**
     * Creates a new Hamming strategy from configuration.
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
     * Creates a new Hamming strategy with specified settings and deterministic selection.
     *
     * @param tolerance The Hamming distance tolerance (2 = ~211 neighbors, 3 = ~1351 neighbors)
     * @param foreignPenalty The score penalty for foreign labels
     * @param hammingWeight The score weight per Hamming distance
     */
    public PreExpandedHammingStrategy(int tolerance, int foreignPenalty, int hammingWeight) {
        this(tolerance, foreignPenalty, hammingWeight, DEFAULT_SELECTION_SPREAD);
    }

    /**
     * Creates a new Hamming strategy with specified settings.
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
    }

    @Override
    public int findTarget(int searchValue, int codeOwner, int[] callerCoords, Environment environment) {
        int[] shape = environment.getShape();

        if (selectionSpread > 0 && randomProvider == null) {
            throw new IllegalStateException(
                "selectionSpread > 0 requires a random provider to be set via setRandomProvider()");
        }

        int bestScore = Integer.MAX_VALUE;
        int bestFlatIndex = -1;
        int bestOwner = Integer.MAX_VALUE;

        // === Stage 0: Exact match (hamming = 0) ===
        List<LabelEntry> exactList = valueToLabels.get(searchValue);
        if (exactList != null) {
            // Own exact match always wins — check first with early exit.
            // When selectionSpread > 0, uses weighted reservoir sampling among own exact matches
            // to enable "duplication + divergence": after gene duplication, both label copies
            // get a chance to be jumped to, weighted by inverse distance.
            int bestOwnExactIndex = -1;
            int bestOwnExactDistance = Integer.MAX_VALUE;
            int bestOwnExactOwner = Integer.MAX_VALUE;
            long totalWeight = 0;

            for (int i = 0; i < exactList.size(); i++) {
                LabelEntry entry = exactList.get(i);
                int[] labelCoords = environment.getCoordinateFromIndex(entry.flatIndex());
                int distance = toroidalManhattanDistance(callerCoords, labelCoords, shape);

                if (!entry.isForeign(codeOwner)) {
                    if (selectionSpread > 0) {
                        long weight = (long) WEIGHT_PRECISION * selectionSpread / (distance + selectionSpread);
                        if (weight < 1) weight = 1;
                        totalWeight += weight;
                        if (randomProvider.nextInt((int) Math.min(totalWeight, Integer.MAX_VALUE)) < weight) {
                            bestOwnExactIndex = entry.flatIndex();
                        }
                    } else {
                        if (distance < bestOwnExactDistance ||
                            (distance == bestOwnExactDistance && entry.owner() < bestOwnExactOwner)) {
                            bestOwnExactDistance = distance;
                            bestOwnExactIndex = entry.flatIndex();
                            bestOwnExactOwner = entry.owner();
                        }
                    }
                }

                // Score all exact entries (own and foreign) for the general best tracking
                int score = distance + (entry.isForeign(codeOwner) ? foreignPenalty : 0);
                if (score < bestScore || (score == bestScore && entry.owner() < bestOwner)) {
                    bestScore = score;
                    bestFlatIndex = entry.flatIndex();
                    bestOwner = entry.owner();
                }
            }

            if (bestOwnExactIndex != -1) {
                return bestOwnExactIndex;
            }
        }

        // === Stage 1: Hamming distance 1 (single-bit flips, 20 lookups) ===
        if (tolerance >= 1 && bestScore >= hammingWeight) {
            int stageBaseScore = hammingWeight;
            for (int mask : SINGLE_BIT_MASKS) {
                List<LabelEntry> bucket = valueToLabels.get(searchValue ^ mask);
                if (bucket != null) {
                    for (int i = 0; i < bucket.size(); i++) {
                        LabelEntry entry = bucket.get(i);
                        int[] labelCoords = environment.getCoordinateFromIndex(entry.flatIndex());
                        int distance = toroidalManhattanDistance(callerCoords, labelCoords, shape);
                        int score = stageBaseScore + distance + (entry.isForeign(codeOwner) ? foreignPenalty : 0);
                        if (score < bestScore || (score == bestScore && entry.owner() < bestOwner)) {
                            bestScore = score;
                            bestFlatIndex = entry.flatIndex();
                            bestOwner = entry.owner();
                        }
                    }
                }
            }
        }

        // === Stage 2: Hamming distance 2 (double-bit flips, 190 lookups) ===
        if (tolerance >= 2 && bestScore >= 2 * hammingWeight) {
            int stageBaseScore = 2 * hammingWeight;
            for (int mask : DOUBLE_BIT_MASKS) {
                List<LabelEntry> bucket = valueToLabels.get(searchValue ^ mask);
                if (bucket != null) {
                    for (int i = 0; i < bucket.size(); i++) {
                        LabelEntry entry = bucket.get(i);
                        int[] labelCoords = environment.getCoordinateFromIndex(entry.flatIndex());
                        int distance = toroidalManhattanDistance(callerCoords, labelCoords, shape);
                        int score = stageBaseScore + distance + (entry.isForeign(codeOwner) ? foreignPenalty : 0);
                        if (score < bestScore || (score == bestScore && entry.owner() < bestOwner)) {
                            bestScore = score;
                            bestFlatIndex = entry.flatIndex();
                            bestOwner = entry.owner();
                        }
                    }
                }
            }
        }

        // === Stage 3: Hamming distance 3 (triple-bit flips, 1140 lookups) ===
        if (tolerance >= 3 && bestScore >= 3 * hammingWeight) {
            int stageBaseScore = 3 * hammingWeight;
            for (int mask : TRIPLE_BIT_MASKS) {
                List<LabelEntry> bucket = valueToLabels.get(searchValue ^ mask);
                if (bucket != null) {
                    for (int i = 0; i < bucket.size(); i++) {
                        LabelEntry entry = bucket.get(i);
                        int[] labelCoords = environment.getCoordinateFromIndex(entry.flatIndex());
                        int distance = toroidalManhattanDistance(callerCoords, labelCoords, shape);
                        int score = stageBaseScore + distance + (entry.isForeign(codeOwner) ? foreignPenalty : 0);
                        if (score < bestScore || (score == bestScore && entry.owner() < bestOwner)) {
                            bestScore = score;
                            bestFlatIndex = entry.flatIndex();
                            bestOwner = entry.owner();
                        }
                    }
                }
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
        valueToLabels.computeIfAbsent(labelValue, k -> new ArrayList<>()).add(entry);
    }

    @Override
    public void removeLabel(int labelValue, int flatIndex) {
        List<LabelEntry> list = valueToLabels.get(labelValue);
        if (list != null) {
            list.removeIf(e -> e.flatIndex() == flatIndex);
            if (list.isEmpty()) {
                valueToLabels.remove(labelValue);
            }
        }
    }

    @Override
    public void updateOwner(int labelValue, int flatIndex, int newOwner) {
        List<LabelEntry> list = valueToLabels.get(labelValue);
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).flatIndex() == flatIndex) {
                    LabelEntry old = list.get(i);
                    list.set(i, new LabelEntry(flatIndex, newOwner, old.marker()));
                    return;
                }
            }
        }
    }

    @Override
    public void updateMarker(int labelValue, int flatIndex, int newMarker) {
        List<LabelEntry> list = valueToLabels.get(labelValue);
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).flatIndex() == flatIndex) {
                    LabelEntry old = list.get(i);
                    list.set(i, new LabelEntry(flatIndex, old.owner(), newMarker));
                    return;
                }
            }
        }
    }

    @Override
    public Collection<LabelEntry> getCandidates(int searchValue) {
        List<LabelEntry> result = new ArrayList<>();
        forEachNeighborValue(searchValue, tolerance, neighborValue -> {
            List<LabelEntry> list = valueToLabels.get(neighborValue);
            if (list != null) {
                result.addAll(list);
            }
        });
        return Collections.unmodifiableList(result);
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
     * Iterates over a value and all its Hamming neighbors within the given tolerance.
     * <p>
     * Used for query-expansion in {@link #getCandidates}. The main {@link #findTarget}
     * method uses inline staged iteration with pruning instead.
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
