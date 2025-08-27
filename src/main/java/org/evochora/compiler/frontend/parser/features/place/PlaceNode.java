package org.evochora.compiler.frontend.parser.features.place;

import org.evochora.compiler.frontend.parser.ast.AstNode;

import java.util.List;

/**
 * An AST node that represents a <code>.place</code> directive.
 *
 * @param literal The literal to be placed.
 * @param position The vector literal that specifies the position.
 */
public record PlaceNode(
        AstNode literal,
        AstNode position
) implements AstNode {

    @Override
    public List<AstNode> getChildren() {
        // The children of a PlaceNode are the literal and the position.
        return List.of(literal, position);
    }
}