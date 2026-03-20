package org.evochora.compiler.model.ast;

import org.evochora.compiler.api.SourceInfo;

/**
 * An AST node that represents a typed literal, e.g., "DATA:42".
 *
 * @param typeName The name of the type (e.g., "DATA").
 * @param value The integer value of the literal.
 * @param sourceInfo The source location where this literal appeared.
 */
public record TypedLiteralNode(
        String typeName,
        int value,
        SourceInfo sourceInfo
) implements AstNode, ISourceLocatable {
    // This node has no children and inherits the empty list from getChildren().
}
