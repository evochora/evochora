package org.evochora.compiler.features.expression;

import org.evochora.compiler.expression.IOperation;
import org.evochora.compiler.expression.IOperationGroup;
import org.evochora.compiler.expression.Level;
import org.evochora.compiler.expression.Notation;
import org.evochora.compiler.expression.OperationRegistry;
import org.evochora.compiler.expression.OperationResult;
import org.evochora.compiler.expression.Signature;
import org.evochora.compiler.model.expression.Kind;
import org.evochora.compiler.model.expression.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongBinaryOperator;

/**
 * The operations on vectors: the chain {@code a|b|c}, which makes a vector of numbers; the index
 * {@code v[i]}, a component counted from 0; a vector added to or subtracted from a vector of the
 * same length; a vector multiplied by a number from either side and divided by a number; and the
 * sign of every component.
 * <p>
 * The rule for the kinds: a vector meets a vector of its own length, or a number in a
 * multiplication or division, and nothing else; the parts of a chain are numbers, so a vector
 * constant in a chain is an error, not a longer vector.
 */
final class VectorOperations implements IOperationGroup {

    @Override
    public List<IOperation> operations() {
        return List.of(
                new DefinedOperation(OperationRegistry.VECTOR, Notation.CHAIN, Level.CHAIN,
                        List.of(new Signature(List.of(Kind.NUMBER), true, Kind.VECTOR)),
                        (operands, context) -> {
                            List<Long> components = new ArrayList<>(operands.size());
                            operands.forEach(operand -> components.add(((Value.Number) operand).value()));
                            return new OperationResult.Value(new Value.Vector(components));
                        }),
                new DefinedOperation(OperationRegistry.INDEX, Notation.INFIX, Level.INDEX,
                        List.of(Signature.of(Kind.NUMBER, Kind.VECTOR, Kind.NUMBER)),
                        (operands, context) -> Components.component(vector(operands.get(0)), number(operands.get(1)), "a vector")),
                pairwise("+", Math::addExact),
                pairwise("-", Math::subtractExact),
                new DefinedOperation("*", Notation.INFIX, Level.MULTIPLICATIVE,
                        List.of(Signature.of(Kind.VECTOR, Kind.VECTOR, Kind.NUMBER), Signature.of(Kind.VECTOR, Kind.NUMBER, Kind.VECTOR)),
                        (operands, context) -> operands.get(0) instanceof Value.Vector v
                                ? Components.each(v.components(), c -> Math.multiplyExact(c, number(operands.get(1))), Value.Vector::new)
                                : Components.each(vector(operands.get(1)), c -> Math.multiplyExact(number(operands.get(0)), c), Value.Vector::new)),
                new DefinedOperation("/", Notation.INFIX, Level.MULTIPLICATIVE,
                        List.of(Signature.of(Kind.VECTOR, Kind.VECTOR, Kind.NUMBER)),
                        (operands, context) -> number(operands.get(1)) == 0
                                ? new OperationResult.Error(Components.DIVISION_BY_ZERO)
                                : Components.each(vector(operands.get(0)), c -> Math.divideExact(c, number(operands.get(1))), Value.Vector::new)),
                new DefinedOperation("-", Notation.PREFIX, Level.PREFIX,
                        List.of(Signature.of(Kind.VECTOR, Kind.VECTOR)),
                        (operands, context) -> Components.each(vector(operands.get(0)), Math::negateExact, Value.Vector::new)));
    }

    /**
     * A vector with a vector of the same length, component by component.
     */
    private static IOperation pairwise(String name, LongBinaryOperator operator) {
        return new DefinedOperation(name, Notation.INFIX, Level.ADDITIVE,
                List.of(Signature.of(Kind.VECTOR, Kind.VECTOR, Kind.VECTOR)),
                (operands, context) -> Components.pairwise(vector(operands.get(0)), vector(operands.get(1)), operator, Value.Vector::new));
    }

    private static List<Long> vector(Value value) {
        return ((Value.Vector) value).components();
    }

    private static long number(Value value) {
        return ((Value.Number) value).value();
    }
}
