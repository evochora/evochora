package org.evochora.compiler.features.place.placement;

import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.OperandNode;

import java.util.List;

/**
 * Represents a continuous range with a start and end value (e.g., 1..10), both included.
 * @param start The operand of the start value of the range.
 * @param end The operand of the end value of the range.
 */
public record RangeValueComponent(OperandNode start, OperandNode end) implements IPlacementComponent {

    @Override
    public List<AstNode> getChildren() {
        return List.of(start, end);
    }

    @Override
    public AstNode reconstructWithChildren(List<AstNode> newChildren) {
        return new RangeValueComponent((OperandNode) newChildren.get(0), (OperandNode) newChildren.get(1));
    }
}
