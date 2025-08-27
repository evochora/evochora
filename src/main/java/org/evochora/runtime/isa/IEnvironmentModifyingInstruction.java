package org.evochora.runtime.isa;

import java.util.List;

/**
 * A "marker interface" for instructions that directly modify cells in the world
 * and therefore must be involved in conflict resolution.
 * It no longer contains its own methods, as all required methods
 * are inherited from the Instruction base class.
 */
public interface IEnvironmentModifyingInstruction {

    /**
     * Returns a list of the n-dimensional coordinates that this instruction
     * attempts to modify. This is crucial for conflict resolution.
     * @return A list of int[] arrays, where each array represents a coordinate.
     */
    List<int[]> getTargetCoordinates();

}