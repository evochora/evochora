package org.evochora.compiler.features.place;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.OperandNode;
import org.evochora.compiler.model.ast.ISourceLocatable;
import org.evochora.compiler.features.place.placement.PlacementNode;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * An AST node that represents a <code>.PLACE</code> directive.
 *
 * @param literal The molecule to be placed: a typed literal written out, or a name or a
 *                computation whose value is a typed value.
 * @param placements The placements, one per comma-separated part of the directive.
 * @param sourceInfo The source location of the directive.
 */
public record PlaceNode(
        OperandNode literal,
        List<PlacementNode> placements,
        SourceInfo sourceInfo
) implements AstNode, ISourceLocatable {

    @Override
    public List<AstNode> getChildren() {
        return Stream.concat(
                Stream.of(literal),
                placements.stream().map(p -> (AstNode) p)
        ).collect(Collectors.toList());
    }

    /**
     * Phase 6 puts the literal and the placements it rebuilt into the node through this, in the
     * order of {@link #getChildren()}.
     */
    @Override
    public AstNode reconstructWithChildren(List<AstNode> newChildren) {
        return new PlaceNode((OperandNode) newChildren.getFirst(),
                newChildren.subList(1, newChildren.size()).stream().map(p -> (PlacementNode) p).toList(),
                sourceInfo);
    }
}
