package org.evochora.compiler.frontend.parser.features.proc;

import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.ast.AstNode;

/**
 * Ein AST-Knoten, der eine .PREG-Direktive innerhalb einer Prozedur repräsentiert.
 *
 * @param alias Das Token des Alias-Namens.
 * @param index Das Token des Register-Index (0 oder 1).
 */
public record PregNode(
        Token alias,
        Token index
) implements AstNode {
    // Dieser Knoten hat keine Kinder.
}