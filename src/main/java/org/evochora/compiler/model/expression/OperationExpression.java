package org.evochora.compiler.model.expression;

import org.evochora.compiler.api.SourceInfo;

import java.util.List;

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

    /**
     * Copies the operands.
     */
    public OperationExpression {
        operands = List.copyOf(operands);
    }
}
