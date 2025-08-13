package org.evochora.compiler.core.ast;

/**
 * Ein AST-Knoten, der eine .PLACE-Direktive repräsentiert.
 *
 * @param literal Das zu platzierende Literal.
 * @param position Das Vektor-Literal, das die Position angibt.
 */
public record PlaceNode(
        AstNode literal,
        AstNode position
) implements AstNode {
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
