package org.evochora.compiler.features.place.placement;

/**
 * Represents a range with a start, step, and end value (e.g., 10:2:20).
 * @param start The start value of the range.
 * @param step The step value between elements.
 * @param end The end value of the range.
 */
public record SteppedRangeValueComponent(int start, int step, int end) implements IPlacementComponent {
}
