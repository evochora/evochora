package org.evochora.compiler.model.ir.placement;

import java.util.List;

/**
 * The IR representation of the new range syntax.
 * @param dimensions A list of dimensions, where each dimension is a list of IR placement components.
 */
public record IrRangeExpression(List<List<IIrPlacementComponent>> dimensions) implements IPlacementArgument {
}
