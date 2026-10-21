package org.evochora.compiler.features.place.placement;

import org.evochora.compiler.model.ast.LiteralNode;

/**
 * A placement written as one vector, such as {@code 11|12}.
 * @param vector The vector, a literal whose value is a vector.
 */
public record VectorPlacementNode(LiteralNode vector) implements IPlacementArgumentNode {
}
