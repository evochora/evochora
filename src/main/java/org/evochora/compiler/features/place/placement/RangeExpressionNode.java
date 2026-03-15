package org.evochora.compiler.frontend.parser.ast.placement;

import java.util.List;

/**
 * Represents the new, complex range syntax (e.g., 1..10|20..30).
 * It holds a structure to represent n-dimensional arguments.
 * @param dimensions A list of dimensions, where each dimension is a list of placement components.
 */
public record RangeExpressionNode(List<List<IPlacementComponent>> dimensions) implements IPlacementArgumentNode {
}
