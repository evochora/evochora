package org.evochora.compiler.model.ast;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.expression.Expression;

/**
 * An AST node that represents a computation in operand position: a parenthesis such as
 * {@code (WIDTH-1)}, a call such as {@code MIN(A, 4)}, an index such as {@code V[0]}, a typed
 * literal whose value is one of these, such as {@code DATA:(WIDTH-1)}, or the cursor {@code @}.
 * <p>
 * Phase 4 evaluates the computation and records its result in the symbol table by the node's
 * identity; phase 6 replaces the node by that result, a literal or the tree a later phase
 * computes.
 *
 * @param expression The computation.
 * @param sourceInfo The position of the operand's first token.
 * @param end        The position right after the operand's last token.
 */
public record ExpressionNode(
        Expression expression,
        SourceInfo sourceInfo,
        SourceInfo end
) implements OperandNode {
    // This node has no children and inherits the empty list from getChildren().
}
