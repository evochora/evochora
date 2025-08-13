package org.evochora.compiler.core.ast;

import org.evochora.compiler.core.Token;

/**
 * Ein AST-Knoten, der eine .EXPORT-Direktive repräsentiert.
 *
 * @param exportedName Das Token des Namens, der exportiert wird.
 */
public record ExportNode(
        Token exportedName
) implements AstNode {
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
