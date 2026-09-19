package org.evochora.runtime.label;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.evochora.runtime.Config;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.OrganismRandom;
import org.evochora.runtime.spi.ILabelMatchingStrategy;
import org.evochora.runtime.spi.IRandomProvider;

import java.util.BitSet;
import java.util.Set;

/**
 * Label addressing by Hamming distance: own labels first, foreign labels within a reach, label
 * values stable by descent with an optional namespace flip at birth.
 *
 * <h2>What a lookup does</h2>
 * A jump carries a label value; a label matches it at a Hamming distance of at most
 * {@code tolerance} bits. Candidates are ranked in <em>stages</em> of Hamming distance, 0 first.
 * Only the best stage that holds a candidate is considered — stages are never mixed, so a label one
 * bit away never competes with an exact one, however near it stands.
 * <ol>
 *   <li><b>Own labels.</b> The labels in cells the executing organism owns are examined first. If
 *       any lies within {@code tolerance}, the target is one of the own labels on the best stage,
 *       and no foreign label is looked at, however near it stands:
 *       <ul>
 *         <li>one label on that stage: it is the target. No distance is computed and no random
 *             number is drawn — this is the case of almost every jump;</li>
 *         <li>several labels on that stage (duplicates of a label): with {@code selectionSpread}
 *             greater than 0 one of them is drawn by lottery from the organism's random source,
 *             each weighted by {@code selectionSpread / (distance + selectionSpread)}, so that a
 *             label at a distance of {@code selectionSpread} cells has half the weight of one at
 *             distance 0; one random number is drawn per candidate, in the order of their flat
 *             indexes. With {@code selectionSpread} 0 the nearest is the target.</li>
 *       </ul>
 *       Because the stage counts and not the exactness, a reference that mutates by one bit keeps
 *       the distribution among duplicates it had before: both duplicates move to stage 1 together.
 *       A label that mutates by one bit leaves the stage of its duplicate and is no target of the
 *       old references until one of them follows.</li>
 *   <li><b>Foreign labels.</b> Only if no own label lies within {@code tolerance}, and only if
 *       {@code foreignReach} is not negative, the labels in cells of other organisms and in unowned
 *       cells are examined. A foreign label is <em>reachable</em> when
 *       {@code foreignReachDeductionPerBit × hammingDistance + distance ≤ foreignReach}: every
 *       differing bit deducts from the reach. Among the reachable labels the best stage is taken,
 *       and on it the nearest label is the target — deterministically, without a lottery, so that a
 *       reference keeps resolving to the same nearby label. Stages are examined up to the smallest
 *       of {@code tolerance}, 3, and {@code foreignReach / foreignReachDeductionPerBit}.</li>
 *   <li><b>No match.</b> The lookup returns -1 and the instruction fails.</li>
 * </ol>
 * <b>Distance</b> is the Manhattan distance from the calling position to the label. It wraps around
 * the world edge in a toroidal world and does not in a bounded one.
 * <p>
 * <b>Ties.</b> Candidates at equal distance are told apart by position: an organism with an even ID
 * takes the one with the lowest flat index, an organism with an odd ID the one with the highest.
 * Own and foreign candidates follow the same rule, and across a population it favours no direction.
 * <p>
 * Labels in a body under construction — written with a non-zero marker — never reach this class;
 * {@link LabelIndex} reports a label only once it is a jump target.
 *
 * <h2>What happens to label values at birth</h2>
 * A child carries the label values of its parent; only mutation changes them. Relatives therefore
 * share addresses, which is what lets a reference without an own match resolve to the homologous
 * label of a neighbour, and lets its children inherit that relation.
 * <p>
 * With probability {@code namespaceFlipRate} a newborn's labels and label references are all XORed
 * with one mask that has exactly one bit set, drawn uniformly from the uppermost
 * {@code namespaceBits} bits of the value field ({@link #birthMask}). Inside the organism nothing
 * changes. Between lineages the difference performs a random walk over those bit positions: it grows
 * with genealogical distance and saturates at half of them on average, each differing bit costing a
 * foreign reference {@code foreignReachDeductionPerBit} cells of reach. The lower bits are never
 * touched by a flip. With a rate of 0 no random number is drawn.
 *
 * <h2>Index</h2>
 * <ul>
 *   <li><b>Own labels:</b> per owner, its labels ordered by flat index with their values. An own
 *       lookup walks that list once, XORs and counts bits per entry; its cost grows with the number
 *       of labels the organism owns and does not depend on {@code tolerance}.</li>
 *   <li><b>Foreign labels:</b> per label value, the labels carrying it ordered by flat index with
 *       their owners, and one bit per value in use. A stage probes the values at its Hamming
 *       distance — 1, then every single-bit, double-bit and triple-bit neighbour — against the bit
 *       set. Under stable addresses one value is carried by every organism with that gene, so a
 *       list is as long as the population; the reach bounds what is scanned: the radius a stage
 *       leaves, {@code foreignReach − foreignReachDeductionPerBit × stage}, limits the first
 *       coordinate, and because that coordinate is the most significant part of the flat index the
 *       labels within the limit form one contiguous range of the list (two across the seam of a
 *       toroidal world), located by binary search and scanned in full.</li>
 * </ul>
 * Every label is held in both structures. Additions, removals and owner changes cost a binary
 * search and an array shift in each.
 * <p>
 * Thread Safety: {@link #findTarget} and {@link #valuesMatch} only read and are called concurrently
 * from every thread of the parallel wave; {@link #addLabel}, {@link #removeLabel},
 * {@link #changeOwner} and {@link #birthMask} are called only from the simulation thread outside
 * the wave.
 */
public class HammingLabelMatchingStrategy implements ILabelMatchingStrategy {

    /** Default Hamming distance tolerance. */
    public static final int DEFAULT_TOLERANCE = 2;

    /**
     * Default selection spread of a strategy built without configuration: 0, so that such a
     * strategy never draws a random number. The shipped configuration sets 50.
     */
    public static final int DEFAULT_SELECTION_SPREAD = 0;

    /** Default reach of a reference without an own match. */
    public static final int DEFAULT_FOREIGN_REACH = 250;

    /** Default deduction from the reach per differing bit. */
    public static final int DEFAULT_FOREIGN_REACH_DEDUCTION_PER_BIT = 50;

    /** Default probability per newborn of a namespace flip. */
    public static final double DEFAULT_NAMESPACE_FLIP_RATE = 0.05;

    /** Default number of uppermost value bits a namespace flip can hit. */
    public static final int DEFAULT_NAMESPACE_BITS = 8;

    /** The highest stage the foreign search examines: the neighbour masks reach this far. */
    private static final int MAX_FOREIGN_STAGE = 3;

    private static final Set<String> OPTION_KEYS = Set.of("tolerance", "selectionSpread",
            "foreignReach", "foreignReachDeductionPerBit", "namespaceFlipRate", "namespaceBits");

    private static final int VALUE_BITS = Config.VALUE_BITS;

    /** Scaling constant of the integer lottery weights. */
    private static final int WEIGHT_PRECISION = 10000;

    /** The XOR masks of every stage: {@code NEIGHBOUR_MASKS[h]} holds the masks with h bits set. */
    private static final int[][] NEIGHBOUR_MASKS = buildNeighbourMasks();

    private final int tolerance;
    private final int selectionSpread;
    private final int foreignReach;
    private final int foreignReachDeductionPerBit;
    private final double namespaceFlipRate;
    private final int namespaceBits;

    /** The highest stage the foreign search examines under this configuration; -1 for none. */
    private final int lastForeignStage;

    /** Per owner: its labels by flat index, the payload being the label value. */
    private final Int2ObjectOpenHashMap<LabelList> labelsByOwner = new Int2ObjectOpenHashMap<>();

    /** Per label value: the labels carrying it by flat index, the payload being the owner. */
    private final Int2ObjectOpenHashMap<LabelList> labelsByValue = new Int2ObjectOpenHashMap<>();

    /**
     * One bit per label value in use, so that a probe for a neighbour value nobody carries costs a
     * bit read instead of a hash lookup.
     */
    private final BitSet occupiedValues = new BitSet(1 << VALUE_BITS);

    /**
     * Creates a strategy with the default settings.
     */
    public HammingLabelMatchingStrategy() {
        this(DEFAULT_TOLERANCE, DEFAULT_SELECTION_SPREAD, DEFAULT_FOREIGN_REACH,
                DEFAULT_FOREIGN_REACH_DEDUCTION_PER_BIT, DEFAULT_NAMESPACE_FLIP_RATE, DEFAULT_NAMESPACE_BITS);
    }

    /**
     * Creates a strategy from its configuration block. Every option is optional; a key the strategy
     * does not know is rejected, so that a setting never goes unnoticed.
     *
     * @param options The {@code options} of the {@code label-matching} block
     * @throws IllegalArgumentException if an option is unknown or outside its range
     */
    public HammingLabelMatchingStrategy(com.typesafe.config.Config options) {
        this(
            intOption(requireKnownKeys(options), "tolerance", DEFAULT_TOLERANCE),
            intOption(options, "selectionSpread", DEFAULT_SELECTION_SPREAD),
            intOption(options, "foreignReach", DEFAULT_FOREIGN_REACH),
            intOption(options, "foreignReachDeductionPerBit", DEFAULT_FOREIGN_REACH_DEDUCTION_PER_BIT),
            options.hasPath("namespaceFlipRate") ? options.getDouble("namespaceFlipRate") : DEFAULT_NAMESPACE_FLIP_RATE,
            intOption(options, "namespaceBits", DEFAULT_NAMESPACE_BITS)
        );
    }

    /**
     * Creates a strategy with the given settings.
     *
     * @param tolerance Largest Hamming distance at which a label matches, 0 to the width of the
     *                  value field
     * @param selectionSpread Half-weight distance of the lottery among own labels on one stage;
     *                        0 takes the nearest
     * @param foreignReach Bound of {@code foreignReachDeductionPerBit × hammingDistance + distance}
     *                     for a foreign label; negative for no foreign label ever
     * @param foreignReachDeductionPerBit What each differing bit deducts from the reach; not negative
     * @param namespaceFlipRate Probability per newborn of a namespace flip, 0 to 1
     * @param namespaceBits Number of uppermost value bits a flip can hit, 1 to the width of the
     *                      value field
     * @throws IllegalArgumentException if a setting is outside its range
     */
    public HammingLabelMatchingStrategy(int tolerance, int selectionSpread, int foreignReach,
                                        int foreignReachDeductionPerBit, double namespaceFlipRate,
                                        int namespaceBits) {
        require(tolerance >= 0 && tolerance <= VALUE_BITS, "tolerance", tolerance, "0 to " + VALUE_BITS);
        require(selectionSpread >= 0, "selectionSpread", selectionSpread, "0 or more");
        require(foreignReachDeductionPerBit >= 0, "foreignReachDeductionPerBit", foreignReachDeductionPerBit, "0 or more");
        require(namespaceFlipRate >= 0.0 && namespaceFlipRate <= 1.0, "namespaceFlipRate", namespaceFlipRate, "0 to 1");
        require(namespaceBits >= 1 && namespaceBits <= VALUE_BITS, "namespaceBits", namespaceBits, "1 to " + VALUE_BITS);
        this.tolerance = tolerance;
        this.selectionSpread = selectionSpread;
        this.foreignReach = foreignReach;
        this.foreignReachDeductionPerBit = foreignReachDeductionPerBit;
        this.namespaceFlipRate = namespaceFlipRate;
        this.namespaceBits = namespaceBits;
        this.lastForeignStage = lastForeignStage(tolerance, foreignReach, foreignReachDeductionPerBit);
    }

    private static int lastForeignStage(int tolerance, int foreignReach, int deductionPerBit) {
        if (foreignReach < 0) {
            return -1;
        }
        int last = Math.min(tolerance, MAX_FOREIGN_STAGE);
        return deductionPerBit == 0 ? last : Math.min(last, foreignReach / deductionPerBit);
    }

    private static com.typesafe.config.Config requireKnownKeys(com.typesafe.config.Config options) {
        for (String key : options.root().keySet()) {
            if (!OPTION_KEYS.contains(key)) {
                throw new IllegalArgumentException("Unknown label-matching option '" + key + "'. Known options: "
                        + String.join(", ", OPTION_KEYS.stream().sorted().toList())
                        + ". 'foreignPenalty' and 'hammingWeight' are replaced by 'foreignReach' and"
                        + " 'foreignReachDeductionPerBit'.");
            }
        }
        return options;
    }

    private static int intOption(com.typesafe.config.Config options, String key, int defaultValue) {
        return options.hasPath(key) ? options.getInt(key) : defaultValue;
    }

    private static void require(boolean holds, String option, Object value, String range) {
        if (!holds) {
            throw new IllegalArgumentException("label-matching option '" + option + "' is " + value
                    + " but must be " + range);
        }
    }

    // ==================== Lookup ====================

    @Override
    public int findTarget(int searchValue, int codeOwner, int[] callerCoords, Environment environment,
                          OrganismRandom random) {
        LabelList own = labelsByOwner.get(codeOwner);
        if (own != null) {
            // One pass: the best stage, how many own labels stand on it, and the first of them
            int bestStage = tolerance + 1;
            int onBestStage = 0;
            int firstPosition = -1;
            for (int i = 0, n = own.size(); i < n; i++) {
                int stage = Integer.bitCount(own.payloadAt(i) ^ searchValue);
                if (stage < bestStage) {
                    bestStage = stage;
                    onBestStage = 1;
                    firstPosition = i;
                } else if (stage == bestStage && stage <= tolerance) {
                    onBestStage++;
                }
            }
            if (onBestStage == 1) {
                return own.flatIndexAt(firstPosition);
            }
            if (onBestStage > 1) {
                return chooseAmongOwn(own, firstPosition, searchValue, bestStage, codeOwner, callerCoords,
                        environment.properties, random);
            }
        }
        return lastForeignStage < 0 ? -1
                : findForeign(searchValue, codeOwner, callerCoords, environment.properties);
    }

    /**
     * Chooses among several own labels on one stage: by lottery, or the nearest.
     */
    private int chooseAmongOwn(LabelList own, int firstPosition, int searchValue, int stage, int codeOwner,
                               int[] callerCoords, EnvironmentProperties props, OrganismRandom random) {
        boolean preferLowIndex = prefersLowIndex(codeOwner);
        int chosen = -1;
        int chosenDistance = Integer.MAX_VALUE;
        long totalWeight = 0;
        for (int i = firstPosition, n = own.size(); i < n; i++) {
            if (Integer.bitCount(own.payloadAt(i) ^ searchValue) != stage) {
                continue;
            }
            int flatIndex = own.flatIndexAt(i);
            int distance = distance(callerCoords, flatIndex, props);
            if (selectionSpread > 0) {
                long weight = Math.max(1, (long) WEIGHT_PRECISION * selectionSpread / (distance + selectionSpread));
                totalWeight += weight;
                if (random.nextLong(totalWeight) < weight) {
                    chosen = flatIndex;
                }
            } else if (isBetter(distance, flatIndex, chosenDistance, chosen, preferLowIndex)) {
                chosenDistance = distance;
                chosen = flatIndex;
            }
        }
        return chosen;
    }

    /**
     * Finds the nearest reachable foreign label on the best stage that holds one.
     */
    private int findForeign(int searchValue, int codeOwner, int[] callerCoords, EnvironmentProperties props) {
        boolean preferLowIndex = prefersLowIndex(codeOwner);
        int size0 = props.getDimensionSize(0);
        int stride0 = props.getStride(0);
        for (int stage = 0; stage <= lastForeignStage; stage++) {
            int radius = foreignReach - foreignReachDeductionPerBit * stage;

            // The window of the first coordinate the radius leaves: one range, or two where it
            // crosses the seam of a toroidal world. A radius beyond the world covers all of it.
            int window = Math.min(radius, size0);
            int low = callerCoords[0] - window;
            int high = callerCoords[0] + window;
            int from = Math.max(low, 0);
            int to = Math.min(high, size0 - 1);
            int seamFrom = 0;
            int seamTo = -1;
            if (props.isToroidal()) {
                if (high - low + 1 >= size0) {
                    from = 0;
                    to = size0 - 1;
                } else if (low < 0) {
                    seamFrom = low + size0;
                    seamTo = size0 - 1;
                } else if (high >= size0) {
                    seamFrom = 0;
                    seamTo = high - size0;
                }
            }

            int best = -1;
            int bestDistance = Integer.MAX_VALUE;
            for (int mask : NEIGHBOUR_MASKS[stage]) {
                int value = searchValue ^ mask;
                if (!occupiedValues.get(value)) {
                    continue;
                }
                LabelList labels = labelsByValue.get(value);
                if (labels == null) {
                    continue;
                }
                for (int range = 0; range < 2; range++) {
                    int rangeFrom = range == 0 ? from : seamFrom;
                    int rangeTo = range == 0 ? to : seamTo;
                    if (rangeTo < rangeFrom) {
                        continue;
                    }
                    int end = labels.lowerBound((rangeTo + 1) * stride0);
                    for (int i = labels.lowerBound(rangeFrom * stride0); i < end; i++) {
                        if (labels.payloadAt(i) == codeOwner) {
                            continue;
                        }
                        int flatIndex = labels.flatIndexAt(i);
                        int distance = distance(callerCoords, flatIndex, props);
                        if (distance <= radius && isBetter(distance, flatIndex, bestDistance, best, preferLowIndex)) {
                            bestDistance = distance;
                            best = flatIndex;
                        }
                    }
                }
            }
            if (best >= 0) {
                return best;
            }
        }
        return -1;
    }

    /**
     * Whether an organism resolves a tie towards the lowest flat index; the others resolve it
     * towards the highest, so that ties favour no direction across a population.
     */
    private static boolean prefersLowIndex(int organismId) {
        return (organismId & 1) == 0;
    }

    private static boolean isBetter(int distance, int flatIndex, int bestDistance, int bestFlatIndex,
                                    boolean preferLowIndex) {
        if (distance != bestDistance) {
            return distance < bestDistance;
        }
        return preferLowIndex ? flatIndex < bestFlatIndex : flatIndex > bestFlatIndex;
    }

    /**
     * Manhattan distance between the caller's coordinates and the cell at a flat index. The cell's
     * coordinate is decoded dimension-wise from the index and the world's row-major strides without
     * materializing a coordinate array; in a toroidal world each per-dimension difference takes the
     * shorter way around, in a bounded world it does not wrap.
     */
    private static int distance(int[] caller, int flatIndex, EnvironmentProperties props) {
        boolean toroidal = props.isToroidal();
        int distance = 0;
        int remaining = flatIndex;
        for (int i = 0; i < caller.length; i++) {
            int stride = props.getStride(i);
            int labelCoord = remaining / stride;
            remaining -= labelCoord * stride;
            int diff = Math.abs(caller[i] - labelCoord);
            distance += toroidal ? Math.min(diff, props.getDimensionSize(i) - diff) : diff;
        }
        return distance;
    }

    @Override
    public boolean valuesMatch(int searchValue, int labelValue) {
        return Integer.bitCount((searchValue ^ labelValue) & Config.VALUE_MASK) <= tolerance;
    }

    // ==================== Index maintenance ====================

    @Override
    public void addLabel(int labelValue, int flatIndex, int owner) {
        labelsByOwner.computeIfAbsent(owner, k -> new LabelList()).put(flatIndex, labelValue);
        labelsByValue.computeIfAbsent(labelValue, k -> new LabelList()).put(flatIndex, owner);
        occupiedValues.set(labelValue);
    }

    @Override
    public void removeLabel(int labelValue, int flatIndex, int owner) {
        LabelList ofOwner = labelsByOwner.get(owner);
        if (ofOwner != null && ofOwner.remove(flatIndex) && ofOwner.size() == 0) {
            labelsByOwner.remove(owner);
        }
        LabelList ofValue = labelsByValue.get(labelValue);
        if (ofValue != null && ofValue.remove(flatIndex) && ofValue.size() == 0) {
            labelsByValue.remove(labelValue);
            occupiedValues.clear(labelValue);
        }
    }

    @Override
    public void changeOwner(int labelValue, int flatIndex, int oldOwner, int newOwner) {
        LabelList ofValue = labelsByValue.get(labelValue);
        if (ofValue == null || ofValue.positionOf(flatIndex) < 0) {
            return;
        }
        ofValue.put(flatIndex, newOwner);
        LabelList ofOldOwner = labelsByOwner.get(oldOwner);
        if (ofOldOwner != null && ofOldOwner.remove(flatIndex) && ofOldOwner.size() == 0) {
            labelsByOwner.remove(oldOwner);
        }
        labelsByOwner.computeIfAbsent(newOwner, k -> new LabelList()).put(flatIndex, labelValue);
    }

    /**
     * Tells under which owner a label is indexed; for tests and diagnosis.
     *
     * @param labelValue The label's value
     * @param flatIndex The flat index of the cell holding the label
     * @return The owner the label is indexed under, or -1 if the index holds no such label
     */
    public int ownerOf(int labelValue, int flatIndex) {
        LabelList ofValue = labelsByValue.get(labelValue);
        int position = ofValue == null ? -1 : ofValue.positionOf(flatIndex);
        return position < 0 ? -1 : ofValue.payloadAt(position);
    }

    // ==================== Birth ====================

    /**
     * {@inheritDoc}
     * <p>
     * With probability {@code namespaceFlipRate} the mask has exactly one bit set, drawn uniformly
     * from the uppermost {@code namespaceBits} bits of the value field; otherwise it is 0. A rate
     * of 0 draws no random number, so such a run consumes the root provider exactly as if the
     * strategy had no birth mask at all.
     */
    @Override
    public int birthMask(IRandomProvider randomProvider) {
        if (namespaceFlipRate <= 0.0 || randomProvider.nextDouble() >= namespaceFlipRate) {
            return 0;
        }
        return 1 << (VALUE_BITS - 1 - randomProvider.nextInt(namespaceBits));
    }

    // ==================== Settings ====================

    /**
     * Gets the tolerance.
     *
     * @return The largest Hamming distance at which a label matches
     */
    public int getTolerance() {
        return tolerance;
    }

    /**
     * Gets the selection spread.
     *
     * @return The half-weight distance of the lottery among own labels; 0 takes the nearest
     */
    public int getSelectionSpread() {
        return selectionSpread;
    }

    /**
     * Gets the foreign reach.
     *
     * @return The reach of a reference without an own match; negative for none
     */
    public int getForeignReach() {
        return foreignReach;
    }

    /**
     * Gets the deduction per differing bit.
     *
     * @return What each differing bit deducts from the reach
     */
    public int getForeignReachDeductionPerBit() {
        return foreignReachDeductionPerBit;
    }

    /**
     * Gets the namespace flip rate.
     *
     * @return The probability per newborn of a namespace flip
     */
    public double getNamespaceFlipRate() {
        return namespaceFlipRate;
    }

    /**
     * Gets the number of namespace bits.
     *
     * @return The number of uppermost value bits a namespace flip can hit
     */
    public int getNamespaceBits() {
        return namespaceBits;
    }

    /**
     * Builds the XOR masks with 0, 1, 2 and 3 bits set over the value field.
     */
    private static int[][] buildNeighbourMasks() {
        int[][] masks = new int[MAX_FOREIGN_STAGE + 1][];
        masks[0] = new int[]{0};
        masks[1] = new int[VALUE_BITS];
        masks[2] = new int[VALUE_BITS * (VALUE_BITS - 1) / 2];
        masks[3] = new int[VALUE_BITS * (VALUE_BITS - 1) * (VALUE_BITS - 2) / 6];
        int single = 0;
        int twofold = 0;
        int threefold = 0;
        for (int i = 0; i < VALUE_BITS; i++) {
            masks[1][single++] = 1 << i;
            for (int j = i + 1; j < VALUE_BITS; j++) {
                masks[2][twofold++] = (1 << i) | (1 << j);
                for (int k = j + 1; k < VALUE_BITS; k++) {
                    masks[3][threefold++] = (1 << i) | (1 << j) | (1 << k);
                }
            }
        }
        return masks;
    }
}
