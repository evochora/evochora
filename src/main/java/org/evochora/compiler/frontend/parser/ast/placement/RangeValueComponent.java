package org.evochora.compiler.frontend.parser.ast.placement;

import org.evochora.compiler.model.Token;

/**
 * Represents a continuous range with a start and end value (e.g., 1..10).
 * @param start The token for the start value.
 * @param end The token for the end value.
 */
public record RangeValueComponent(Token start, Token end) implements IPlacementComponent {
}
