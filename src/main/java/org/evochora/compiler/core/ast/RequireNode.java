package org.evochora.compiler.core.ast;

import org.evochora.compiler.core.Token;

/**
 * Ein AST-Knoten, der eine .REQUIRE-Direktive repräsentiert.
 *
 * @param path Das String-Token des Dateipfads.
 * @param alias Das optionale Alias-Token (kann null sein).
 */
public record RequireNode(
        Token path,
        Token alias
) implements AstNode {
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
