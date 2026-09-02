package org.evochora.runtime.label;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.evochora.runtime.Config;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.OrganismRandom;

import java.util.ArrayList;
import java.util.BitSet;
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
 * independent of tolerance. Insert, remove and update are linear in the number of entries that
 * share one label value (insert locates its position by binary search, then shifts the tail);
 * with per-organism label namespaces that number is typically one.
 * <p>
 * Thread Safety: {@link #findTarget} only reads and is called concurrently from every thread of
 * the parallel wave; {@link #addLabel}, {@link #removeLabel} and {@link #updateOwner} are called
 * only from the simulation thread outside the wave.
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
     * Maps label value to its entries. Each label is stored only under its exact value.
     * Query-expansion at search time checks neighbor values via Hamming distance iteration.
     */
    private final Int2ObjectOpenHashMap<List<LabelEntry>> valueToLabels;

    /**
     * One bit per label value currently present in the index (2^20 bits = 128 KB). The
     * Hamming stages test a neighbor value here before touching the map, so the hundreds
     * of probes for unoccupied neighbor values cost one bit read instead of a hash lookup.
     * A bit is set with the first entry of its value and cleared when the value's last
     * entry is removed. Like {@link #valueToLabels}, it is mutated only from the
     * simulation thread outside the parallel wave and read concurrently inside it.
     */
    private final BitSet occupiedValues = new BitSet(1 << VALUE_BITS);

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
     *                        the value is the half-weight distance, drawn from the calling
     *                        organism's random source.
     */
    public PreExpandedHammingStrategy(int tolerance, int foreignPenalty, int hammingWeight, int selectionSpread) {
        this.tolerance = tolerance;
        this.foreignPenalty = foreignPenalty;
        this.hammingWeight = hammingWeight;
        this.selectionSpread = selectionSpread;
        this.valueToLabels = new Int2ObjectOpenHashMap<>();
    }

    /**
     * Finds the best matching label in staged Hamming-distance order (exact, then one, two, three
     * bit flips), stopping early when no later stage can beat the current best score. Among own
     * exact matches the closest wins when {@code selectionSpread} is zero; otherwise one of them
     * is chosen at random, weighted by inverse distance, with values drawn from {@code random}.
     *
     * @param searchValue the label value to search for
     * @param codeOwner the owner ID of the executing code; its own labels are preferred
     * @param callerCoords the coordinates of the calling instruction, for distance calculation
     * @param environment the environment, for coordinate conversion and toroidal distance
     * @param random the random source of the organism executing the lookup; must not be null
     * @return the flat index of the best matching label, or -1 if no label is within tolerance
     */
    @Override
    public int findTarget(int searchValue, int codeOwner, int[] callerCoords, Environment environment,
                          OrganismRandom random) {
        EnvironmentProperties props = environment.properties;

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
                int distance = toroidalManhattanDistanceToFlat(callerCoords, entry.flatIndex(), props);

                if (!entry.isForeign(codeOwner)) {
                    if (selectionSpread > 0) {
                        long weight = (long) WEIGHT_PRECISION * selectionSpread / (distance + selectionSpread);
                        if (weight < 1) weight = 1;
                        totalWeight += weight;
                        if (random.nextLong(totalWeight) < weight) {
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
                int neighborValue = searchValue ^ mask;
                if (!occupiedValues.get(neighborValue)) {
                    continue;
                }
                List<LabelEntry> bucket = valueToLabels.get(neighborValue);
                if (bucket != null) {
                    for (int i = 0; i < bucket.size(); i++) {
                        LabelEntry entry = bucket.get(i);
                        int distance = toroidalManhattanDistanceToFlat(callerCoords, entry.flatIndex(), props);
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
                int neighborValue = searchValue ^ mask;
                if (!occupiedValues.get(neighborValue)) {
                    continue;
                }
                List<LabelEntry> bucket = valueToLabels.get(neighborValue);
                if (bucket != null) {
                    for (int i = 0; i < bucket.size(); i++) {
                        LabelEntry entry = bucket.get(i);
                        int distance = toroidalManhattanDistanceToFlat(callerCoords, entry.flatIndex(), props);
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
                int neighborValue = searchValue ^ mask;
                if (!occupiedValues.get(neighborValue)) {
                    continue;
                }
                List<LabelEntry> bucket = valueToLabels.get(neighborValue);
                if (bucket != null) {
                    for (int i = 0; i < bucket.size(); i++) {
                        LabelEntry entry = bucket.get(i);
                        int distance = toroidalManhattanDistanceToFlat(callerCoords, entry.flatIndex(), props);
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
    /**
     * Toroidal Manhattan distance between the caller's coordinates and a label's flat
     * index. The label's coordinate is decoded dimension-wise from the index and the
     * world's strides without materializing a coordinate array, and each per-dimension
     * difference wraps around the world, taking the shorter way around the torus.
     */
    private static int toroidalManhattanDistanceToFlat(int[] caller, int flatIndex, EnvironmentProperties props) {
        int distance = 0;
        int remaining = flatIndex;
        for (int i = 0; i < caller.length; i++) {
            int stride = props.getStride(i);
            int labelCoord = remaining / stride;
            remaining -= labelCoord * stride;
            int diff = Math.abs(caller[i] - labelCoord);
            distance += Math.min(diff, props.getDimensionSize(i) - diff);
        }
        return distance;
    }

    @Override
    public void addLabel(int labelValue, LabelEntry entry) {
        // Entries of one value are kept ordered by canonical index, so that candidate order — and
        // with it the stochastic selection — depends on the labels' coordinates alone: never on the
        // order in which labels were placed (a resumed run rebuilds the index from a snapshot) and
        // never on how the grid is laid out in memory.
        List<LabelEntry> entries = valueToLabels.computeIfAbsent(labelValue, k -> new ArrayList<>());
        int low = 0;
        int high = entries.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (entries.get(mid).canonicalIndex() < entry.canonicalIndex()) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        entries.add(low, entry);
        occupiedValues.set(labelValue);
    }

    @Override
    public void removeLabel(int labelValue, int flatIndex) {
        List<LabelEntry> list = valueToLabels.get(labelValue);
        if (list != null) {
            list.removeIf(e -> e.flatIndex() == flatIndex);
            if (list.isEmpty()) {
                valueToLabels.remove(labelValue);
                occupiedValues.clear(labelValue);
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
                    list.set(i, new LabelEntry(flatIndex, old.canonicalIndex(), newOwner, old.marker()));
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
                    list.set(i, new LabelEntry(flatIndex, old.canonicalIndex(), old.owner(), newMarker));
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
