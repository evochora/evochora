package org.evochora.compiler.features.place.placement;

import org.evochora.compiler.model.ast.VectorLiteralNode;

/**
 * An adapter class that holds a VectorLiteralNode for the old syntax (e.g., 11|12).
 * @param vector The underlying VectorLiteralNode.
 */
public record VectorPlacementNode(VectorLiteralNode vector) implements IPlacementArgumentNode {
}
