package org.evochora.compiler.core.ast;

import org.evochora.compiler.core.Token;

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
}
