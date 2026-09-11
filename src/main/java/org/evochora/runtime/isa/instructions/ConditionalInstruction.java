package org.evochora.runtime.isa.instructions;

import org.evochora.runtime.Config;
import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.Variant;
import org.evochora.runtime.model.LocationValue;
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
 * <p>
 * The value comparisons work on a scalar per operand: a scalar operand contributes its own value,
 * a vector operand its Manhattan magnitude, the sum of the absolute values of its components. Two
 * vector operands of an equality test are compared component by component instead, so that two
 * positions count as equal only if they are the same position.
 * <p>
 * The probabilistic operations (PGT, PLE, PLT, PGE) replace the second value B by a uniformly
 * distributed draw U from {@code [0, B)} taken from the organism's own random source, and then
 * compare exactly as their deterministic counterparts do. A bound of zero or less yields
 * {@code U = 0}, which leaves a comparison against zero rather than a failing instruction.
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
        // Operations 18 and 19: location register holds a position / holds none
        regPair(18, 19, Variant.L, "IFSL", "INSL", LOCATION_REGISTER);
        // Operations 20 and 21: greater than a draw below the second value / less than or equal to it
        regPair(20, 21, Variant.RR, "PGTR", "PLER", REGISTER, REGISTER);
        regPair(20, 21, Variant.RI, "PGTI", "PLEI", REGISTER, IMMEDIATE);
        regPair(20, 21, Variant.SS, "PGTS", "PLES", STACK, STACK);
        // Operations 22 and 23: less than a draw below the second value / greater than or equal to it
        regPair(22, 23, Variant.RR, "PLTR", "PGER", REGISTER, REGISTER);
        regPair(22, 23, Variant.RI, "PLTI", "PGEI", REGISTER, IMMEDIATE);
        regPair(22, 23, Variant.SS, "PLTS", "PGES", STACK, STACK);
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
            // Matched by full name ahead of everything below, which ends in a path that expects
            // two operands and reads a name beginning with "IN" as a negated comparison — INSL
            // among them.
            if ("IFSL".equals(opName) || "INSL".equals(opName)) {
                List<Operand> operands = resolveOperands(environment);
                if (organism.isInstructionFailed()) {
                    return;
                }
                if (operands.size() != 1) {
                    organism.instructionFailed("Invalid operand count for " + opName);
                    return;
                }
                Object value = organism.readOperand(operands.get(0).rawSourceId());
                if (organism.isInstructionFailed()) {
                    return;
                }
                boolean holdsPosition = !LocationValue.isNone((int[]) value);
                boolean conditionMet = "IFSL".equals(opName) ? holdsPosition : !holdsPosition;
                if (!conditionMet) {
                    organism.skipNextInstruction(environment);
                }
                return;
            }
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
                int[] vector = organism.toDisplacement((int[]) op.value());
                if (vector == null) {
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
                int[] vector = organism.toDisplacement((int[]) op.value());
                if (vector == null) {
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
                int[] vector = organism.toDisplacement((int[]) op.value());
                if (vector == null) {
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
                int[] vector = organism.toDisplacement((int[]) op.value());
                if (vector == null) {
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
                if (op1.value() instanceof Integer i1 && op2.value() instanceof Integer i2) {
                    Molecule s1 = org.evochora.runtime.model.Molecule.fromInt(i1);
                    Molecule s2 = org.evochora.runtime.model.Molecule.fromInt(i2);
                    if (Config.STRICT_TYPING && !Molecule.areValueCompatible(s1.type(), s2.type())) {
                        // Condition is false if the types cannot be compared by value in strict mode
                    } else {
                        conditionMet = compare(opName, s1.toScalarValue(), s2.toScalarValue(), organism);
                    }
                } else if (isEqualityComparison(opName)
                        && op1.value() instanceof int[] v1 && op2.value() instanceof int[] v2) {
                    boolean areEqual = Arrays.equals(v1, v2);
                    conditionMet = opName.startsWith("IN") ? !areEqual : areEqual;
                } else {
                    conditionMet = compare(opName, magnitudeOf(op1.value()), magnitudeOf(op2.value()), organism);
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

    /**
     * Tells the equality comparisons of the value family from the order comparisons.
     *
     * @param opName the name of a value-comparing conditional opcode
     * @return {@code true} for the operations that ask whether two values are the same
     */
    private static boolean isEqualityComparison(String opName) {
        return opName.startsWith("IF") || opName.startsWith("IN");
    }

    /**
     * Decides a value comparison between two scalars.
     * <p>
     * The probabilistic operations compare the first value against a draw below the second one
     * instead of against the second one itself; every other operation is deterministic.
     *
     * @param opName   the name of the conditional opcode
     * @param val1     the value of the first operand
     * @param val2     the value of the second operand, the bound of the draw for a probabilistic
     *                 operation
     * @param organism the organism the draw is taken from and a failure is reported to
     * @return {@code true} if the condition holds
     */
    private static boolean compare(String opName, int val1, int val2, Organism organism) {
        return switch (opName) {
            case "IFR", "IFI", "IFS" -> val1 == val2;
            case "INR", "INI", "INS" -> val1 != val2;
            case "GTR", "GTI", "GTS" -> val1 > val2;
            case "GETR", "GETI", "GETS" -> val1 >= val2;
            case "LTR", "LTI", "LTS" -> val1 < val2;
            case "LETR", "LETI", "LETS" -> val1 <= val2;
            case "PGTR", "PGTI", "PGTS" -> val1 > drawBelow(organism, val2);
            case "PLER", "PLEI", "PLES" -> val1 <= drawBelow(organism, val2);
            case "PLTR", "PLTI", "PLTS" -> val1 < drawBelow(organism, val2);
            case "PGER", "PGEI", "PGES" -> val1 >= drawBelow(organism, val2);
            default -> {
                organism.instructionFailed("Unknown conditional operation: " + opName);
                yield false;
            }
        };
    }

    /**
     * Draws the value a probabilistic conditional compares against.
     *
     * @param organism the organism whose random source the draw comes from
     * @param bound    the exclusive upper bound of the draw
     * @return a uniformly distributed value in {@code [0, bound)}, and 0 for a bound of zero or less
     */
    private static int drawBelow(Organism organism, int bound) {
        return bound > 0 ? organism.getRandom().nextInt(bound) : 0;
    }

    /**
     * Returns the value a comparison works on for one operand.
     * <p>
     * A scalar contributes its own value, a vector the Manhattan magnitude of its components, so
     * a location value that holds no position contributes 0.
     *
     * @param value the value of an operand, a molecule or a vector
     * @return the scalar the comparison uses
     */
    private static int magnitudeOf(Object value) {
        if (value instanceof int[] vector) {
            int magnitude = 0;
            for (int component : vector) {
                magnitude += Math.abs(component);
            }
            return magnitude;
        }
        return Molecule.fromInt((Integer) value).toScalarValue();
    }
}