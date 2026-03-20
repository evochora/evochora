package org.evochora.compiler.model.ast;

import org.evochora.compiler.api.SourceInfo;

/**
 * An AST node that represents a generic identifier,
 * e.g., a constant name or a label used as an argument.
 *
 * @param text The text of the identifier.
 * @param sourceInfo The source location where this identifier appeared.
 */
public record IdentifierNode(
        String text,
        SourceInfo sourceInfo
) implements AstNode, ISourceLocatable {
    // This node has no children and inherits the empty list from getChildren().
}
