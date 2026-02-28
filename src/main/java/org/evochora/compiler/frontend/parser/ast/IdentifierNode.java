package org.evochora.compiler.frontend.parser.ast;

import org.evochora.compiler.model.Token;

/**
 * An AST node that represents a generic identifier,
 * e.g., a constant name or a label used as an argument.
 *
 * @param identifierToken The token of the identifier.
 */
public record IdentifierNode(
        Token identifierToken
) implements AstNode {
    // This node has no children and inherits the empty list from getChildren().
}