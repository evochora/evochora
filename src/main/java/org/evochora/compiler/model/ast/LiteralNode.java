package org.evochora.compiler.model.ast;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.expression.Value;

/**
 * An AST node that represents a literal operand: a number, a typed literal such as
 * {@code DATA:42} or a vector such as {@code 3|21}, carried as one {@link Value}.
 *
 * @param value      The value of the literal.
 * @param sourceInfo The source location where this literal appeared.
 */
public record LiteralNode(
        Value value,
        SourceInfo sourceInfo
) implements OperandNode {
    // This node has no children and inherits the empty list from getChildren().
}
