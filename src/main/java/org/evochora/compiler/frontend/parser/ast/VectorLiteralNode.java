package org.evochora.compiler.frontend.parser.ast;

import org.evochora.compiler.frontend.lexer.Token;

import java.util.List;

/**
 * Ein AST-Knoten, der ein Vektor-Literal repräsentiert, z.B. "3|21".
 *
 * @param components Eine Liste von Tokens, die die einzelnen Zahlen des Vektors darstellen.
 */
public record VectorLiteralNode(
        List<Token> components
) implements AstNode {
    // Dieser Knoten hat keine Kinder und erbt die leere Liste von getChildren().
}