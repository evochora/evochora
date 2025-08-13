package org.evochora.compiler.frontend.parser.ast;

import org.evochora.compiler.frontend.lexer.Token;

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

    // Dieser Knoten hat keine Kinder und erbt die leere Liste von getChildren().
}