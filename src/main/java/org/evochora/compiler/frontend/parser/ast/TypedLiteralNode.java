package org.evochora.compiler.frontend.parser.ast;

import org.evochora.compiler.frontend.lexer.Token;

/**
 * Ein AST-Knoten, der ein typisiertes Literal repräsentiert, z.B. "DATA:42".
 *
 * @param type Das Token, das den Typ des Literals enthält (z.B. DATA).
 * @param value Das Token, das den numerischen Wert enthält.
 */
public record TypedLiteralNode(
        Token type,
        Token value
) implements AstNode {
    // Dieser Knoten hat keine Kinder und erbt die leere Liste von getChildren().
}