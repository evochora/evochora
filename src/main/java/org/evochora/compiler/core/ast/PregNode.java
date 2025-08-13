package org.evochora.compiler.core.ast;

import org.evochora.compiler.core.Token;

/**
 * Ein AST-Knoten, der eine .PREG-Direktive repräsentiert.
 *
 * @param alias Das Token des Alias-Namens.
 * @param index Das Token des Register-Index (0 oder 1).
 */
public record PregNode(
        Token alias,
        Token index
) implements AstNode {
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
