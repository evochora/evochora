package org.evochora.compiler.features.place.placement;

import org.evochora.compiler.model.ast.AstNode;
import org.evochora.compiler.model.ast.OperandNode;

import java.util.List;

/**
 * Represents a range with a start, step, and end value (e.g., 10:2:20).
 * @param start The operand of the start value of the range.
 * @param step The operand of the step between elements.
 * @param end The operand of the end value of the range.
 */
public record SteppedRangeValueComponent(OperandNode start, OperandNode step, OperandNode end) implements IPlacementComponent {

    @Override
    public List<AstNode> getChildren() {
        return List.of(start, step, end);
    }

    @Override
    public AstNode reconstructWithChildren(List<AstNode> newChildren) {
        return new SteppedRangeValueComponent((OperandNode) newChildren.get(0), (OperandNode) newChildren.get(1),
                (OperandNode) newChildren.get(2));
    }
}
