package org.evochora.compiler.ir.placement;

/**
 * The IR representation of a single integer value component.
 * @param value The integer value.
 */
public record IrSingleValueComponent(int value) implements IIrPlacementComponent {
}
