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
 * @param end        The position right after the last token read for the node: the closing
 *                   {@code )} or {@code ]} for a call or an index, the end of the last operand
 *                   for any other operation, or the closing parenthesis around the node
 *                   (see {@link Expression#end()}).
 */
public record OperationExpression(String operation, List<Expression> operands, SourceInfo sourceInfo, SourceInfo end)
        implements Expression {

    /** The name of the index access, {@code a[i]}. */
    private static final String INDEX = "[]";

    /** The name of the chain that makes a vector, {@code 1|2}. */
    private static final String CHAIN = "|";

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
     * {@code ((W-1)|(H-1))}. A literal vector or position in front of an index, behind a prefix or
     * in an infix or chain stands in parentheses, {@code (P+(0|1))}, since written bare its
     * {@code |} would bind more loosely than the operation around it. A chain that is an argument
     * of a call needs no parentheses of its own, {@code POSITION(1|2)}.
     */
    @Override
    public String text() {
        if (INDEX.equals(operation) && operands.size() == 2) {
            return grouped(operands.get(0)) + "[" + operands.get(1).text() + "]";
        }
        if (TYPING.equals(operation) && operands.size() == 2) {
            return operands.get(0).text() + TYPING + operands.get(1).text();
        }
        if (!operation.isEmpty() && Character.isLetter(operation.charAt(0))) {
            return operation + "(" + operands.stream().map(OperationExpression::argument).collect(Collectors.joining(", ")) + ")";
        }
        if (operands.size() == 1) {
            return operation + grouped(operands.getFirst());
        }
        return "(" + operands.stream().map(OperationExpression::grouped).collect(Collectors.joining(operation)) + ")";
    }

    private static String argument(Expression operand) {
        return operand instanceof OperationExpression chain && CHAIN.equals(chain.operation())
                ? chain.operands().stream().map(OperationExpression::grouped).collect(Collectors.joining(CHAIN))
                : operand.text();
    }

    private static String grouped(Expression operand) {
        return operand instanceof LiteralExpression literal
                && (literal.value() instanceof Value.Vector || literal.value() instanceof Value.Position)
                ? "(" + operand.text() + ")"
                : operand.text();
    }
}
