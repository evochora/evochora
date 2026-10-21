package org.evochora.compiler.model.expression;

import org.evochora.compiler.api.SourceInfo;

import java.util.List;
import java.util.stream.Collectors;

/**
 * An operation applied to its operands, such as {@code A + 1}, {@code -A}, {@code MIN(A, 4)},
 * {@code V[0]} or the chain {@code 1|2|3}.
 *
 * @param operation  The name the operation registry knows the operation under: an operator by its
 *                   text ({@code +}, {@code |}), the index access as {@code []}, a word operator or
 *                   a function by its upper-case word.
 * @param operands   The operands, in order; copied, so that the record is immutable.
 * @param sourceInfo The position of the node's first token: the operator's for a prefix, the
 *                   first operand's for an infix, an index or a chain, the name's for a call.
 */
public record OperationExpression(String operation, List<Expression> operands, SourceInfo sourceInfo)
        implements Expression {

    /** The name of the index access, {@code a[i]}. */
    private static final String INDEX = "[]";

    /** The name of the typing operator, {@code T:n}. */
    private static final String TYPING = ":";

    /**
     * Copies the operands.
     */
    public OperationExpression {
        operands = List.copyOf(operands);
    }

    /**
     * {@inheritDoc}
     * <p>
     * The tree does not say how an operation was written, so the form follows from its name and
     * the number of its operands: the index access as {@code V[0]}, the typing as
     * {@code DATA:(W-1)}, an operation named by a word as a call {@code MIN(A, 4)} (a prefix word
     * reads the same way, {@code NOT(A)}), a symbol with one operand as a prefix {@code -A}, and a
     * symbol with more as its infix or chain in parentheses, {@code (WIDTH-1)},
     * {@code ((W-1)|(H-1))}.
     */
    @Override
    public String text() {
        if (INDEX.equals(operation) && operands.size() == 2) {
            return operands.get(0).text() + "[" + operands.get(1).text() + "]";
        }
        if (TYPING.equals(operation) && operands.size() == 2) {
            return operands.get(0).text() + TYPING + operands.get(1).text();
        }
        if (!operation.isEmpty() && Character.isLetter(operation.charAt(0))) {
            return operation + "(" + joined(", ") + ")";
        }
        if (operands.size() == 1) {
            return operation + operands.getFirst().text();
        }
        return "(" + joined(operation) + ")";
    }

    private String joined(String separator) {
        return operands.stream().map(Expression::text).collect(Collectors.joining(separator));
    }
}
