package org.evochora.compiler.core.ast;

import org.evochora.compiler.core.Token;

/**
 * Ein AST-Knoten, der eine .IMPORT-Direktive repräsentiert.
 *
 * @param name Das Token des Namens, der importiert wird.
 * @param alias Das optionale Alias-Token (kann null sein).
 */
public record ImportNode(
        Token name,
        Token alias
) implements AstNode {
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
