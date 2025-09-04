package org.evochora.compiler.frontend.parser.features.place;

import org.evochora.compiler.frontend.parser.ast.AstNode;
import org.evochora.compiler.frontend.parser.ast.placement.IPlacementArgumentNode;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * An AST node that represents a <code>.place</code> directive.
 *
 * @param literal The literal to be placed.
 * @param placements The list of placement arguments, which can be vectors or range expressions.
 */
public record PlaceNode(
        AstNode literal,
        List<IPlacementArgumentNode> placements
) implements AstNode {

    @Override
    public List<AstNode> getChildren() {
        // The children are the literal plus all placement nodes.
        return Stream.concat(
                Stream.of(literal),
                placements.stream().map(p -> (AstNode) p)
        ).collect(Collectors.toList());
    }
}