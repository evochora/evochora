package org.evochora.compiler.frontend.parser.features.place;

import org.evochora.compiler.frontend.parser.ast.AstNode;

import java.util.List;

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
    public List<AstNode> getChildren() {
        // Die Kinder eines Place-Knotens sind das Literal und die Position.
        return List.of(literal, position);
    }
}