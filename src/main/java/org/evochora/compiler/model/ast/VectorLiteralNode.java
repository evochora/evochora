package org.evochora.compiler.model.ast;

import org.evochora.compiler.api.SourceInfo;

import java.util.List;

/**
 * An AST node that represents a vector literal, e.g., "3|21".
 *
 * @param values The integer values of the vector components.
 * @param sourceInfo The source location where this literal appeared.
 */
public record VectorLiteralNode(
        List<Integer> values,
        SourceInfo sourceInfo
) implements AstNode, ISourceLocatable {
    // This node has no children and inherits the empty list from getChildren().
}
