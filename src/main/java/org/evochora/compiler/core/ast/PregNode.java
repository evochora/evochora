package org.evochora.compiler.core.ast;

import org.evochora.compiler.core.Token;

/**
 * Ein AST-Knoten, der eine .PREG-Direktive repräsentiert.
 *
 * @param alias Das Token des Alias-Namens.
 * @param register Das Token des Registers (%PR0 oder %PR1).
 */
public record PregNode(
        Token alias,
        Token register
) implements AstNode {
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
