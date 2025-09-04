package org.evochora.compiler.frontend.parser.ast.placement;

import org.evochora.compiler.frontend.lexer.Token;

/**
 * Represents a single integer value in a .PLACE directive.
 * @param value The token containing the integer value.
 */
public record SingleValueComponent(Token value) implements IPlacementComponent {
}
