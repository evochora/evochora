package org.evochora.compiler.model.ast;

import org.evochora.compiler.api.SourceInfo;

/**
 * An AST node that represents a numeric literal.
 *
 * @param value The integer value of the literal.
 * @param sourceInfo The source location where this literal appeared.
 */
public record NumberLiteralNode(
        int value,
        SourceInfo sourceInfo
) implements AstNode, ISourceLocatable {

    // This node has no children and inherits the empty list from getChildren().
}
