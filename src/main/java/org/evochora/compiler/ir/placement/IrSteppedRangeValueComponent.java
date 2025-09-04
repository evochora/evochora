package org.evochora.compiler.ir.placement;

/**
 * The IR representation of a stepped range value component.
 * @param start The start of the range.
 * @param step The step of the range.
 * @param end The end of the range.
 */
public record IrSteppedRangeValueComponent(int start, int step, int end) implements IIrPlacementComponent {
}
