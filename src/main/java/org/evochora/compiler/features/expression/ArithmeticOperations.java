package org.evochora.compiler.features.expression;

import org.evochora.compiler.expression.IEvaluationContext;
import org.evochora.compiler.expression.IOperation;
import org.evochora.compiler.expression.IOperationGroup;
import org.evochora.compiler.expression.Level;
import org.evochora.compiler.expression.Notation;
import org.evochora.compiler.expression.OperationResult;
import org.evochora.compiler.expression.Signature;
import org.evochora.compiler.model.expression.Kind;
import org.evochora.compiler.model.expression.Value;

import java.util.List;
import java.util.function.LongBinaryOperator;

/**
 * The arithmetic on numbers and typed values: {@code + - * /} and {@code MOD} as infix
 * operators, {@code -} as the sign.
 * <p>
 * Every operator follows one rule for the kinds: a number with a number is a number; a typed
 * value with a number keeps its type, so {@code UNIT * 2} is {@code ENERGY:100} for a constant
 * {@code UNIT} of {@code ENERGY:50}; two typed values combine only when their types are equal. A
 * molecule type never takes part. Every result is exact: a division by zero and a result beyond
 * 64 bits are errors, and a division rounds toward zero, as the runtime does.
 */
final class ArithmeticOperations implements IOperationGroup {

    @Override
    public List<IOperation> operations() {
        return List.of(
                infix("+", Level.ADDITIVE, Math::addExact, false),
                infix("-", Level.ADDITIVE, Math::subtractExact, false),
                infix("*", Level.MULTIPLICATIVE, Math::multiplyExact, false),
                infix("/", Level.MULTIPLICATIVE, Math::divideExact, true),
                infix("MOD", Level.MULTIPLICATIVE, (a, b) -> a % b, true),
                sign());
    }

    /**
     * An infix operator under the rule of the group, as three signatures.
     */
    private static IOperation infix(String name, Level level, LongBinaryOperator operator, boolean divides) {
        return new DefinedOperation(name, Notation.INFIX, level,
                List.of(Signature.of(Kind.NUMBER, Kind.NUMBER, Kind.NUMBER),
                        Signature.of(Kind.TYPED, Kind.TYPED, Kind.NUMBER),
                        Signature.of(Kind.TYPED, Kind.TYPED, Kind.TYPED)),
                (operands, context) -> combine(operands.get(0), operands.get(1), operator, divides));
    }

    private static OperationResult combine(Value left, Value right, LongBinaryOperator operator, boolean divides) {
        return switch (left) {
            case Value.Number a when right instanceof Value.Number b ->
                    Components.number(a.value(), b.value(), operator, divides, Value.Number::new);
            case Value.Typed a when right instanceof Value.Number b ->
                    Components.number(a.number(), b.value(), operator, divides, n -> new Value.Typed(a.type(), n));
            case Value.Typed a when right instanceof Value.Typed b -> a.type().equalsIgnoreCase(b.type())
                    ? Components.number(a.number(), b.number(), operator, divides, n -> new Value.Typed(a.type(), n))
                    : new OperationResult.Error("Type mismatch: " + a.type() + " and " + b.type() + ".");
            default -> throw new IllegalStateException("No signature of the arithmetic takes " + left + " and " + right);
        };
    }

    /**
     * The sign: a number negated, a typed value negated in its type.
     */
    private static IOperation sign() {
        return new DefinedOperation("-", Notation.PREFIX, Level.PREFIX,
                List.of(Signature.of(Kind.NUMBER, Kind.NUMBER), Signature.of(Kind.TYPED, Kind.TYPED)),
                ArithmeticOperations::negate);
    }

    private static OperationResult negate(List<Value> operands, IEvaluationContext context) {
        return switch (operands.get(0)) {
            case Value.Number a -> Components.number(0, a.value(), Math::subtractExact, false, Value.Number::new);
            case Value.Typed a -> Components.number(0, a.number(), Math::subtractExact, false, n -> new Value.Typed(a.type(), n));
            default -> throw new IllegalStateException("No signature of the sign takes " + operands.get(0));
        };
    }
}
