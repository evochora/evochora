package org.evochora.compiler.core.ast;

import org.evochora.compiler.core.Token;

/**
 * Ein AST-Knoten, der einen allgemeinen Identifier repräsentiert,
 * z.B. einen Konstantennamen oder ein Label, das als Argument verwendet wird.
 *
 * @param identifierToken Das Token des Identifiers.
 */
public record IdentifierNode(
        Token identifierToken
) implements AstNode {
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
