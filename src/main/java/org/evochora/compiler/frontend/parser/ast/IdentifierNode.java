package org.evochora.compiler.frontend.parser.ast;

import org.evochora.compiler.frontend.lexer.Token;

/**
 * Ein AST-Knoten, der einen allgemeinen Identifier repräsentiert,
 * z.B. einen Konstantennamen oder ein Label, das als Argument verwendet wird.
 *
 * @param identifierToken Das Token des Identifiers.
 */
public record IdentifierNode(
        Token identifierToken
) implements AstNode {
    // Dieser Knoten hat keine Kinder und erbt die leere Liste von getChildren().
}