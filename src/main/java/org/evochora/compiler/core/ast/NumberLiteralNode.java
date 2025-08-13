package org.evochora.compiler.core.ast;

import org.evochora.compiler.core.Token;

/**
 * Ein AST-Knoten, der ein numerisches Literal repräsentiert.
 *
 * @param numberToken Das Token, das die Zahl enthält.
 */
public record NumberLiteralNode(
        Token numberToken
) implements AstNode {
    public int getValue() {
        return (int) numberToken.value();
    }
}
