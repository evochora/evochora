package org.evochora.compiler.features.place.placement;

import org.evochora.compiler.model.ast.AstNode;

import java.util.List;

/**
 * One placement of a {@code .PLACE} directive, the part between two commas: one component per
 * piece of its {@code |}-list, such as {@code 1..3|5}. A placement of one piece that is no range
 * and no wildcard is a single component whose operand may give the whole place at once: a vector,
 * counted from the origin of the enclosing module, or a position, a place of the program.
 *
 * @param components The components, in order.
 */
public record PlacementNode(List<IPlacementComponent> components) implements AstNode {

    /**
     * Copies the components.
     */
    public PlacementNode {
        components = List.copyOf(components);
    }

    @Override
    public List<AstNode> getChildren() {
        return List.copyOf(components);
    }

    /**
     * Phase 6 puts the components it rebuilt into the node through this, in the order of
     * {@link #getChildren()}.
     */
    @Override
    public AstNode reconstructWithChildren(List<AstNode> newChildren) {
        return new PlacementNode(newChildren.stream().map(child -> (IPlacementComponent) child).toList());
    }
}
