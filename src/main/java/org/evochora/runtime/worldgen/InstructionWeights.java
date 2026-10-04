package org.evochora.runtime.worldgen;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigValue;
import org.evochora.runtime.isa.Instruction;

import it.unimi.dsi.fastutil.ints.Int2DoubleOpenHashMap;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * How strongly a mutation plugin prefers each opcode when it draws one, read from an
 * {@code instructionWeights} block.
 * <p>
 * The weight of an opcode is the weight of its family, the instruction class that registers it,
 * times its own weight. A family named under {@code families} carries its {@code weight} and a
 * {@code default} for its opcodes that are not named under its {@code opcodes}; a family not named
 * there weighs the top-level {@code default}, and each of its opcodes one. A weight of zero leaves
 * an opcode out. A top-level {@code default} of one names what is left out, a blacklist; a
 * {@code default} of zero names what is drawn, a whitelist that stays closed against instructions
 * added later.
 * <p>
 * {@code families} is a list, so that a configuration which sets it replaces the list as a whole
 * and nothing is merged in from another file. Every value is required; there is no default in the
 * code.
 */
public final class InstructionWeights {

    private static final Set<String> BLOCK_KEYS = Set.of("default", "families");
    private static final Set<String> FAMILY_KEYS = Set.of("class", "weight", "default", "opcodes");

    /** The weight of every registered opcode. */
    private final Int2DoubleOpenHashMap weightById;

    private InstructionWeights(Int2DoubleOpenHashMap weightById) {
        this.weightById = weightById;
    }

    /**
     * Reads an {@code instructionWeights} block.
     *
     * @param block the block
     * @param owner what the block configures, for the messages of a rejection
     * @return the weights
     * @throws IllegalArgumentException if a key is missing or unknown, a family is no instruction
     *         class or is listed twice, an opcode is unknown or belongs to another family, or a
     *         weight is negative or no number
     */
    public static InstructionWeights fromConfig(Config block, String owner) {
        requireKeys(block, BLOCK_KEYS, BLOCK_KEYS, owner + " instructionWeights");
        double topDefault = requireWeight(block, "default", owner + " instructionWeights");

        Map<String, Class<? extends Instruction>> classByName = new HashMap<>();
        for (Instruction.InstructionInfo info : Instruction.getInstructionSetInfo()) {
            classByName.put(info.family().getName(), info.family());
        }

        Map<Class<? extends Instruction>, Double> familyWeight = new HashMap<>();
        Map<Class<? extends Instruction>, Double> familyDefault = new HashMap<>();
        Int2DoubleOpenHashMap opcodeWeight = new Int2DoubleOpenHashMap();
        Set<Integer> namedOpcodes = new HashSet<>();

        List<? extends Config> families = block.getConfigList("families");
        for (Config family : families) {
            String where = owner + " instructionWeights.families";
            requireKeys(family, FAMILY_KEYS, Set.of("class", "weight", "default"), where);
            String className = family.getString("class");
            Class<? extends Instruction> familyClass = classByName.get(className);
            if (familyClass == null) {
                throw new IllegalArgumentException(where + ": '" + className
                        + "' is no instruction class; a family is named by the full name of the class that registers it.");
            }
            if (familyWeight.containsKey(familyClass)) {
                throw new IllegalArgumentException(where + ": " + className + " is listed twice.");
            }
            familyWeight.put(familyClass, requireWeight(family, "weight", where + " " + className));
            familyDefault.put(familyClass, requireWeight(family, "default", where + " " + className));
            if (family.hasPath("opcodes")) {
                Config opcodes = family.getConfig("opcodes");
                for (Map.Entry<String, ConfigValue> entry : opcodes.root().entrySet()) {
                    String name = entry.getKey();
                    Integer opcodeId = Instruction.getInstructionIdByName(name.toUpperCase());
                    if (opcodeId == null) {
                        throw new IllegalArgumentException(where + " " + className + ": '" + name + "' is no opcode.");
                    }
                    if (Instruction.getInstructionClassById(opcodeId) != familyClass) {
                        throw new IllegalArgumentException(where + " " + className + ": " + name + " belongs to "
                                + Instruction.getInstructionClassById(opcodeId).getName() + ".");
                    }
                    opcodeWeight.put(opcodeId.intValue(), requireWeight(opcodes, quoted(name), where + " " + className));
                    namedOpcodes.add(opcodeId);
                }
            }
        }

        Int2DoubleOpenHashMap weights = new Int2DoubleOpenHashMap();
        for (int opcodeId : Instruction.getAllInstructions().keySet()) {
            Class<? extends Instruction> familyClass = Instruction.getInstructionClassById(opcodeId);
            double weight;
            if (!familyWeight.containsKey(familyClass)) {
                weight = topDefault;
            } else if (namedOpcodes.contains(opcodeId)) {
                weight = familyWeight.get(familyClass) * opcodeWeight.get(opcodeId);
            } else {
                weight = familyWeight.get(familyClass) * familyDefault.get(familyClass);
            }
            weights.put(opcodeId, weight);
        }
        return new InstructionWeights(weights);
    }

    /**
     * Returns the weight of an opcode.
     *
     * @param opcodeId the opcode ID
     * @return its weight, zero for an opcode that is not registered
     */
    public double weightOf(int opcodeId) {
        return weightById.get(opcodeId);
    }

    /**
     * Collects the opcodes a draw may choose from, with their weights.
     *
     * @param opcodeIds the candidates
     * @return the candidates of weight above zero, empty if there is none
     */
    WeightedOpcodes select(int[] opcodeIds) {
        double[] weights = new double[opcodeIds.length];
        for (int i = 0; i < opcodeIds.length; i++) {
            weights[i] = weightOf(opcodeIds[i]);
        }
        return WeightedOpcodes.of(opcodeIds, weights);
    }

    /**
     * Collects every registered opcode a predicate accepts, with its weight.
     *
     * @param candidate which opcodes may be drawn at all
     * @return the accepted opcodes of weight above zero, in ascending order of their IDs
     */
    WeightedOpcodes selectRegistered(IntPredicate candidate) {
        return select(Instruction.getAllInstructions().keySet().stream()
                .mapToInt(Integer::intValue)
                .filter(candidate)
                .sorted()
                .toArray());
    }

    private static String quoted(String key) {
        return "\"" + key + "\"";
    }

    /**
     * Rejects a block that lacks a required key or carries one this block does not know.
     */
    private static void requireKeys(Config block, Set<String> accepted, Set<String> required, String where) {
        for (String key : block.root().keySet()) {
            if (!accepted.contains(key)) {
                throw new IllegalArgumentException(where + " has no setting '" + key + "'; accepted names are "
                        + String.join(", ", accepted.stream().sorted().toList()) + ".");
            }
        }
        for (String key : required) {
            if (!block.hasPath(key)) {
                throw new IllegalArgumentException(where + " needs '" + key + "'.");
            }
        }
    }

    /**
     * Reads a weight and rejects one that is negative or no number.
     */
    private static double requireWeight(Config block, String path, String where) {
        double weight = block.getDouble(path);
        if (!(weight >= 0.0) || Double.isInfinite(weight)) {
            throw new IllegalArgumentException(where + ": weight " + path + " must be a number of at least 0, got " + weight);
        }
        return weight;
    }
}
