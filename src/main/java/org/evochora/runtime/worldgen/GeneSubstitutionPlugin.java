package org.evochora.runtime.worldgen;

import java.util.Arrays;
import java.util.Map;
import java.util.Random;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.OpcodeId;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
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
 * sampling and applies a type-specific mutation:
 * <ul>
 *   <li><b>CODE:</b> Flip to a different registered opcode (operation/family/variant modes)</li>
 *   <li><b>REGISTER:</b> ±1 within bank boundaries (DR stays DR, PR stays PR, etc.)</li>
 *   <li><b>DATA:</b> Scale-proportional perturbation: delta = max(1, round(|value|^exponent))</li>
 *   <li><b>LABEL/LABELREF:</b> Flip N random bits in 19-bit hash</li>
 * </ul>
 * ENERGY and STRUCTURE molecules are never mutated (world-substance types).
 * <p>
 * <strong>CODE Mutation:</strong> At init time, three lookup tables are pre-computed from
 * the registered instruction set. Each table maps an opcode ID to an array of valid alternative
 * opcodes for one flip mode (operation, family, or variant). Variant flips are constrained to
 * the same arity group (0-arg, 1-arg, 2-arg, 3-arg), so instruction length never changes.
 * Every mutation result is guaranteed to be a registered opcode.
 * <p>
 * <strong>Performance:</strong> Near-zero allocation after warmup. The entire operation works
 * on flat indices (no coordinate buffers), uses a single-pass reservoir sampling (no list
 * collection), and pre-computed O(1) lookup tables for CODE mutation. The only per-call
 * allocation is one {@link Molecule} record for the write-back.
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

    /** Number of molecule types (CODE=0 through REGISTER=6). */
    private static final int NUM_TYPES = 7;

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
    private final double dataExponent;
    private final int labelBitflips;
    private final int labelrefBitflips;

    /** Type weight lookup: indexed by (type >> TYPE_SHIFT). Length {@value NUM_TYPES}. */
    private final double[] typeWeights;

    // --- Pre-computed opcode alternative tables (computed once at init) ---
    private final Int2ObjectOpenHashMap<int[]> operationFlipAlternatives;
    private final Int2ObjectOpenHashMap<int[]> familyFlipAlternatives;
    private final Int2ObjectOpenHashMap<int[]> variantFlipAlternatives;

    /**
     * Creates a gene substitution plugin from configuration.
     *
     * @param randomProvider Source of randomness.
     * @param config Configuration containing substitutionRate and per-type settings.
     */
    public GeneSubstitutionPlugin(IRandomProvider randomProvider, com.typesafe.config.Config config) {
        this.random = randomProvider.asJavaRandom();
        this.substitutionRate = config.getDouble("substitutionRate");
        if (substitutionRate < 0.0 || substitutionRate > 1.0) {
            throw new IllegalArgumentException("substitutionRate must be in [0.0, 1.0], got: " + substitutionRate);
        }

        com.typesafe.config.Config codeConfig = config.getConfig("CODE");
        double codeWeight = codeConfig.getDouble("weight");
        this.operationFlipWeight = codeConfig.getDouble("operationFlipWeight");
        this.familyFlipWeight = codeConfig.getDouble("familyFlipWeight");
        this.variantFlipWeight = codeConfig.getDouble("variantFlipWeight");
        this.totalFlipWeight = operationFlipWeight + familyFlipWeight + variantFlipWeight;

        double registerWeight = config.getConfig("REGISTER").getDouble("weight");

        com.typesafe.config.Config dataConfig = config.getConfig("DATA");
        double dataWeight = dataConfig.getDouble("weight");
        this.dataExponent = dataConfig.getDouble("exponent");

        com.typesafe.config.Config labelConfig = config.getConfig("LABEL");
        double labelWeight = labelConfig.getDouble("weight");
        this.labelBitflips = labelConfig.getInt("bitflips");

        com.typesafe.config.Config labelrefConfig = config.getConfig("LABELREF");
        double labelrefWeight = labelrefConfig.getDouble("weight");
        this.labelrefBitflips = labelrefConfig.getInt("bitflips");

        this.typeWeights = buildTypeWeights(codeWeight, dataWeight, registerWeight, labelWeight, labelrefWeight);

        this.operationFlipAlternatives = new Int2ObjectOpenHashMap<>();
        this.familyFlipAlternatives = new Int2ObjectOpenHashMap<>();
        this.variantFlipAlternatives = new Int2ObjectOpenHashMap<>();
        buildCodeAlternatives();
    }

    /**
     * Convenience constructor for tests.
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
     * @param dataExponent Exponent for scale-proportional DATA mutation.
     * @param labelBitflips Number of bits to flip for LABEL mutation.
     * @param labelrefBitflips Number of bits to flip for LABELREF mutation.
     */
    GeneSubstitutionPlugin(IRandomProvider randomProvider, double substitutionRate,
                           double codeWeight, double registerWeight, double dataWeight,
                           double labelWeight, double labelrefWeight,
                           double operationFlipWeight, double familyFlipWeight, double variantFlipWeight,
                           double dataExponent, int labelBitflips, int labelrefBitflips) {
        this.random = randomProvider.asJavaRandom();
        this.substitutionRate = substitutionRate;
        this.operationFlipWeight = operationFlipWeight;
        this.familyFlipWeight = familyFlipWeight;
        this.variantFlipWeight = variantFlipWeight;
        this.totalFlipWeight = operationFlipWeight + familyFlipWeight + variantFlipWeight;
        this.dataExponent = dataExponent;
        this.labelBitflips = labelBitflips;
        this.labelrefBitflips = labelrefBitflips;

        this.typeWeights = buildTypeWeights(codeWeight, dataWeight, registerWeight, labelWeight, labelrefWeight);

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
     * the new value back via {@link Environment#setMoleculeByIndex}.
     *
     * @param child The newborn organism.
     * @param env The simulation environment.
     */
    void substitute(Organism child, Environment env) {
        int childId = child.getId();
        IntOpenHashSet owned = env.getCellsOwnedBy(childId);
        if (owned == null || owned.isEmpty()) {
            LOG.debug("tick={} Organism {} gene substitution: no owned cells", child.getBirthTick(), childId);
            return;
        }

        // Weighted reservoir sampling — state captured via arrays for lambda
        // [0]=flatIndex, [1]=type (shifted), [2]=value, [3]=marker
        final int[] state = {-1, 0, 0, 0};
        final double[] ws = {0.0};

        owned.forEach((int flatIndex) -> {
            int moleculeInt = env.getMoleculeInt(flatIndex);
            if (moleculeInt == 0) {
                return; // empty cell
            }
            int typeIdx = (moleculeInt & Config.TYPE_MASK) >>> Config.TYPE_SHIFT;
            if (typeIdx >= NUM_TYPES) {
                return; // unknown type
            }
            double w = typeWeights[typeIdx];
            if (w <= 0.0) {
                return; // type disabled
            }
            ws[0] += w;
            if (random.nextDouble() * ws[0] < w) {
                state[0] = flatIndex;
                state[1] = moleculeInt & Config.TYPE_MASK;
                state[2] = moleculeInt & Config.VALUE_MASK;
                state[3] = (moleculeInt & Config.MARKER_MASK) >>> Config.MARKER_SHIFT;
            }
        });

        if (state[0] == -1) {
            LOG.debug("tick={} Organism {} gene substitution: no mutable molecules", child.getBirthTick(), childId);
            return;
        }

        int selectedType = state[1];
        int selectedValue = state[2];
        int newValue;

        if (selectedType == Config.TYPE_CODE) {
            newValue = mutateCode(selectedValue);
        } else if (selectedType == Config.TYPE_REGISTER) {
            newValue = mutateRegister(selectedValue);
        } else if (selectedType == Config.TYPE_DATA) {
            newValue = mutateData(selectedValue);
        } else if (selectedType == Config.TYPE_LABEL) {
            newValue = mutateLabelHash(selectedValue, labelBitflips);
        } else if (selectedType == Config.TYPE_LABELREF) {
            newValue = mutateLabelHash(selectedValue, labelrefBitflips);
        } else {
            return;
        }

        if (newValue == selectedValue) {
            LOG.debug("tick={} Organism {} gene substitution: no-op (value unchanged)", child.getBirthTick(), childId);
            return;
        }

        env.setMoleculeByIndex(state[0], new Molecule(selectedType, newValue, state[3]));

        if (LOG.isDebugEnabled()) {
            String typeName = typeNameForLog(selectedType);
            LOG.debug("tick={} Organism {} gene substitution: {}:{}->{} at flatIndex={}",
                    child.getBirthTick(), childId, typeName, selectedValue, newValue, state[0]);
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
     * Bank detection: {@code >=LR_BASE} → LR (3000-3003), {@code >=FPR_BASE} → FPR (2000-2007),
     * {@code >=PR_BASE} → PR (1000-1007), else DR (0-7).
     *
     * @param regValue The current register ID.
     * @return The mutated register ID, clamped to bank boundaries.
     */
    private int mutateRegister(int regValue) {
        int delta = random.nextBoolean() ? 1 : -1;
        int newValue = regValue + delta;

        int base;
        int maxOffset;
        if (regValue >= Instruction.LR_BASE) {
            base = Instruction.LR_BASE;
            maxOffset = Config.NUM_LOCATION_REGISTERS - 1;
        } else if (regValue >= Instruction.FPR_BASE) {
            base = Instruction.FPR_BASE;
            maxOffset = Config.NUM_FORMAL_PARAM_REGISTERS - 1;
        } else if (regValue >= Instruction.PR_BASE) {
            base = Instruction.PR_BASE;
            maxOffset = Config.NUM_PROC_REGISTERS - 1;
        } else {
            base = 0;
            maxOffset = Config.NUM_DATA_REGISTERS - 1;
        }

        return Math.max(base, Math.min(base + maxOffset, newValue));
    }

    /**
     * Mutates a DATA molecule's value using scale-proportional perturbation.
     * <p>
     * Computes delta as {@code max(1, round(|value|^exponent))}, then adds a uniform random
     * offset in {@code [-delta, +delta]}. The result is clamped to {@code [0, VALUE_MASK]}.
     * This ensures small values change relatively strongly while large values change relatively
     * weakly, producing smooth fitness landscape perturbations.
     *
     * @param dataValue The current data value.
     * @return The mutated data value, clamped to valid range.
     */
    private int mutateData(int dataValue) {
        int delta = Math.max(1, (int) Math.round(Math.pow(Math.abs(dataValue), dataExponent)));
        int offset = random.nextInt(2 * delta + 1) - delta;
        int newValue = dataValue + offset;
        return Math.max(0, Math.min(Config.VALUE_MASK, newValue));
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
     * Builds the type weight lookup array.
     *
     * @return Array indexed by (type >> TYPE_SHIFT), length {@value NUM_TYPES}.
     */
    private static double[] buildTypeWeights(double codeWeight, double dataWeight, double registerWeight,
                                             double labelWeight, double labelrefWeight) {
        double[] weights = new double[NUM_TYPES];
        weights[0x00] = codeWeight;      // TYPE_CODE
        weights[0x01] = dataWeight;      // TYPE_DATA
        weights[0x02] = 0.0;            // TYPE_ENERGY — never mutated
        weights[0x03] = 0.0;            // TYPE_STRUCTURE — never mutated
        weights[0x04] = labelWeight;     // TYPE_LABEL
        weights[0x05] = labelrefWeight;  // TYPE_LABELREF
        weights[0x06] = registerWeight;  // TYPE_REGISTER
        return weights;
    }

    /**
     * Returns a human-readable type name for debug logging.
     *
     * @param type The shifted type constant.
     * @return Short type name.
     */
    private static String typeNameForLog(int type) {
        if (type == Config.TYPE_CODE) return "CODE";
        if (type == Config.TYPE_DATA) return "DATA";
        if (type == Config.TYPE_REGISTER) return "REG";
        if (type == Config.TYPE_LABEL) return "LABEL";
        if (type == Config.TYPE_LABELREF) return "LABELREF";
        return "?";
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
