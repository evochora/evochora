package org.evochora.compiler.frontend.parser.ast.placement;

import org.evochora.compiler.frontend.lexer.Token;

/**
 * Represents a range with a start, step, and end value (e.g., 10:2:20).
 * @param start The token for the start value.
 * @param step The token for the step value.
 * @param end The token for the end value.
 */
public record SteppedRangeValueComponent(Token start, Token step, Token end) implements IPlacementComponent {
}
