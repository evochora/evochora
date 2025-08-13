package org.evochora.compiler.core.ast;

import org.evochora.compiler.core.Token;

/**
 * Ein AST-Knoten, der ein Register repräsentiert.
 *
 * @param registerToken Das Token, das das Register enthält (z.B. %DR0).
 */
public record RegisterNode(
        Token registerToken
) implements AstNode {
}
