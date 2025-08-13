package org.evochora.compiler.core.ast;

/**
 * Ein AST-Knoten, der eine .ORG-Direktive repräsentiert.
 *
 * @param originVector Das Vektor-Literal, das den Ursprung angibt.
 */
public record OrgNode(
        AstNode originVector
) implements AstNode {
    @Override
    public <T> T accept(AstVisitor<T> visitor) {
        return visitor.visit(this);
    }
}
