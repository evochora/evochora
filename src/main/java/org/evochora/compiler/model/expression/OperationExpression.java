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
 * @param function   Whether the operation is a function, written {@code NAME(a, b)}; a word
 *                   operator such as {@code NOT} or {@code AND} is not.
 * @param operands   The operands, in order; copied, so that the record is immutable.
 * @param sourceInfo The position of the node's first token: the operator's for a prefix, the
 *                   first operand's for an infix, an index or a chain, the name's for a function.
 * @param end        The position right after the last token read for the node: the closing
 *                   {@code )} or {@code ]} for a function or an index, the end of the last operand
 *                   for any other operation, or the closing parenthesis around the node
 *                   (see {@link Expression#end()}).
 */
public record OperationExpression(String operation, boolean function, List<Expression> operands, SourceInfo sourceInfo,
                                  SourceInfo end) implements Expression {

    /** The name of the index access, {@code a[i]}, the one operation the parser names itself. */
    public static final String INDEX = "[]";

    /** The name of the vector of parts, {@code a|b|c}, which the tokenizer reads as its own token. */
    public static final String VECTOR = "|";

    /**
     * The name of the typing operator, {@code T:n}, which the preprocessor reads as a label colon
     * at the start of a statement only and so may share with it.
     */
    public static final String TYPING = ":";

    /**
     * Copies the operands.
     */
    public OperationExpression {
        operands = List.copyOf(operands);
    }

    /**
     * {@inheritDoc}
     * <p>
     * The form follows from the name of the operation, the number of its operands and whether it
     * is a function: the index access as {@code V[0]}, the typing as {@code DATA:(W-1)}, a
     * function as {@code NUMBER(A)}, a prefix as {@code (-A)} or {@code (NOT A)}, an infix or a
     * chain as {@code (W-1)}, {@code (A AND B)} or {@code ((W-1)|(H-1))}. A prefix, an infix and a
     * chain stand in parentheses of their own, as a computation of them has to in the source;
     * inside the parenthesis of a function or the bracket of an index, which encloses it already,
     * an argument stands without them: {@code NUMBER(A+1)}, {@code V[A+1]}. A literal vector or
     * position that an operation takes stands in parentheses, so that its {@code |} binds no more
     * loosely, {@code (P+(0|1))}; so does a negative number that a prefix or an infix takes, so
     * that the minus sign follows no operator, {@code (3-(-2))}. A chain writes a prefix and a
     * negative number among its parts without parentheses, as a vector is written,
     * {@code (-1|-2)}, {@code (-A|B)}.
     */
    @Override
    public String text() {
        if (function) {
            return operation + "(" + operands.stream().map(OperationExpression::argument).collect(Collectors.joining(", ")) + ")";
        }
        if (INDEX.equals(operation) && operands.size() == 2) {
            return grouped(operands.get(0)) + "[" + argument(operands.get(1)) + "]";
        }
        if (TYPING.equals(operation) && operands.size() == 2) {
            return operands.get(0).text() + TYPING + operands.get(1).text();
        }
        return "(" + inner() + ")";
    }

    /**
     * The text of a prefix, an infix or a chain without its parentheses; a word operator stands
     * apart from its operands.
     */
    private String inner() {
        String operator = Character.isLetter(operation.charAt(0)) ? " " + operation + " " : operation;
        if (operands.size() == 1) {
            return operator.stripLeading() + grouped(operands.getFirst());
        }
        if (VECTOR.equals(operation)) {
            return operands.stream().map(OperationExpression::part).collect(Collectors.joining(operator));
        }
        return operands.stream().map(OperationExpression::grouped).collect(Collectors.joining(operator));
    }

    /**
     * The text of a part of a chain: a prefix and a negative number stand without parentheses,
     * since a prefix binds more tightly than the chain, so that a vector reads as it is written,
     * {@code -1|-2}.
     */
    private static String part(Expression operand) {
        if (operand instanceof OperationExpression operation && operation.enclosed() && operation.operands().size() == 1) {
            return operation.inner();
        }
        return negative(operand) ? operand.text() : grouped(operand);
    }

    private static String argument(Expression operand) {
        return operand instanceof OperationExpression operation && operation.enclosed()
                ? operation.inner()
                : operand.text();
    }

    private boolean enclosed() {
        return !function && !INDEX.equals(operation) && !TYPING.equals(operation);
    }

    private static String grouped(Expression operand) {
        return negative(operand) || operand instanceof LiteralExpression literal
                && (literal.value() instanceof Value.Vector || literal.value() instanceof Value.Position)
                ? "(" + operand.text() + ")"
                : operand.text();
    }

    private static boolean negative(Expression operand) {
        return operand instanceof LiteralExpression literal && literal.value() instanceof Value.Number number
                && number.value() < 0;
    }
}
