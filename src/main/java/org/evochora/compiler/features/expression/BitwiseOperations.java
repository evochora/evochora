package org.evochora.compiler.features.expression;

import org.evochora.compiler.expression.IOperation;
import org.evochora.compiler.expression.IOperationGroup;
import org.evochora.compiler.expression.Level;
import org.evochora.compiler.expression.Notation;
import org.evochora.compiler.expression.OperationResult;
import org.evochora.compiler.expression.Signature;
import org.evochora.compiler.model.expression.Kind;
import org.evochora.compiler.model.expression.Value;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.LongBinaryOperator;

/**
 * The bitwise operations on numbers: {@code AND OR XOR SHL SHR} as infix operators of the
 * unordered level, which stand next to themselves only, and {@code NOT} as a prefix.
 * <p>
 * The rule for the kinds is one: numbers only. {@code SHR} shifts arithmetically, keeping the
 * sign; a shift count outside {@code 0} to {@code 63} is an error, since the compiler is exact
 * where the runtime takes the count modulo 64. A bit shifted out by {@code SHL} is lost, as in a
 * bitwise operation it should be.
 */
final class BitwiseOperations implements IOperationGroup {

    /** The widest shift a 64-bit number takes. */
    private static final long MAX_SHIFT = Long.SIZE - 1;

    @Override
    public List<IOperation> operations() {
        return List.of(
                infix("AND", (a, b) -> a & b),
                infix("OR", (a, b) -> a | b),
                infix("XOR", (a, b) -> a ^ b),
                shift("SHL", (a, b) -> a << b),
                shift("SHR", (a, b) -> a >> b),
                new DefinedOperation("NOT", Notation.PREFIX, Level.PREFIX, List.of(Signature.of(Kind.NUMBER, Kind.NUMBER)),
                        (operands, context) -> new OperationResult.Value(new Value.Number(~number(operands.get(0))))));
    }

    private static IOperation infix(String name, LongBinaryOperator operator) {
        return numbers(name, (a, b) -> new OperationResult.Value(new Value.Number(operator.applyAsLong(a, b))));
    }

    private static IOperation shift(String name, LongBinaryOperator operator) {
        return numbers(name, (a, count) -> count < 0 || count > MAX_SHIFT
                ? new OperationResult.Error("Shift count " + count + " out of range (0 to " + MAX_SHIFT + ").")
                : new OperationResult.Value(new Value.Number(operator.applyAsLong(a, count))));
    }

    /**
     * An infix operator of the unordered level under the rule of the group: numbers only.
     */
    private static IOperation numbers(String name, BiFunction<Long, Long, OperationResult> computation) {
        return new DefinedOperation(name, Notation.INFIX, Level.UNORDERED,
                List.of(Signature.of(Kind.NUMBER, Kind.NUMBER, Kind.NUMBER)),
                (operands, context) -> computation.apply(number(operands.get(0)), number(operands.get(1))));
    }

    private static long number(Value value) {
        return ((Value.Number) value).value();
    }
}
