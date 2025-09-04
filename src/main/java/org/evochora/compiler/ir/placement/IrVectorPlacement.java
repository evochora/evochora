package org.evochora.compiler.ir.placement;

import java.util.List;

/**
 * The IR representation of a single vector placement.
 * @param components The integer components of the vector.
 */
public record IrVectorPlacement(List<Integer> components) implements IPlacementArgument {
}
