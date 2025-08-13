package org.evochora.compiler.core.ast;

import org.evochora.compiler.core.Token;

/**
 * Ein AST-Knoten, der eine .REQUIRE-Direktive repräsentiert.
 *
 * @param requiredName Das Token des Namens, der benötigt wird.
 */
public record RequireNode(
        Token requiredName
) implements AstNode {
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
