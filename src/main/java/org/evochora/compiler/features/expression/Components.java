package org.evochora.compiler.features.expression;

import org.evochora.compiler.expression.OperationResult;
import org.evochora.compiler.model.expression.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.LongBinaryOperator;
import java.util.function.LongUnaryOperator;

/**
 * The arithmetic the groups share: numbers combined exactly, and vectors and positions combined
 * component by component, with the messages for what cannot be computed.
 */
final class Components {

    /** The reason a division by zero gives. */
    static final String DIVISION_BY_ZERO = "Division by zero.";

    /** The reason a result beyond 64 bits gives. */
    static final String OVERFLOW = "Integer overflow.";

    private Components() {
    }

    /**
     * Combines two numbers, exactly.
     *
     * @param left     The left number.
     * @param right    The right number.
     * @param operator The combination, throwing {@link ArithmeticException} on overflow.
     * @param divides  Whether the right number divides, so that zero is an error.
     * @param make     Makes the value of the result from the number.
     * @return The number, or the reason there is none.
     */
    static OperationResult number(long left, long right, LongBinaryOperator operator, boolean divides,
                                  Function<Long, Value> make) {
        if (divides && right == 0) {
            return new OperationResult.Error(DIVISION_BY_ZERO);
        }
        try {
            return new OperationResult.Value(make.apply(operator.applyAsLong(left, right)));
        } catch (ArithmeticException overflow) {
            return new OperationResult.Error(OVERFLOW);
        }
    }

    /**
     * Combines two lists of components of equal length, component by component.
     *
     * @param left     The left components; their number is the one the right has to have.
     * @param right    The right components.
     * @param operator The combination, throwing {@link ArithmeticException} on overflow.
     * @param make     Makes the value of the result from its components.
     * @return The value, or the reason there is none.
     */
    static OperationResult pairwise(List<Long> left, List<Long> right, LongBinaryOperator operator,
                                    Function<List<Long>, Value> make) {
        if (left.size() != right.size()) {
            return new OperationResult.Error(expectedComponents(left.size()));
        }
        List<Long> result = new ArrayList<>(left.size());
        try {
            for (int i = 0; i < left.size(); i++) {
                result.add(operator.applyAsLong(left.get(i), right.get(i)));
            }
        } catch (ArithmeticException overflow) {
            return new OperationResult.Error(OVERFLOW);
        }
        return new OperationResult.Value(make.apply(result));
    }

    /**
     * Applies an operation to every component.
     *
     * @param components The components.
     * @param operator   The operation, throwing {@link ArithmeticException} on overflow.
     * @param make       Makes the value of the result from its components.
     * @return The value, or the reason there is none.
     */
    static OperationResult each(List<Long> components, LongUnaryOperator operator, Function<List<Long>, Value> make) {
        List<Long> result = new ArrayList<>(components.size());
        try {
            for (long component : components) {
                result.add(operator.applyAsLong(component));
            }
        } catch (ArithmeticException overflow) {
            return new OperationResult.Error(OVERFLOW);
        }
        return new OperationResult.Value(make.apply(result));
    }

    /**
     * Picks one component, counting from 0.
     *
     * @param components The components.
     * @param index      The index.
     * @param what       What the components belong to, for the message: "a vector", "a position".
     * @return The component, or the reason there is none.
     */
    static OperationResult component(List<Long> components, long index, String what) {
        if (index < 0 || index >= components.size()) {
            return new OperationResult.Error("Index " + index + " out of bounds for " + what + " of length "
                    + components.size() + ".");
        }
        return new OperationResult.Value(new Value.Number(components.get((int) index)));
    }

    /**
     * The reason a vector of the wrong length gives.
     *
     * @param count The number of components it should have.
     * @return The sentence.
     */
    static String expectedComponents(int count) {
        return "Expected a vector with " + count + " components.";
    }
}
