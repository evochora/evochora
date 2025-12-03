package org.evochora.runtime.isa;

import org.evochora.runtime.model.Molecule;

/**
 * Interface for reading molecules from different environment implementations.
 * This allows the UnifiedDisassembler to work with both Runtime Environment
 * and RawTickState data without duplicating the disassembly logic.
 */
public interface IEnvironmentReader {
    
    /**
     * Reads a molecule at the given coordinates.
     * 
     * @param coordinates The coordinates to read from
     * @return The molecule at the specified coordinates, or null if not found
     */
    Molecule getMolecule(int... coordinates);
    
    /**
     * Gets the shape/dimensions of the environment.
     * 
     * @return The dimensions of the environment (e.g., [100, 100] for 2D)
     */
    int[] getShape();
    
    /**
     * Gets the environment properties for coordinate calculations.
     * 
     * @return The environment properties containing coordinate logic
     */
    org.evochora.runtime.model.EnvironmentProperties getProperties();
}
