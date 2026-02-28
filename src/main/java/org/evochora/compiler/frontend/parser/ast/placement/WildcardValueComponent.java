package org.evochora.compiler.frontend.parser.ast.placement;

import org.evochora.compiler.model.Token;

/**
 * Represents the '*' wildcard for a full dimension.
 * @param star The token for the '*' character.
 */
public record WildcardValueComponent(Token star) implements IPlacementComponent {
}
