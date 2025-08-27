package org.evochora.compiler.frontend.parser.ast;

import org.evochora.compiler.frontend.lexer.Token;

/**
 * An AST node that represents a register.
 *
 * @param registerToken The token containing the register (e.g., %DR0).
 */
public record RegisterNode(
        Token registerToken
) implements AstNode {
    // This node has no children.
}