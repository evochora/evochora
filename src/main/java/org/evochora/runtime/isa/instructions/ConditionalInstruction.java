package org.evochora.runtime.isa.instructions;

import org.evochora.runtime.Config;
import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.Variant;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.Environment;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.evochora.runtime.isa.Instruction.OperandSource.*;

/**
 * Handles conditional instructions, which compare values and skip the next instruction
 * if the condition is not met. It supports different operand types and sources.
 */
public class ConditionalInstruction extends Instruction {

    private static int family;

    /** Maps each conditional opcode name to the name of the opcode that skips exactly when it does not. */
    private static final Map<String, String> NEGATION_BY_NAME = new HashMap<>();

    /**
     * Registers all conditional instructions with the instruction registry.
     * <p>
     * Every conditional is registered together with its negation, the instruction that skips
     * the next instruction exactly when this one does not. The two share their operands, so
     * one can stand in for the other wherever a condition has to be inverted.
     *
     * @param f the family ID for this instruction family
     */
    public static void register(int f) {
        family = f;
        // Operations 0 and 1: equal / not equal
        regPair(0, 1, Variant.RR, "IFR", "INR", REGISTER, REGISTER);
        regPair(0, 1, Variant.RI, "IFI", "INI", REGISTER, IMMEDIATE);
        regPair(0, 1, Variant.SS, "IFS", "INS", STACK, STACK);
        // Operations 2 and 5: less than / greater than or equal
        regPair(2, 5, Variant.RR, "LTR", "GETR", REGISTER, REGISTER);
        regPair(2, 5, Variant.RI, "LTI", "GETI", REGISTER, IMMEDIATE);
        regPair(2, 5, Variant.SS, "LTS", "GETS", STACK, STACK);
        // Operations 3 and 4: greater than / less than or equal
        regPair(3, 4, Variant.RR, "GTR", "LETR", REGISTER, REGISTER);
        regPair(3, 4, Variant.RI, "GTI", "LETI", REGISTER, IMMEDIATE);
        regPair(3, 4, Variant.SS, "GTS", "LETS", STACK, STACK);
        // Operations 6 and 7: true (non-zero) / not true (zero)
        regPair(6, 7, Variant.RR, "IFTR", "INTR", REGISTER, REGISTER);
        regPair(6, 7, Variant.RI, "IFTI", "INTI", REGISTER, IMMEDIATE);
        regPair(6, 7, Variant.SS, "IFTS", "INTS", STACK, STACK);
        // Operations 8 and 9: mine / not mine (ownership check)
        regPair(8, 9, Variant.R, "IFMR", "INMR", REGISTER);
        regPair(8, 9, Variant.V, "IFMI", "INMI", VECTOR);  // Note: uses VECTOR operand despite "I" suffix
        regPair(8, 9, Variant.S, "IFMS", "INMS", STACK);
        // Operations 10 and 11: passable / not passable
        regPair(10, 11, Variant.R, "IFPR", "INPR", REGISTER);
        regPair(10, 11, Variant.V, "IFPI", "INPI", VECTOR);  // Note: uses VECTOR operand despite "I" suffix
        regPair(10, 11, Variant.S, "IFPS", "INPS", STACK);
        // Operations 12 and 13: foreign ownership / not foreign ownership
        regPair(12, 13, Variant.R, "IFFR", "INFR", REGISTER);
        regPair(12, 13, Variant.V, "IFFI", "INFI", VECTOR);  // Note: uses VECTOR operand despite "I" suffix
        regPair(12, 13, Variant.S, "IFFS", "INFS", STACK);
        // Operations 14 and 15: vacant ownership / not vacant ownership
        regPair(14, 15, Variant.R, "IFVR", "INVR", REGISTER);
        regPair(14, 15, Variant.V, "IFVI", "INVI", VECTOR);  // Note: uses VECTOR operand despite "I" suffix
        regPair(14, 15, Variant.S, "IFVS", "INVS", STACK);
        // Operations 16 and 17: previous instruction failed / did not fail
        regPair(16, 17, Variant.NONE, "IFER", "INER");
    }

    /**
     * Registers a conditional and its negation, one variant of each, with the same operands.
     */
    private static void regPair(int op, int negatedOp, int variant, String name, String negatedName,
                                OperandSource... sources) {
        reg(op, variant, name, sources);
        reg(negatedOp, variant, negatedName, sources);
        NEGATION_BY_NAME.put(name.toUpperCase(), negatedName.toUpperCase());
        NEGATION_BY_NAME.put(negatedName.toUpperCase(), name.toUpperCase());
    }

    private static void reg(int op, int variant, String name, OperandSource... sources) {
        Instruction.registerOp(ConditionalInstruction.class, ConditionalInstruction::new, family, op, variant, name, true, sources);
    }

    /**
     * Returns the opcode that skips the next instruction exactly when the given one does not.
     * The two take the same operands, so the negation can replace the original wherever a
     * condition has to be inverted.
     *
     * @param opcodeName the name of a conditional opcode, in any letter case
     * @return the name of its negation, or empty if the name is not a conditional opcode
     */
    public static Optional<String> negationOf(String opcodeName) {
        return Optional.ofNullable(NEGATION_BY_NAME.get(opcodeName.toUpperCase()));
    }

    /**
     * Constructs a new ConditionalInstruction.
     * @param organism The organism executing the instruction.
     * @param fullOpcodeId The full opcode ID of the instruction.
     */
    public ConditionalInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    @Override
    public void execute(ExecutionContext context) {
        Organism organism = context.getOrganism();
        Environment environment = context.getWorld();
        try {
            String opName = getName();
            if ("IFER".equals(opName) || "INER".equals(opName)) {
                boolean prevFailed = organism.wasPreviousInstructionFailed();
                boolean conditionMet = "IFER".equals(opName) ? prevFailed : !prevFailed;
                if (!conditionMet) {
                    organism.skipNextInstruction(environment);
                }
                return;
            }
            if (opName.startsWith("IFM") || opName.startsWith("INM")) {
                List<Operand> operands = resolveOperands(environment);
                if (organism.isInstructionFailed()) {
                    return;
                }
                if (operands.size() != 1) {
                    organism.instructionFailed("Invalid operand count for " + opName);
                    return;
                }
                Operand op = operands.get(0);
                if (!(op.value() instanceof int[])) {
                    organism.instructionFailed(opName + " requires a vector argument.");
                    return;
                }
                int[] vector = (int[]) op.value();
                if (!organism.isUnitVector(vector)) {
                    return;
                }
                int[] targetCoordinate = organism.getTargetCoordinate(organism.getActiveDp(), vector, environment);
                int ownerId = environment.getOwnerId(targetCoordinate);
                boolean isAccessible = organism.isCellAccessible(ownerId);
                boolean conditionMet = opName.startsWith("IFM") ? isAccessible : !isAccessible;
                if (!conditionMet) {
                    organism.skipNextInstruction(environment);
                }
                return;
            }
            if (opName.startsWith("IFP") || opName.startsWith("INP")) {
                List<Operand> operands = resolveOperands(environment);
                if (organism.isInstructionFailed()) {
                    return;
                }
                if (operands.size() != 1) {
                    organism.instructionFailed("Invalid operand count for " + opName);
                    return;
                }
                Operand op = operands.get(0);
                if (!(op.value() instanceof int[])) {
                    organism.instructionFailed(opName + " requires a vector argument.");
                    return;
                }
                int[] vector = (int[]) op.value();
                if (!organism.isUnitVector(vector)) {
                    return;
                }
                int[] targetCoordinate = organism.getTargetCoordinate(organism.getActiveDp(), vector, environment);
                Molecule molecule = environment.getMolecule(targetCoordinate);
                int ownerId = environment.getOwnerId(targetCoordinate);
                boolean isPassable = molecule.isEmpty() || organism.isCellAccessible(ownerId);
                boolean conditionMet = opName.startsWith("IFP") ? isPassable : !isPassable;
                if (!conditionMet) {
                    organism.skipNextInstruction(environment);
                }
                return;
            }
            if (opName.startsWith("IFF") || opName.startsWith("INF")) {
                // Foreign ownership check: ownerId != 0 && ownerId != self.id
                List<Operand> operands = resolveOperands(environment);
                if (organism.isInstructionFailed()) {
                    return;
                }
                if (operands.size() != 1) {
                    organism.instructionFailed("Invalid operand count for " + opName);
                    return;
                }
                Operand op = operands.get(0);
                if (!(op.value() instanceof int[])) {
                    organism.instructionFailed(opName + " requires a vector argument.");
                    return;
                }
                int[] vector = (int[]) op.value();
                if (!organism.isUnitVector(vector)) {
                    return;
                }
                int[] targetCoordinate = organism.getTargetCoordinate(organism.getActiveDp(), vector, environment);
                int ownerId = environment.getOwnerId(targetCoordinate);
                boolean isForeign = (ownerId != 0 && ownerId != organism.getId());
                boolean conditionMet = opName.startsWith("IFF") ? isForeign : !isForeign;
                if (!conditionMet) {
                    organism.skipNextInstruction(environment);
                }
                return;
            }
            if (opName.startsWith("IFV") || opName.startsWith("INV")) {
                // Vacant ownership check: ownerId == 0
                List<Operand> operands = resolveOperands(environment);
                if (organism.isInstructionFailed()) {
                    return;
                }
                if (operands.size() != 1) {
                    organism.instructionFailed("Invalid operand count for " + opName);
                    return;
                }
                Operand op = operands.get(0);
                if (!(op.value() instanceof int[])) {
                    organism.instructionFailed(opName + " requires a vector argument.");
                    return;
                }
                int[] vector = (int[]) op.value();
                if (!organism.isUnitVector(vector)) {
                    return;
                }
                int[] targetCoordinate = organism.getTargetCoordinate(organism.getActiveDp(), vector, environment);
                int ownerId = environment.getOwnerId(targetCoordinate);
                boolean isVacant = (ownerId == 0);
                boolean conditionMet = opName.startsWith("IFV") ? isVacant : !isVacant;
                if (!conditionMet) {
                    organism.skipNextInstruction(environment);
                }
                return;
            }
            List<Operand> operands = resolveOperands(environment);
            if (organism.isInstructionFailed()) {
                return;
            }
            if (operands.size() != 2) {
                organism.instructionFailed("Invalid operand count for conditional operation.");
                return;
            }

            Operand op1 = operands.get(0);
            Operand op2 = operands.get(1);
            boolean conditionMet = false;


            if (opName.startsWith("IFT") || opName.startsWith("INT")) { // Type comparison
                int type1 = (op1.value() instanceof Integer i) ? org.evochora.runtime.model.Molecule.fromInt(i).type() : -1; // -1 for vectors
                int type2 = (op2.value() instanceof Integer i) ? Molecule.fromInt(i).type() : -1;
                if (opName.startsWith("INT")) {
                    conditionMet = (type1 != type2);
                } else {
                    conditionMet = (type1 == type2);
                }
            } else { // Value comparison
                if (op1.value() instanceof int[] v1 && op2.value() instanceof int[] v2) {
                    boolean areEqual = Arrays.equals(v1, v2);
                    conditionMet = opName.startsWith("IN") ? !areEqual : areEqual;
                } else if (op1.value() instanceof Integer i1 && op2.value() instanceof Integer i2) {
                    Molecule s1 = org.evochora.runtime.model.Molecule.fromInt(i1);
                    Molecule s2 = org.evochora.runtime.model.Molecule.fromInt(i2);
                    if (Config.STRICT_TYPING && s1.type() != s2.type()) {
                        // Condition is false if types don't match in strict mode
                    } else {
                        int val1 = s1.toScalarValue();
                        int val2 = s2.toScalarValue();
                        switch (opName) {
                            case "IFR", "IFI", "IFS" -> conditionMet = (val1 == val2);
                            case "INR", "INI", "INS" -> conditionMet = (val1 != val2);
                            case "GTR", "GTI", "GTS" -> conditionMet = (val1 > val2);
                            case "GETR", "GETI", "GETS" -> conditionMet = (val1 >= val2);
                            case "LTR", "LTI", "LTS" -> conditionMet = (val1 < val2);
                            case "LETR", "LETI", "LETS" -> conditionMet = (val1 <= val2);
                            default -> organism.instructionFailed("Unknown conditional operation: " + opName);
                        }
                    }
                } else {
                    organism.instructionFailed("Mismatched operand types for comparison.");
                }
            }

            if (!conditionMet) {
                organism.skipNextInstruction(environment);
            }

        } catch (NoSuchElementException e) {
            organism.instructionFailed("Stack underflow during conditional operation.");
            return;
        }
    }
}