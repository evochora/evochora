package org.evochora.compiler.features.place.placement;

import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.OperandNode;

import java.util.List;

/**
 * Represents a single value in a .PLACE directive: a number for one dimension, or, as the only
 * component of a placement, a vector or a position for the whole place.
 * @param value The operand of the value.
 */
public record SingleValueComponent(OperandNode value) implements IPlacementComponent {

    @Override
    public List<AstNode> getChildren() {
        return List.of(value);
    }

    @Override
    public AstNode reconstructWithChildren(List<AstNode> newChildren) {
        return new SingleValueComponent((OperandNode) newChildren.getFirst());
    }
}
