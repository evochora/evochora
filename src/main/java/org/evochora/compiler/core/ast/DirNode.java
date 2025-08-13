package org.evochora.compiler.core.ast;

/**
 * Ein AST-Knoten, der eine .DIR-Direktive repräsentiert.
 *
 * @param directionVector Das Vektor-Literal, das die Richtung angibt.
 */
public record DirNode(
        AstNode directionVector
) implements AstNode {
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
