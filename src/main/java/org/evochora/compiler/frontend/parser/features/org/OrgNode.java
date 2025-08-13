package org.evochora.compiler.frontend.parser.features.org;

import org.evochora.compiler.frontend.parser.ast.AstNode;

import java.util.List;

/**
 * Ein AST-Knoten, der eine .ORG-Direktive repräsentiert.
 *
 * @param originVector Das Vektor-Literal, das den Ursprung angibt.
 */
public record OrgNode(
        AstNode originVector
) implements AstNode {

    @Override
    public List<AstNode> getChildren() {
        // Das Kind eines .ORG-Knotens ist der Vektor, der den Ursprung definiert.
        return List.of(originVector);
    }
}