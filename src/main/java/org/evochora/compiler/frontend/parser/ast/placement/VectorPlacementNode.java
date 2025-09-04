package org.evochora.compiler.frontend.parser.ast.placement;

import org.evochora.compiler.frontend.parser.ast.VectorLiteralNode;

/**
 * An adapter class that holds a VectorLiteralNode for the old syntax (e.g., 11|12).
 * @param vector The underlying VectorLiteralNode.
 */
public record VectorPlacementNode(VectorLiteralNode vector) implements IPlacementArgumentNode {
}
