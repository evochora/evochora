package org.evochora.compiler.model.ir.placement;

/**
 * The IR representation of a range value component.
 * @param start The start of the range.
 * @param end The end of the range.
 */
public record IrRangeValueComponent(int start, int end) implements IIrPlacementComponent {
}
