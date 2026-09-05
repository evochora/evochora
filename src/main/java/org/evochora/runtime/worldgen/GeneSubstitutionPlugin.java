package org.evochora.runtime.worldgen;

import java.util.Arrays;
import java.util.Map;
import java.util.Random;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.OpcodeId;
import org.evochora.runtime.isa.RegisterBank;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.MoleculeTypeRegistry;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.spi.IBirthHandler;
import org.evochora.runtime.spi.IRandomProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gene substitution birth handler that mutates a single molecule in-place
 * in a newborn organism's genome.
 * <p>
 * Called once per newborn organism in the post-Execute phase of each tick. With configurable
 * probability, selects one random non-empty code-encoding molecule via weighted reservoir
 * sampling and applies the strategy that belongs to the molecule's type.
 * <p>
 * <strong>Strategies:</strong> Types whose value is an identifier have their own strategy — CODE
 * flips to a different registered opcode (operation, family or variant mode), REGISTER moves ±1
 * within its bank boundaries (DR stays DR, PDR stays PDR), LABEL and LABELREF flip N random bits
 * of the 19-bit hash. Every other type carries a plain number and uses the general strategy:
 * scale-proportional perturbation of the signed value, {@code delta = max(1,
 * round(|value|^exponent))} with the type's own exponent, a result leaving the 20-bit range
 * wrapping.
 * <p>
 * <strong>Selection:</strong> Every type registered in {@link org.evochora.runtime.model.MoleculeTypeRegistry}
 * has a selection weight, read from the plugin configuration under the type's name. A type without
 * a configuration block, and a type of a molecule the registry does not know, has weight 0 and is
 * never selected.
 * <p>
 * <strong>CODE Mutation:</strong> At init time, three lookup tables are pre-computed from
 * the registered instruction set. Each table maps an opcode ID to an array of valid alternative
 * opcodes for one flip mode (operation, family, or variant). Variant flips are constrained to
 * the same arity group (0-arg, 1-arg, 2-arg, 3-arg), so instruction length never changes.
 * Every mutation result is guaranteed to be a registered opcode.
 * <p>
 * <strong>Performance:</strong> Near-zero allocation after warmup. The owned cells are visited
 * through the environment's cell views in a single reservoir-sampling pass (no list collection),
 * the reservoir state and the coordinate of the chosen cell live in reusable fields, and CODE
 * mutation uses pre-computed O(1) lookup tables. The only per-call allocations are the visitor
 * lambda and one {@link Molecule} record for the write-back.
 * <p>
 * <strong>Thread Safety:</strong> Not thread-safe. Runs in the sequential post-Execute phase of
 * {@code Simulation.tick()}.
 *
 * @see org.evochora.runtime.spi.IBirthHandler
 * @see GeneInsertionPlugin
 * @see GeneDeletionPlugin
 */
public class GeneSubstitutionPlugin implements IBirthHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GeneSubstitutionPlugin.class);

    /** Maximum label hash value (19-bit unsigned). */
    private static final int LABEL_HASH_BITS = 19;
    private static final int LABEL_HASH_MAX = (1 << LABEL_HASH_BITS) - 1;

    /** Exponent used for a value-carrying type whose configuration block names none. */
    private static final double DEFAULT_EXPONENT = 0.7;

    /**
     * Length of the per-type lookup arrays: one slot for every raw type index a registered type
     * can produce, so the highest registered index is still addressable.
     */
    private static final int TYPE_TABLE_SIZE = typeTableSize();

    /** Arity group boundaries from {@link org.evochora.runtime.isa.Variant}. */
    private static final int ARITY_0_MAX = 15;
    private static final int ARITY_1_MAX = 31;
    private static final int ARITY_2_MAX = 47;

    // --- Immutable config ---
    private final Random random;
    private final double substitutionRate;
    private final double operationFlipWeight;
    private final double familyFlipWeight;
    private final double variantFlipWeight;
    private final double totalFlipWeight;
    private final int labelBitflips;
    private final int labelrefBitflips;

    /** Selection weight per type, indexed by raw type index. Length {@link #TYPE_TABLE_SIZE}. */
    private final double[] typeWeights;

    /**
     * Perturbation exponent per type, indexed by raw type index. Only the slots of types that use
     * the general value strategy are ever read. Length {@link #TYPE_TABLE_SIZE}.
     */
    private final double[] typeExponents;

    // --- Pre-computed opcode alternative tables (computed once at init) ---
    private final Int2ObjectOpenHashMap<int[]> operationFlipAlternatives;
    private final Int2ObjectOpenHashMap<int[]> familyFlipAlternatives;
    private final Int2ObjectOpenHashMap<int[]> variantFlipAlternatives;

    // --- Reservoir state, reused across births (only the visitor lambda and the written molecule are allocated per call) ---
    /** Whether the reservoir holds a candidate cell. */
    private boolean cellChosen;
    /** Type bits (unshifted, as in the packed molecule int) of the candidate cell. */
    private int chosenType;
    /** Raw 20-bit value of the candidate cell. */
    private int chosenRawValue;
    /** Marker of the candidate cell. */
    private int chosenMarker;
    /** Sum of the weights seen so far during the weighted reservoir walk. */
    private double weightSum;
    /** Coordinate of the candidate cell; sized from the world's dimension count on first use. */
    private int[] chosen;

    /**
     * Creates a gene substitution plugin from configuration.
     * <p>
     * Every registered molecule type is looked up under its name. A type with a block contributes
     * its {@code weight}, and, if it uses the general value strategy, its {@code exponent} or
     * {@value #DEFAULT_EXPONENT} when the block names none. A type without a block gets weight 0.
     *
     * @param randomProvider Source of randomness.
     * @param config Configuration containing substitutionRate and one block per mutable type.
     */
    public GeneSubstitutionPlugin(IRandomProvider randomProvider, com.typesafe.config.Config config) {
        this.random = randomProvider.asJavaRandom();
        this.substitutionRate = config.getDouble("substitutionRate");

        this.typeWeights = new double[TYPE_TABLE_SIZE];
        this.typeExponents = new double[TYPE_TABLE_SIZE];
        for (int type : MoleculeTypeRegistry.orderedTypes()) {
            com.typesafe.config.Config typeConfig = blockOrNull(config, MoleculeTypeRegistry.typeToName(type));
            if (typeConfig == null) {
                continue;
            }
            int index = rawIndex(type);
            this.typeWeights[index] = typeConfig.getDouble("weight");
            if (usesValueStrategy(type)) {
                this.typeExponents[index] = typeConfig.hasPath("exponent")
                        ? typeConfig.getDouble("exponent")
                        : DEFAULT_EXPONENT;
            }
        }
        validateRanges(this.substitutionRate, this.typeExponents);

        com.typesafe.config.Config codeConfig = blockOrNull(config, "CODE");
        this.operationFlipWeight = codeConfig == null ? 0.0 : codeConfig.getDouble("operationFlipWeight");
        this.familyFlipWeight = codeConfig == null ? 0.0 : codeConfig.getDouble("familyFlipWeight");
        this.variantFlipWeight = codeConfig == null ? 0.0 : codeConfig.getDouble("variantFlipWeight");
        this.totalFlipWeight = operationFlipWeight + familyFlipWeight + variantFlipWeight;

        com.typesafe.config.Config labelConfig = blockOrNull(config, "LABEL");
        this.labelBitflips = labelConfig == null ? 0 : labelConfig.getInt("bitflips");

        com.typesafe.config.Config labelrefConfig = blockOrNull(config, "LABELREF");
        this.labelrefBitflips = labelrefConfig == null ? 0 : labelrefConfig.getInt("bitflips");

        this.operationFlipAlternatives = new Int2ObjectOpenHashMap<>();
        this.familyFlipAlternatives = new Int2ObjectOpenHashMap<>();
        this.variantFlipAlternatives = new Int2ObjectOpenHashMap<>();
        buildCodeAlternatives();
    }

    /**
     * Convenience constructor for tests.
     * <p>
     * Covers the five types with a non-zero default weight. Every other registered type gets
     * weight 0, and every type that uses the general value strategy gets the given exponent.
     *
     * @param randomProvider Source of randomness.
     * @param substitutionRate Probability of substitution per newborn (0.0 to 1.0).
     * @param codeWeight Selection weight for CODE molecules.
     * @param registerWeight Selection weight for REGISTER molecules.
     * @param dataWeight Selection weight for DATA molecules.
     * @param labelWeight Selection weight for LABEL molecules.
     * @param labelrefWeight Selection weight for LABELREF molecules.
     * @param operationFlipWeight Weight for operation flip mode within CODE.
     * @param familyFlipWeight Weight for family flip mode within CODE.
     * @param variantFlipWeight Weight for variant flip mode within CODE.
     * @param valueExponent Exponent for the scale-proportional value perturbation.
     * @param labelBitflips Number of bits to flip for LABEL mutation.
     * @param labelrefBitflips Number of bits to flip for LABELREF mutation.
     */
    GeneSubstitutionPlugin(IRandomProvider randomProvider, double substitutionRate,
                           double codeWeight, double registerWeight, double dataWeight,
                           double labelWeight, double labelrefWeight,
                           double operationFlipWeight, double familyFlipWeight, double variantFlipWeight,
                           double valueExponent, int labelBitflips, int labelrefBitflips) {
        this.random = randomProvider.asJavaRandom();
        this.substitutionRate = substitutionRate;
        this.operationFlipWeight = operationFlipWeight;
        this.familyFlipWeight = familyFlipWeight;
        this.variantFlipWeight = variantFlipWeight;
        this.totalFlipWeight = operationFlipWeight + familyFlipWeight + variantFlipWeight;
        this.labelBitflips = labelBitflips;
        this.labelrefBitflips = labelrefBitflips;

        this.typeWeights = new double[TYPE_TABLE_SIZE];
        this.typeWeights[rawIndex(Config.TYPE_CODE)] = codeWeight;
        this.typeWeights[rawIndex(Config.TYPE_DATA)] = dataWeight;
        this.typeWeights[rawIndex(Config.TYPE_REGISTER)] = registerWeight;
        this.typeWeights[rawIndex(Config.TYPE_LABEL)] = labelWeight;
        this.typeWeights[rawIndex(Config.TYPE_LABELREF)] = labelrefWeight;

        this.typeExponents = new double[TYPE_TABLE_SIZE];
        for (int type : MoleculeTypeRegistry.orderedTypes()) {
            if (usesValueStrategy(type)) {
                this.typeExponents[rawIndex(type)] = valueExponent;
            }
        }
        validateRanges(this.substitutionRate, this.typeExponents);

        this.operationFlipAlternatives = new Int2ObjectOpenHashMap<>();
        this.familyFlipAlternatives = new Int2ObjectOpenHashMap<>();
        this.variantFlipAlternatives = new Int2ObjectOpenHashMap<>();
        buildCodeAlternatives();
    }

    /** {@inheritDoc} */
    @Override
    public void onBirth(Organism child, Environment environment) {
        if (random.nextDouble() >= substitutionRate) {
            return;
        }
        substitute(child, environment);
    }

    /**
     * Performs gene substitution for a single newborn organism.
     * <p>
     * Iterates the child's owned cells via weighted reservoir sampling to select one random
     * non-empty code-encoding molecule, then applies a type-specific mutation and writes
     * the new value back via {@link Environment#setMoleculeAt(int[], Molecule)}.
     *
     * @param child The newborn organism.
     * @param env The simulation environment.
     */
    void substitute(Organism child, Environment env) {
        int childId = child.getId();
        if (env.countCellsOwnedBy(childId) == 0) {
            LOG.debug("tick={} Organism {} gene substitution: no owned cells", child.getBirthTick(), childId);
            return;
        }

        int dims = env.getProperties().getDimensions();
        if (chosen == null || chosen.length != dims) {
            chosen = new int[dims];
        }
        cellChosen = false;
        weightSum = 0.0;

        // Weighted reservoir sampling over the owned cells.
        // The visit runs in flat-index order: the reservoir choice below must not depend on write history
        env.visitCellsOwnedBy(childId, cell -> {
            int moleculeInt = cell.moleculeInt();
            if (moleculeInt == 0) {
                return; // empty cell
            }
            int typeIdx = (moleculeInt & Config.TYPE_MASK) >>> Config.TYPE_SHIFT;
            if (typeIdx >= TYPE_TABLE_SIZE) {
                return; // no registered type has this index, so its weight is 0
            }
            double w = typeWeights[typeIdx];
            if (w <= 0.0) {
                return; // type disabled
            }
            weightSum += w;
            if (random.nextDouble() * weightSum < w) {
                cellChosen = true;
                System.arraycopy(cell.coordinate(), 0, chosen, 0, dims);
                chosenType = moleculeInt & Config.TYPE_MASK;
                chosenRawValue = moleculeInt & Config.VALUE_MASK;
                chosenMarker = (moleculeInt & Config.MARKER_MASK) >>> Config.MARKER_SHIFT;
            }
        });

        if (!cellChosen) {
            LOG.debug("tick={} Organism {} gene substitution: no mutable molecules", child.getBirthTick(), childId);
            return;
        }

        int selectedType = chosenType;
        int selectedRawValue = chosenRawValue;
        int newValue;

        if (selectedType == Config.TYPE_CODE) {
            newValue = mutateCode(selectedRawValue);
        } else if (selectedType == Config.TYPE_REGISTER) {
            newValue = mutateRegister(selectedRawValue);
        } else if (selectedType == Config.TYPE_LABEL) {
            newValue = mutateLabelHash(selectedRawValue, labelBitflips);
        } else if (selectedType == Config.TYPE_LABELREF) {
            newValue = mutateLabelHash(selectedRawValue, labelrefBitflips);
        } else {
            newValue = mutateValue(selectedRawValue, typeExponents[rawIndex(selectedType)]);
        }

        if (newValue == selectedRawValue) {
            LOG.debug("tick={} Organism {} gene substitution: no-op (value unchanged)", child.getBirthTick(), childId);
            return;
        }

        env.setMoleculeAt(chosen, new Molecule(selectedType, newValue, chosenMarker));

        if (LOG.isDebugEnabled()) {
            String typeName = MoleculeTypeRegistry.typeToName(selectedType);
            LOG.debug("tick={} Organism {} gene substitution: {}:{}->{} at {}",
                    child.getBirthTick(), childId, typeName,
                    displayValueForLog(selectedType, selectedRawValue),
                    displayValueForLog(selectedType, newValue), Arrays.toString(chosen));
        }
    }

    // ---- Per-type mutation methods ----

    /**
     * Mutates a CODE molecule's opcode value by flipping to a random valid alternative.
     * <p>
     * Selects a flip mode (operation, family, or variant) based on configured weights,
     * then picks a random alternative from the pre-computed lookup table. If no alternatives
     * exist for the selected mode, returns the original value unchanged.
     *
     * @param opcodeValue The current opcode value (bare, without TYPE_CODE bits).
     * @return The mutated opcode value, or the original if no alternative exists.
     */
    private int mutateCode(int opcodeValue) {
        double r = random.nextDouble() * totalFlipWeight;
        int[] alternatives;
        if (r < operationFlipWeight) {
            alternatives = operationFlipAlternatives.get(opcodeValue);
        } else if (r < operationFlipWeight + familyFlipWeight) {
            alternatives = familyFlipAlternatives.get(opcodeValue);
        } else {
            alternatives = variantFlipAlternatives.get(opcodeValue);
        }

        if (alternatives == null || alternatives.length == 0) {
            return opcodeValue;
        }

        return alternatives[random.nextInt(alternatives.length)];
    }

    /**
     * Mutates a REGISTER molecule's value by ±1, clamped within the register bank.
     * <p>
     * Bank detection uses {@link RegisterBank} base addresses in descending order.
     *
     * @param regValue The current register ID.
     * @return The mutated register ID, clamped to bank boundaries.
     */
    private int mutateRegister(int regValue) {
        int delta = random.nextBoolean() ? 1 : -1;
        int newValue = regValue + delta;

        RegisterBank bank = RegisterBank.forId(regValue);
        if (bank == null) {
            return regValue;
        }
        return Math.max(bank.base, Math.min(bank.base + bank.count - 1, newValue));
    }

    /**
     * Mutates the value of a value-carrying molecule using scale-proportional perturbation.
     * <p>
     * Computes delta as {@code max(1, round(|value|^exponent))}, then adds a uniform random
     * offset in {@code [-delta, +delta]}. Small values therefore change relatively strongly and
     * large values relatively weakly, producing smooth fitness landscape perturbations.
     * <p>
     * The perturbation runs on the sign-extended value, so a stored {@code -1} moves by one rather
     * than by the delta belonging to the unsigned pattern {@code 1048575}. A result leaving the
     * signed 20-bit range wraps modulo 2^20, which is how the instruction set treats arithmetic
     * on these values as well.
     * <p>
     * Input and output are the raw 20-bit pattern, as for every mutator in this class: the caller
     * compares the returned pattern against the sampled one to detect a no-op.
     *
     * @param rawValue The current value as the raw 20-bit pattern.
     * @param exponent The exponent of the scale-proportional delta, in {@code [0.0, 1.0]}.
     * @return The mutated value as the raw 20-bit pattern.
     */
    private int mutateValue(int rawValue, double exponent) {
        int value = Molecule.extractSignedValue(rawValue);
        int delta = Math.max(1, (int) Math.round(Math.pow(Math.abs(value), exponent)));
        int offset = random.nextInt(2 * delta + 1) - delta;
        return (value + offset) & Config.VALUE_MASK;
    }

    /**
     * Mutates a LABEL or LABELREF molecule's hash by flipping random bits.
     *
     * @param hash The current 19-bit hash value.
     * @param bitflips Number of bits to flip.
     * @return The mutated hash, masked to 19-bit range.
     */
    private int mutateLabelHash(int hash, int bitflips) {
        return flipBits(hash, bitflips);
    }

    /**
     * Flips a specified number of random bits in a label hash.
     * Uses a bitmask to track selected positions (zero allocation).
     *
     * @param hash The original hash.
     * @param bitflips Number of bits to flip.
     * @return The hash with flipped bits, masked to 19-bit range.
     */
    int flipBits(int hash, int bitflips) {
        int selectedBits = 0;
        for (int i = 0; i < bitflips; i++) {
            int bit;
            do {
                bit = random.nextInt(LABEL_HASH_BITS);
            } while ((selectedBits & (1 << bit)) != 0);
            selectedBits |= (1 << bit);
            hash ^= (1 << bit);
        }
        return hash & LABEL_HASH_MAX;
    }

    // ---- Pre-computation ----

    /**
     * Builds the pre-computed opcode alternative tables for the three flip modes.
     * <p>
     * Groups all registered opcodes by shared components, then for each opcode stores
     * the list of valid alternatives (same group, minus self) as an {@code int[]}.
     * This is called once at construction time; the resulting tables provide O(1)
     * lookup during mutation.
     */
    private void buildCodeAlternatives() {
        Map<Integer, String> allOpcodes = Instruction.getAllInstructions();

        // Intermediate grouping maps
        Int2ObjectOpenHashMap<IntArrayList> opGroups = new Int2ObjectOpenHashMap<>();
        Int2ObjectOpenHashMap<IntArrayList> famGroups = new Int2ObjectOpenHashMap<>();
        Int2ObjectOpenHashMap<IntArrayList> varGroups = new Int2ObjectOpenHashMap<>();

        for (int opcodeId : allOpcodes.keySet()) {
            int family = OpcodeId.extractFamily(opcodeId);
            int operation = OpcodeId.extractOperation(opcodeId);
            int variant = OpcodeId.extractVariant(opcodeId);
            int ag = arityGroup(variant);

            int opKey = family * 64 + variant;
            opGroups.computeIfAbsent(opKey, k -> new IntArrayList()).add(opcodeId);

            int famKey = operation * 64 + variant;
            famGroups.computeIfAbsent(famKey, k -> new IntArrayList()).add(opcodeId);

            int varKey = family * 256 + operation * 4 + ag;
            varGroups.computeIfAbsent(varKey, k -> new IntArrayList()).add(opcodeId);
        }

        for (int opcodeId : allOpcodes.keySet()) {
            int family = OpcodeId.extractFamily(opcodeId);
            int operation = OpcodeId.extractOperation(opcodeId);
            int variant = OpcodeId.extractVariant(opcodeId);
            int ag = arityGroup(variant);

            operationFlipAlternatives.put(opcodeId,
                    filterSelf(opGroups.get(family * 64 + variant), opcodeId));
            familyFlipAlternatives.put(opcodeId,
                    filterSelf(famGroups.get(operation * 64 + variant), opcodeId));
            variantFlipAlternatives.put(opcodeId,
                    filterSelf(varGroups.get(family * 256 + operation * 4 + ag), opcodeId));
        }
    }

    /**
     * Filters a group list to exclude the given opcode, returning an array of alternatives.
     *
     * @param group The group of opcodes sharing a common key.
     * @param selfId The opcode to exclude.
     * @return An array of alternative opcodes, or {@code null} if no alternatives exist.
     */
    private static int[] filterSelf(IntArrayList group, int selfId) {
        if (group == null || group.size() <= 1) {
            return null;
        }
        int[] result = new int[group.size() - 1];
        int idx = 0;
        for (int i = 0; i < group.size(); i++) {
            int id = group.getInt(i);
            if (id != selfId) {
                result[idx++] = id;
            }
        }
        if (idx == 0) {
            return null;
        }
        return idx == result.length ? result : Arrays.copyOf(result, idx);
    }

    /**
     * Returns the arity group (0-3) for a variant ID.
     * <p>
     * Arity groups: 0-15 → 0-arg, 16-31 → 1-arg, 32-47 → 2-arg, 48-63 → 3-arg.
     *
     * @param variant The variant ID.
     * @return The arity group (0, 1, 2, or 3).
     */
    static int arityGroup(int variant) {
        if (variant <= ARITY_0_MAX) return 0;
        if (variant <= ARITY_1_MAX) return 1;
        if (variant <= ARITY_2_MAX) return 2;
        return 3;
    }

    // ---- Utilities ----

    /**
     * Rejects configuration values outside their valid range.
     * <p>
     * Both conditions are written positively, because {@code NaN} compares {@code false} to every
     * bound and would pass a negated form. Its effects are silent: a {@code NaN} rate makes the
     * probability test false and mutates every newborn, and a {@code NaN} exponent collapses delta
     * to 1, turning the perturbation into a constant step of one.
     * <p>
     * The exponent is limited to {@code [0.0, 1.0]} by its meaning. At 0 the delta is 1 for every
     * value, at 1 it equals the value itself, which is the strongest step that is still
     * proportional. Beyond that the delta exceeds the value and the perturbation is no longer
     * scale-proportional.
     *
     * @param substitutionRate Probability of substitution per newborn.
     * @param exponents Perturbation exponents indexed by raw type index.
     * @throws IllegalArgumentException If any value lies outside {@code [0.0, 1.0]}.
     */
    private static void validateRanges(double substitutionRate, double[] exponents) {
        if (!(substitutionRate >= 0.0 && substitutionRate <= 1.0)) {
            throw new IllegalArgumentException("substitutionRate must be in [0.0, 1.0], got: " + substitutionRate);
        }
        for (int type : MoleculeTypeRegistry.orderedTypes()) {
            double exponent = exponents[rawIndex(type)];
            if (!(exponent >= 0.0 && exponent <= 1.0)) {
                throw new IllegalArgumentException(MoleculeTypeRegistry.typeToName(type)
                        + " exponent must be in [0.0, 1.0], got: " + exponent);
            }
        }
    }

    /**
     * Returns the position of a molecule type's bits within the packed molecule integer, shifted
     * down to a small index. This is the index of every per-type lookup array in this class.
     *
     * @param type The shifted type constant.
     * @return The raw type index.
     */
    private static int rawIndex(int type) {
        return (type & Config.TYPE_MASK) >>> Config.TYPE_SHIFT;
    }

    /**
     * Returns the length a per-type lookup array needs to address every registered type.
     *
     * @return One more than the highest raw type index in the registry.
     */
    private static int typeTableSize() {
        int highest = 0;
        for (int type : MoleculeTypeRegistry.orderedTypes()) {
            highest = Math.max(highest, rawIndex(type));
        }
        return highest + 1;
    }

    /**
     * Reports whether a type is mutated by the general scale-proportional value perturbation.
     * <p>
     * The types that answer {@code false} carry an identifier in their value field — an opcode, a
     * register id, a label hash — and have a strategy of their own. Every other type carries a
     * plain number.
     *
     * @param type The shifted type constant.
     * @return true if the general value strategy applies to this type.
     */
    private static boolean usesValueStrategy(int type) {
        return type != Config.TYPE_CODE
                && type != Config.TYPE_REGISTER
                && type != Config.TYPE_LABEL
                && type != Config.TYPE_LABELREF;
    }

    /**
     * Returns a configuration block if the configuration has one under that path.
     *
     * @param config The plugin configuration.
     * @param path The block name.
     * @return The block, or {@code null} if the configuration does not contain it.
     */
    private static com.typesafe.config.Config blockOrNull(com.typesafe.config.Config config, String path) {
        return config.hasPath(path) ? config.getConfig(path) : null;
    }

    /**
     * Returns the value to print for debug logging.
     * <p>
     * A value-carrying molecule is shown sign-extended, matching how every other reader of the
     * cell sees it. The remaining types carry identifiers, for which the raw pattern is correct.
     *
     * @param type The shifted type constant.
     * @param value The raw 20-bit value pattern.
     * @return The value in the representation that belongs to the type.
     */
    private static int displayValueForLog(int type, int value) {
        return usesValueStrategy(type) ? Molecule.extractSignedValue(value) : value;
    }

    /** {@inheritDoc} */
    @Override
    public byte[] saveState() {
        return new byte[0];
    }

    /** {@inheritDoc} */
    @Override
    public void loadState(byte[] state) {
        // Stateless plugin - nothing to restore
    }
}
