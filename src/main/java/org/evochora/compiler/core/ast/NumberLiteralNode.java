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
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }

    public int getValue() {
        return (int) numberToken.value();
    }
}
