package org.evochora.runtime.isa.instructions;

import org.evochora.runtime.Config;
import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.isa.Instruction;
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
        regPair(0, 1, 0, 1, "IFR", "INR", REGISTER, REGISTER);
        regPair(0, 1, 2, 3, "IFI", "INI", REGISTER, IMMEDIATE);
        regPair(0, 1, 4, 5, "IFS", "INS", STACK, STACK);
        // Operations 2 and 5: less than / greater than or equal
        regPair(2, 5, 6, 7, "LTR", "GETR", REGISTER, REGISTER);
        regPair(2, 5, 8, 9, "LTI", "GETI", REGISTER, IMMEDIATE);
        regPair(2, 5, 10, 11, "LTS", "GETS", STACK, STACK);
        // Operations 3 and 4: greater than / less than or equal
        regPair(3, 4, 12, 13, "GTR", "LETR", REGISTER, REGISTER);
        regPair(3, 4, 14, 15, "GTI", "LETI", REGISTER, IMMEDIATE);
        regPair(3, 4, 16, 17, "GTS", "LETS", STACK, STACK);
        // Operations 6 and 7: true (non-zero) / not true (zero)
        regPair(6, 7, 18, 19, "IFTR", "INTR", REGISTER, REGISTER);
        regPair(6, 7, 20, 21, "IFTI", "INTI", REGISTER, IMMEDIATE);
        regPair(6, 7, 22, 23, "IFTS", "INTS", STACK, STACK);
        // Operations 8 and 9: mine / not mine (ownership check)
        regPair(8, 9, 24, 25, "IFMR", "INMR", REGISTER);
        regPair(8, 9, 26, 27, "IFMI", "INMI", VECTOR);  // Note: uses VECTOR operand despite "I" suffix
        regPair(8, 9, 28, 29, "IFMS", "INMS", STACK);
        // Operations 10 and 11: passable / not passable
        regPair(10, 11, 30, 31, "IFPR", "INPR", REGISTER);
        regPair(10, 11, 32, 33, "IFPI", "INPI", VECTOR);  // Note: uses VECTOR operand despite "I" suffix
        regPair(10, 11, 34, 35, "IFPS", "INPS", STACK);
        // Operations 12 and 13: foreign ownership / not foreign ownership
        regPair(12, 13, 36, 37, "IFFR", "INFR", REGISTER);
        regPair(12, 13, 38, 39, "IFFI", "INFI", VECTOR);  // Note: uses VECTOR operand despite "I" suffix
        regPair(12, 13, 40, 41, "IFFS", "INFS", STACK);
        // Operations 14 and 15: vacant ownership / not vacant ownership
        regPair(14, 15, 42, 43, "IFVR", "INVR", REGISTER);
        regPair(14, 15, 44, 45, "IFVI", "INVI", VECTOR);  // Note: uses VECTOR operand despite "I" suffix
        regPair(14, 15, 46, 47, "IFVS", "INVS", STACK);
        // Operations 16 and 17: previous instruction failed / did not fail
        regPair(16, 17, 48, 49, "IFER", "INER");
        // Operations 18 and 19: location register holds a position / holds none
        regPair(18, 19, 50, 51, "IFSL", "INSL", LOCATION_REGISTER);
        // Operations 20 and 21: greater than a draw below the second value / less than or equal to it
        regPair(20, 21, 52, 53, "PGTR", "PLER", REGISTER, REGISTER);
        regPair(20, 21, 54, 55, "PGTI", "PLEI", REGISTER, IMMEDIATE);
        regPair(20, 21, 56, 57, "PGTS", "PLES", STACK, STACK);
        // Operations 22 and 23: less than a draw below the second value / greater than or equal to it
        regPair(22, 23, 58, 59, "PLTR", "PGER", REGISTER, REGISTER);
        regPair(22, 23, 60, 61, "PLTI", "PGEI", REGISTER, IMMEDIATE);
        regPair(22, 23, 62, 63, "PLTS", "PGES", STACK, STACK);
    }

    /**
     * Registers a conditional and its negation, one variant of each, with the same operands.
     */
    private static void regPair(int op, int negatedOp, int index, int negatedIndex,
                                String name, String negatedName, OperandSource... sources) {
        reg(op, index, name, sources);
        reg(negatedOp, negatedIndex, negatedName, sources);
        NEGATION_BY_NAME.put(name.toUpperCase(), negatedName.toUpperCase());
        NEGATION_BY_NAME.put(negatedName.toUpperCase(), name.toUpperCase());
    }

    private static void reg(int op, int index, String name, OperandSource... sources) {
        Instruction.registerOp(ConditionalInstruction.class, ConditionalInstruction::new, family, op, index, name, true, sources);
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
                } else if (comparableWithMagnitude(op1.value()) && comparableWithMagnitude(op2.value())) {
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
     * Tells whether an operand may take part in a comparison against a vector's magnitude.
     * <p>
     * The magnitude is a DATA value, so under strict typing a scalar on the other side has to be
     * value-compatible with DATA, as it would have to be against a DATA scalar; a vector operand
     * always may.
     *
     * @param value the value of an operand, a molecule or a vector
     * @return {@code true} if the operand can be compared with a magnitude
     */
    private static boolean comparableWithMagnitude(Object value) {
        return !(value instanceof Integer scalar)
                || !Config.STRICT_TYPING
                || Molecule.areValueCompatible(Config.TYPE_DATA, Molecule.fromInt(scalar).type());
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