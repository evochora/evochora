package org.evochora.compiler.frontend.parser.ast;

import org.evochora.compiler.frontend.lexer.Token;

/**
 * An AST node that represents a numeric literal.
 *
 * @param numberToken The token containing the number.
 */
public record NumberLiteralNode(
        Token numberToken
) implements AstNode {

    /**
     * Gets the integer value of the numeric literal.
     * @return The integer value.
     */
    public int getValue() {
        return (int) numberToken.value();
    }

    // This node has no children and inherits the empty list from getChildren().
}