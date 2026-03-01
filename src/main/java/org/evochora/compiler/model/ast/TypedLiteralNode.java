package org.evochora.compiler.model.ast;

import org.evochora.compiler.model.token.Token;

/**
 * An AST node that represents a typed literal, e.g., "DATA:42".
 *
 * @param type The token containing the type of the literal (e.g., DATA).
 * @param value The token containing the numeric value.
 */
public record TypedLiteralNode(
        Token type,
        Token value
) implements AstNode {
    // This node has no children and inherits the empty list from getChildren().
}
