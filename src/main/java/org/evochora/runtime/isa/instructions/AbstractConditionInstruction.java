package org.evochora.runtime.isa.instructions;

import org.evochora.runtime.Config;
import org.evochora.runtime.internal.services.ExecutionContext;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.LocationValue;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.ScanLineArc;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The instructions that test a condition and act on the result: the conditional skips and the
 * conditional jumps. This class evaluates the conditions; what a kind of conditional does with the
 * result is up to the class that registers it.
 * <p>
 * Every conditional is registered together with its negation, and the two are exact opposites: for
 * any pair of operands exactly one of them holds. The compiler relies on that when it inverts a
 * condition.
 * <p>
 * A test that cannot be evaluated does not hold, and its negation holds: an operand that names no
 * register, a stack without a value for it, an argument cell beyond the edge of a bounded world, a
 * cell test without a vector. The failure is booked as any failure is, with its penalty and its
 * reason, and the conditional decides in addition, so that of a conditional and its negation
 * exactly one acts on failure too. The test itself is not evaluated then. For this the conditionals
 * are executed although they failed while they were planned, which they declare when they
 * register; every other instruction stays unexecuted when it failed.
 * <p>
 * The value comparisons work on a type and a number per operand: a scalar contributes its own type
 * and value, a vector the type DATA and its Manhattan magnitude, the sum of the absolute values of
 * its components. An equality test (IF, IN) asks whether the two are the same value: it holds if
 * the types are value-compatible and the numbers are equal, and "not equal" holds otherwise, a
 * type mismatch included. Two vector operands of an equality test are compared component by
 * component instead, so that two positions count as equal only if they are the same position. An
 * order comparison (LT, GT, LET, GET and the probabilistic ones) compares the numbers alone; the
 * types play no part in it. Types are the business of the type comparisons (IFT, INT).
 * <p>
 * The probabilistic operations (PGT, PLE, PLT, PGE) replace the second value B by a uniformly
 * distributed draw U from {@code [0, B)} taken from the organism's own random source, and then
 * compare exactly as their deterministic counterparts do. A bound of zero or less yields
 * {@code U = 0}, which leaves a comparison against zero rather than a failing instruction.
 * <p>
 * The body tests (IFB, INB) ask whether the active data pointer lies within the organism's own body
 * on the line the vector names. What lying within the body on a line means is the arc of
 * {@link ScanLineArc}: the cells the organism owns and has not marked, so that what it is building
 * for a child counts as outside its body. A vector without components asks the question on every
 * axis at once.
 */
public abstract class AbstractConditionInstruction extends Instruction {

    /**
     * The condition an opcode tests and whether it is the negated form.
     *
     * @param condition the test
     * @param negated   whether the opcode holds exactly when the test does not
     */
    private record Test(Condition condition, boolean negated) {}

    /** The test of every registered conditional, keyed by full opcode ID. */
    private static final Int2ObjectOpenHashMap<Test> TEST_BY_ID = new Int2ObjectOpenHashMap<>();

    /** Maps each conditional opcode name to the name of the opcode that holds exactly when it does not. */
    private static final Map<String, String> NEGATION_BY_NAME = new HashMap<>();

    /**
     * Registers a conditional and its negation, one variant of each, with the same operands and the
     * same condition, the first one positive and the second one negated.
     *
     * @param instructionClass the class that registers and executes the pair
     * @param factory          the factory of that class
     * @param family           the family ID of that class
     * @param condition        the condition both opcodes test
     * @param op               the operation number of the positive opcode
     * @param negatedOp        the operation number of the negated opcode
     * @param index            the index of the positive opcode within the family
     * @param negatedIndex     the index of the negated opcode within the family
     * @param name             the name of the positive opcode
     * @param negatedName      the name of the negated opcode
     * @param sources          the operand sources both opcodes take
     */
    protected static void regPair(Class<? extends Instruction> instructionClass, InstructionFactory factory,
                                  int family, Condition condition, int op, int negatedOp, int index,
                                  int negatedIndex, String name, String negatedName, OperandSource... sources) {
        Instruction.registerOp(instructionClass, factory, family, op, index, name, true, sources);
        Instruction.registerOp(instructionClass, factory, family, negatedOp, negatedIndex, negatedName, true, sources);
        declareDecidesOnFailure(name);
        declareDecidesOnFailure(negatedName);
        TEST_BY_ID.put(Instruction.getInstructionIdByName(name).intValue(), new Test(condition, false));
        TEST_BY_ID.put(Instruction.getInstructionIdByName(negatedName).intValue(), new Test(condition, true));
        NEGATION_BY_NAME.put(name.toUpperCase(), negatedName.toUpperCase());
        NEGATION_BY_NAME.put(negatedName.toUpperCase(), name.toUpperCase());
    }

    /**
     * Returns the opcode that holds exactly when the given one does not, of the same kind and with
     * the same operands.
     *
     * @param opcodeName the name of a conditional opcode, in any letter case
     * @return the name of its negation, or empty if the name is not a conditional opcode
     */
    protected static Optional<String> negationByName(String opcodeName) {
        return Optional.ofNullable(NEGATION_BY_NAME.get(opcodeName.toUpperCase()));
    }

    /**
     * Constructs a conditional.
     *
     * @param organism     The organism executing the instruction.
     * @param fullOpcodeId The full opcode ID of the instruction.
     */
    protected AbstractConditionInstruction(Organism organism, int fullOpcodeId) {
        super(organism, fullOpcodeId);
    }

    /**
     * Acts on the result of the condition.
     *
     * @param holds       whether the opcode's condition, negated where it is, holds
     * @param operands    all operands of the instruction, those of the condition first
     * @param environment the environment the instruction runs in
     */
    protected abstract void act(boolean holds, List<Operand> operands, Environment environment);

    /**
     * Returns how many operands the instruction takes beyond those of its condition.
     *
     * @return the number of operands that follow the condition's
     */
    protected abstract int operandsAfterCondition();

    @Override
    public final void execute(ExecutionContext context) {
        Organism organism = context.getOrganism();
        Environment environment = context.getWorld();
        Test test = TEST_BY_ID.get(fullOpcodeId);
        Condition condition = test.condition();

        List<Operand> operands = resolveOperands(environment);
        if (operands.size() != condition.operandCount() + operandsAfterCondition()) {
            organism.instructionFailed("Invalid operand count for " + getName());
        }
        // A test that cannot be evaluated does not hold: a failure booked while the instruction
        // was planned leaves the test unevaluated, and one booked by the test itself overrides
        // what it returned.
        boolean holds = !organism.isInstructionFailed()
                && holds(condition, operands, organism, environment)
                && !organism.isInstructionFailed();
        act(test.negated() != holds, operands, environment);
    }

    /**
     * Evaluates a condition in its positive form.
     *
     * @param condition   the condition
     * @param operands    the operands, those of the condition first, their count already checked
     * @param organism    the organism the instruction runs for
     * @param environment the environment the instruction runs in
     * @return whether the condition holds; meaningless if the test has marked the instruction
     *         failed, which the caller takes as "does not hold"
     */
    private boolean holds(Condition condition, List<Operand> operands, Organism organism, Environment environment) {
        return switch (condition) {
            case PREVIOUS_FAILED -> organism.wasPreviousInstructionFailed();
            case HOLDS_POSITION -> holdsPosition(operands.get(0), organism);
            case MINE, PASSABLE, FOREIGN, VACANT, EXISTS -> cellHolds(condition, operands.get(0), organism, environment);
            case WITHIN_BODY -> {
                int[] vector = displacement(operands.get(0), organism);
                yield vector != null && isWithinOwnBody(organism, environment, vector);
            }
            case EQUAL, LESS_THAN, GREATER_THAN, TYPE_EQUAL, GREATER_THAN_DRAW, LESS_THAN_DRAW ->
                    compares(condition, operands.get(0), operands.get(1), organism);
        };
    }

    /**
     * Tells whether a location register holds a position.
     *
     * @param operand  the location register operand
     * @param organism the organism the register belongs to
     * @return whether it holds one; meaningless if the read failed
     */
    private static boolean holdsPosition(Operand operand, Organism organism) {
        Object value = organism.readOperand(operand.rawSourceId());
        if (organism.isInstructionFailed()) {
            return false;
        }
        return !LocationValue.isNone((int[]) value);
    }

    /**
     * Evaluates a condition on the cell a vector names from the active data pointer.
     *
     * @param condition   one of MINE, PASSABLE, FOREIGN, VACANT and EXISTS
     * @param operand     the vector operand
     * @param organism    the organism the instruction runs for
     * @param environment the environment the cell lies in
     * @return whether the condition holds; meaningless if the instruction has been marked failed
     */
    private boolean cellHolds(Condition condition, Operand operand, Organism organism, Environment environment) {
        int[] vector = displacement(operand, organism);
        if (vector == null) {
            return false;
        }
        int[] dp = dataPointerInsideWorld(environment);
        if (dp == null) {
            return false;
        }
        return switch (condition) {
            case MINE -> organism.isCellAccessible(environment.getOwnerIdAt(dp, vector));
            case PASSABLE -> isPassable(environment, dp, vector);
            case FOREIGN -> {
                int ownerId = environment.getOwnerIdAt(dp, vector);
                yield ownerId != 0 && ownerId != organism.getId();
            }
            case VACANT -> environment.getOwnerIdAt(dp, vector) == 0;
            // A cell beyond the edge of a bounded world does not exist. It reads as empty and
            // unowned and is not passable, which no cell inside the world combines; this is the
            // direct question, so that a program can tell the edge from a blocking molecule without
            // inferring it from two conditions. In a toroidal world every cell exists.
            case EXISTS -> environment.exists(dp, vector);
            default -> throw new IllegalStateException(condition + " is no condition on a cell");
        };
    }

    /**
     * Maps the vector operand of a conditional that tests a cell to the displacement that reaches
     * one.
     * <p>
     * The displacement is the vector itself when it already reaches a cell; anything further is
     * mapped to the nearest adjacent cell, and a vector without components stays one, so the test
     * applies to the cell the data pointer stands on. See {@link Organism#toDisplacement(int[])}.
     *
     * @param operand  the operand
     * @param organism the organism a rejection is reported to
     * @return the displacement, or {@code null} if the operand is not a vector, in which case the
     *         instruction has been marked failed
     */
    private int[] displacement(Operand operand, Organism organism) {
        if (!(operand.value() instanceof int[] vector)) {
            organism.instructionFailed(getName() + " requires a vector argument.");
            return null;
        }
        return organism.toDisplacement(vector);
    }

    /**
     * Reports whether the active data pointer lies within the organism's own body on the line the
     * vector names.
     * <p>
     * The vector names the axis the line runs along, and its sign plays no part: a line has no
     * direction. A vector without components names no line, and the question is then asked on every
     * axis, with the answer holding only if the data pointer lies within the body on all of them.
     * The first axis it lies outside on ends the examination.
     * <p>
     * What "within the body" means on one line is the arc of {@link ScanLineArc}, the stretch from
     * the first to the last unmarked cell the organism owns there.
     *
     * @param organism    the organism the data pointer and the body belong to
     * @param environment the environment the cells' owners are read from
     * @param vector      the displacement the operand was mapped to, a unit vector or the zero vector
     * @return {@code true} if the data pointer lies within the own body
     */
    private static boolean isWithinOwnBody(Organism organism, Environment environment, int[] vector) {
        int[] position = organism.getActiveDp();
        // A displacement is a unit vector or the zero vector, so the first non-zero component is
        // the only one, and it names the axis alone.
        for (int axis = 0; axis < vector.length; axis++) {
            if (vector[axis] != 0) {
                return ScanLineArc.isWithinArc(environment, position, axis, organism.getId());
            }
        }
        for (int axis = 0; axis < position.length; axis++) {
            if (!ScanLineArc.isWithinArc(environment, position, axis, organism.getId())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Evaluates a comparison of two values in its positive form.
     *
     * @param condition one of the comparisons
     * @param op1       the first operand
     * @param op2       the second operand
     * @param organism  the organism a draw is taken from
     * @return whether the comparison holds
     */
    private static boolean compares(Condition condition, Operand op1, Operand op2, Organism organism) {
        if (condition == Condition.TYPE_EQUAL) {
            int type1 = (op1.value() instanceof Integer i) ? Molecule.fromInt(i).type() : -1; // -1 for vectors
            int type2 = (op2.value() instanceof Integer i) ? Molecule.fromInt(i).type() : -1;
            return type1 == type2;
        }
        if (op1.value() instanceof Integer i1 && op2.value() instanceof Integer i2) {
            Molecule s1 = Molecule.fromInt(i1);
            Molecule s2 = Molecule.fromInt(i2);
            return condition == Condition.EQUAL
                    ? areEqual(s1.type(), s1.toScalarValue(), s2.type(), s2.toScalarValue())
                    : orders(condition, s1.toScalarValue(), s2.toScalarValue(), organism);
        }
        if (condition == Condition.EQUAL && op1.value() instanceof int[] v1 && op2.value() instanceof int[] v2) {
            return Arrays.equals(v1, v2);
        }
        // At least one vector, which enters as a DATA value, its magnitude
        return condition == Condition.EQUAL
                ? areEqual(typeOf(op1.value()), magnitudeOf(op1.value()), typeOf(op2.value()), magnitudeOf(op2.value()))
                : orders(condition, magnitudeOf(op1.value()), magnitudeOf(op2.value()), organism);
    }

    /**
     * Decides whether two values that have been reduced to a type and a number are the same value:
     * their types are value-compatible and their numbers are equal.
     *
     * @param type1   the type of the first operand
     * @param number1 the number of the first operand
     * @param type2   the type of the second operand
     * @param number2 the number of the second operand
     * @return {@code true} if the two are the same value
     */
    private static boolean areEqual(int type1, int number1, int type2, int number2) {
        return Molecule.areValueCompatible(type1, type2) && number1 == number2;
    }

    /**
     * Decides an order comparison between two numbers.
     * <p>
     * The probabilistic comparisons compare the first value against a draw below the second one
     * instead of against the second one itself.
     *
     * @param condition one of LESS_THAN, GREATER_THAN, LESS_THAN_DRAW and GREATER_THAN_DRAW
     * @param val1      the value of the first operand
     * @param val2      the value of the second operand, the bound of the draw for a probabilistic
     *                  comparison
     * @param organism  the organism the draw is taken from
     * @return {@code true} if the comparison holds
     */
    private static boolean orders(Condition condition, int val1, int val2, Organism organism) {
        return switch (condition) {
            case LESS_THAN -> val1 < val2;
            case GREATER_THAN -> val1 > val2;
            case LESS_THAN_DRAW -> val1 < drawBelow(organism, val2);
            case GREATER_THAN_DRAW -> val1 > drawBelow(organism, val2);
            default -> throw new IllegalStateException(condition + " is no order comparison");
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
     * Returns the type a comparison works on for one operand.
     * <p>
     * A scalar contributes its own type, a vector counts as DATA, the type of its magnitude.
     *
     * @param value the value of an operand, a molecule or a vector
     * @return the molecule type the comparison uses
     */
    private static int typeOf(Object value) {
        return value instanceof int[] ? Config.TYPE_DATA : Molecule.fromInt((Integer) value).type();
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
