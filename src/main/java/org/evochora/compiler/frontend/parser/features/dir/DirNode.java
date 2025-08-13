package org.evochora.compiler.frontend.parser.features.dir;

import org.evochora.compiler.frontend.parser.ast.AstNode;

import java.util.List;

/**
 * Ein AST-Knoten, der eine .DIR-Direktive repräsentiert.
 *
 * @param directionVector Das Vektor-Literal, das die Richtung angibt.
 */
public record DirNode(
        AstNode directionVector
) implements AstNode {

    @Override
    public List<AstNode> getChildren() {
        // Das Kind eines .DIR-Knotens ist der Vektor, der die Richtung definiert.
        return List.of(directionVector);
    }
}