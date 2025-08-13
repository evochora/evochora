package org.evochora.compiler.frontend.parser.ast;

import org.evochora.compiler.frontend.lexer.Token;

/**
 * Ein AST-Knoten, der ein Register repräsentiert.
 *
 * @param registerToken Das Token, das das Register enthält (z.B. %DR0).
 */
public record RegisterNode(
        Token registerToken
) implements AstNode {
    // Dieser Knoten hat keine Kinder.
}