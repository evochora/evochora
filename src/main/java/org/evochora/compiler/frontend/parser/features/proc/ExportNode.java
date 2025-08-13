package org.evochora.compiler.frontend.parser.features.proc;

import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.parser.ast.AstNode;

/**
 * Ein AST-Knoten, der eine .EXPORT-Direktive innerhalb einer Prozedur repräsentiert.
 *
 * @param exportedName Das Token des Namens, der exportiert wird.
 */
public record ExportNode(
        Token exportedName
) implements AstNode {
    // Dieser Knoten hat keine Kinder, daher wird die default-Methode von AstNode verwendet.
}