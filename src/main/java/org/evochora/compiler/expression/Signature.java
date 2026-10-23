package org.evochora.compiler.expression;

import org.evochora.compiler.model.expression.Kind;

import java.util.List;

/**
 * One overload of an operation: the kinds of the operands it takes and the kind of its result.
 *
 * @param parameters   The kinds of the operands, in order; copied, so that the record is
 *                     immutable. At least one.
 * @param lastRepeated Whether the last parameter may occur any number of times, one or more, as
 *                     the chain {@code |} takes "number…".
 * @param result       The kind of the result.
 */
public record Signature(List<Kind> parameters, boolean lastRepeated, Kind result) {

    /**
     * Copies the parameters.
     *
     * @throws IllegalArgumentException if no parameter is given.
     */
    public Signature {
        parameters = List.copyOf(parameters);
        if (parameters.isEmpty()) {
            throw new IllegalArgumentException("A signature needs at least one parameter.");
        }
    }

    /**
     * Creates a signature whose parameters occur exactly once each.
     *
     * @param result     The kind of the result.
     * @param parameters The kinds of the operands, in order.
     * @return The signature.
     */
    public static Signature of(Kind result, Kind... parameters) {
        return new Signature(List.of(parameters), false, result);
    }

    /**
     * Tells whether operands of the given kinds fit this signature.
     *
     * @param kinds The kinds of the operands, in order.
     * @return {@code true} if every operand has the kind of its parameter, the last parameter
     *         standing for every operand from its position on when it is repeated.
     */
    public boolean accepts(List<Kind> kinds) {
        int fixed = parameters.size();
        if (lastRepeated ? kinds.size() < fixed : kinds.size() != fixed) {
            return false;
        }
        for (int i = 0; i < kinds.size(); i++) {
            if (kinds.get(i) != parameterAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the kind the operand at a position has to have.
     *
     * @param index The position of the operand, counting from 0.
     * @return The kind of its parameter; past the last parameter the last one's when it is
     *         repeated, otherwise {@code null}.
     */
    public Kind parameterAt(int index) {
        if (index < parameters.size()) {
            return parameters.get(index);
        }
        return lastRepeated ? parameters.getLast() : null;
    }
}
