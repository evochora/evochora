package org.evochora.compiler.features.place.placement;

/**
 * Represents a continuous range with a start and end value (e.g., 1..10).
 * @param start The start value of the range.
 * @param end The end value of the range.
 */
public record RangeValueComponent(int start, int end) implements IPlacementComponent {
}
