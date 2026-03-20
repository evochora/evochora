package org.evochora.compiler.features.place.placement;

/**
 * Represents a single integer value in a .PLACE directive.
 * @param value The integer value for this dimension.
 */
public record SingleValueComponent(int value) implements IPlacementComponent {
}
