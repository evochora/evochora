package org.evochora.runtime.label;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.evochora.runtime.Config;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.OrganismRandom;
import org.evochora.runtime.spi.ILabelMatchingStrategy;
import org.evochora.runtime.spi.IRandomProvider;

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
 * Every label is held for both kinds of lookup. Labels in unowned cells have no owner to look them
 * up as its own and are held for the foreign search only.
 * <ul>
 *   <li><b>Own labels:</b> {@link OwnLabelTable} answers in one probe which label of the organism
 *       carries exactly the searched value — the case of almost every jump, whose cost depends
 *       neither on the number of labels the organism owns nor on the population. Only when the
 *       organism has no label or several labels with that exact value, its labels are walked: they
 *       are kept per owner, ordered by flat index with their values, and the walk XORs and counts
 *       bits per entry; its cost grows with the number of labels the organism owns and does not
 *       depend on {@code tolerance}.</li>
 *   <li><b>Foreign labels:</b> {@link TiledLabelIndex} holds the labels per value — those of a
 *       value with few labels together, those of a value with many labels by tile of the world —
 *       and one bit per value in use. A stage probes the values at its Hamming distance —
 *       1, then every single-bit, double-bit and triple-bit neighbour — against the bit set, and
 *       searches the labels of every value in use outwards from the caller, within the radius the
 *       stage leaves, {@code foreignReach − foreignReachDeductionPerBit × stage}. Under stable
 *       addresses one value is carried by every organism with that gene; the tiles keep the search
 *       to the labels nearby.</li>
 * </ul>
 * An addition, a removal or an owner change costs one probe of the table of own labels, a binary
 * search and an array shift in the owner's list, and one probe of the index of all labels.
 * <p>
 * Thread Safety: {@link #findTarget} and {@link #valuesMatch} only read and are called concurrently
 * from every thread of the parallel wave; {@link #addLabel}, {@link #removeLabel},
 * {@link #changeOwner} and {@link #birthMask} are called only from the simulation thread outside
 * the wave, {@link #initialize} before any of them.
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

    // Figures of the memory estimate
    /** Upper bound of the bytes one label costs: 80 as an own label, 96 in the index of all labels. */
    private static final long BYTES_PER_LABEL = 80 + 96;
    /** Bytes of the two bit sets over the value space. */
    private static final long FIXED_BYTES = 2 * (1L << Config.VALUE_BITS) / 8;

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

    /** Per owner other than 0: its labels by flat index, the payload being the label value. */
    private final Int2ObjectOpenHashMap<LabelList> labelsByOwner = new Int2ObjectOpenHashMap<>();

    /** Per owner other than 0 and label value: the owner's only label with it, or that there are several. */
    private final OwnLabelTable ownLabels = new OwnLabelTable();

    /** All labels by value and place; created when the world's shape is known. */
    private TiledLabelIndex labels;

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
    public int findTarget(int searchValue, int codeOwner, int[] callerCoords, OrganismRandom random) {
        int onlyExact = ownLabels.find(codeOwner, searchValue);
        if (onlyExact >= 0) {
            return onlyExact;
        }
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
                return chooseAmongOwn(own, firstPosition, searchValue, bestStage, codeOwner, callerCoords, random);
            }
        }
        return lastForeignStage < 0 ? -1 : findForeign(searchValue, codeOwner, callerCoords);
    }

    /**
     * Chooses among several own labels on one stage: by lottery, or the nearest.
     */
    private int chooseAmongOwn(LabelList own, int firstPosition, int searchValue, int stage, int codeOwner,
                               int[] callerCoords, OrganismRandom random) {
        CoordinateDecoder coordinates = labels.coordinates();
        boolean preferLowIndex = prefersLowIndex(codeOwner);
        int chosen = -1;
        int chosenDistance = Integer.MAX_VALUE;
        long totalWeight = 0;
        for (int i = firstPosition, n = own.size(); i < n; i++) {
            if (Integer.bitCount(own.payloadAt(i) ^ searchValue) != stage) {
                continue;
            }
            int flatIndex = own.flatIndexAt(i);
            int distance = coordinates.distance(callerCoords, flatIndex);
            if (selectionSpread > 0) {
                long weight = Math.max(1, (long) WEIGHT_PRECISION * selectionSpread / ((long) distance + selectionSpread));
                totalWeight += weight;
                if (random.nextLong(totalWeight) < weight) {
                    chosen = flatIndex;
                }
            } else if (TiledLabelIndex.isNearer(distance, flatIndex, chosenDistance, chosen, preferLowIndex)) {
                chosenDistance = distance;
                chosen = flatIndex;
            }
        }
        return chosen;
    }

    /**
     * Finds the nearest reachable foreign label on the best stage that holds one.
     */
    private int findForeign(int searchValue, int codeOwner, int[] callerCoords) {
        if (labels == null) {
            return -1;
        }
        boolean preferLowIndex = prefersLowIndex(codeOwner);
        for (int stage = 0; stage <= lastForeignStage; stage++) {
            int radius = foreignReach - foreignReachDeductionPerBit * stage;
            long search = TiledLabelIndex.searchState(Integer.MAX_VALUE, -1);
            for (int mask : NEIGHBOUR_MASKS[stage]) {
                int value = searchValue ^ mask;
                if (labels.isInUse(value)) {
                    search = labels.nearest(value, codeOwner, callerCoords, radius, preferLowIndex, search);
                }
            }
            if (TiledLabelIndex.foundFlatIndex(search) >= 0) {
                return TiledLabelIndex.foundFlatIndex(search);
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

    @Override
    public boolean valuesMatch(int searchValue, int labelValue) {
        return Integer.bitCount((searchValue ^ labelValue) & Config.VALUE_MASK) <= tolerance;
    }

    // ==================== Index maintenance ====================

    /**
     * {@inheritDoc}
     * <p>
     * Creates the index of all labels for the world's shape. A strategy that already holds labels
     * belongs to a world and cannot be given another.
     *
     * @throws IllegalStateException if the strategy already holds labels
     */
    @Override
    public void initialize(EnvironmentProperties properties) {
        if (labels != null && !labels.isEmpty()) {
            throw new IllegalStateException("The label index already holds labels of another world");
        }
        labels = new TiledLabelIndex(properties);
    }

    /** The index of all labels; it exists once the world's shape is known. */
    private TiledLabelIndex allLabels() {
        if (labels == null) {
            throw new IllegalStateException("A label was reported before initialize() told the world's shape");
        }
        return labels;
    }

    @Override
    public void addLabel(int labelValue, int flatIndex, int owner) {
        allLabels().put(labelValue, flatIndex, owner);
        if (owner != 0) {
            addOwn(labelValue, flatIndex, owner);
        }
    }

    @Override
    public void removeLabel(int labelValue, int flatIndex, int owner) {
        allLabels().remove(labelValue, flatIndex);
        if (owner != 0) {
            removeOwn(labelValue, flatIndex, owner);
        }
    }

    @Override
    public void changeOwner(int labelValue, int flatIndex, int oldOwner, int newOwner) {
        if (!allLabels().setOwner(labelValue, flatIndex, newOwner)) {
            return;
        }
        if (oldOwner != 0) {
            removeOwn(labelValue, flatIndex, oldOwner);
        }
        if (newOwner != 0) {
            addOwn(labelValue, flatIndex, newOwner);
        }
    }

    private void addOwn(int labelValue, int flatIndex, int owner) {
        LabelList ofOwner = labelsByOwner.computeIfAbsent(owner, k -> new LabelList());
        if (ofOwner.positionOf(flatIndex) < 0) {
            ofOwner.put(flatIndex, labelValue);
            ownLabels.add(owner, labelValue, flatIndex);
        }
    }

    private void removeOwn(int labelValue, int flatIndex, int owner) {
        LabelList ofOwner = labelsByOwner.get(owner);
        if (ofOwner != null && ofOwner.remove(flatIndex)) {
            ownLabels.remove(owner, labelValue, ofOwner);
            if (ofOwner.size() == 0) {
                labelsByOwner.remove(owner);
            }
        }
    }

    /**
     * Tells under which owner a label is indexed; for tests and diagnosis.
     *
     * @param labelValue The label's value
     * @param flatIndex The flat index of the cell holding the label
     * @return The owner the label is indexed under, or -1 if the index holds no such label
     */
    public int ownerOf(int labelValue, int flatIndex) {
        return labels == null ? -1 : labels.ownerOf(labelValue, flatIndex);
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

    // ==================== Memory estimate ====================

    @Override
    public long estimateMemoryBytes(long labels) {
        return Math.addExact(Math.multiplyExact(labels, BYTES_PER_LABEL), FIXED_BYTES);
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
