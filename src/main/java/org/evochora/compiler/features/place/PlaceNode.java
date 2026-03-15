package org.evochora.compiler.features.place;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.ISourceLocatable;
import org.evochora.compiler.features.place.placement.IPlacementArgumentNode;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * An AST node that represents a <code>.PLACE</code> directive.
 *
 * @param literal The literal to be placed.
 * @param placements The list of placement arguments, which can be vectors or range expressions.
 * @param sourceInfo The source location of the directive.
 */
public record PlaceNode(
        AstNode literal,
        List<IPlacementArgumentNode> placements,
        SourceInfo sourceInfo
) implements AstNode, ISourceLocatable {

    @Override
    public List<AstNode> getChildren() {
        return Stream.concat(
                Stream.of(literal),
                placements.stream().map(p -> (AstNode) p)
        ).collect(Collectors.toList());
    }
}
