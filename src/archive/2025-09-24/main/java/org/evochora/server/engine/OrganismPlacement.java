package org.evochora.server.engine;

import org.evochora.compiler.api.ProgramArtifact;

/**
 * Represents the placement of an organism in the simulation with its compiled program.
 * This replaces the need for separate OrganismDefinition and ProgramArtifact handling.
 * 
 * <p>The organism will be created at the specified position with the given initial energy,
 * and its compiled program will be placed in the environment at that location.</p>
 * 
 * @param programArtifact The compiled program artifact containing the organism's code
 * @param initialEnergy The initial energy level for the organism
 * @param startPosition The starting position coordinates for the organism
 */
public record OrganismPlacement(
    ProgramArtifact programArtifact,
    int initialEnergy, 
    int[] startPosition
) {
    /**
     * Creates an OrganismPlacement.
     * 
     * @param programArtifact The compiled program artifact
     * @param initialEnergy The initial energy level
     * @param startPosition The starting position coordinates
     * @return A new OrganismPlacement
     */
    public static OrganismPlacement of(ProgramArtifact programArtifact, int initialEnergy, int[] startPosition) {
        return new OrganismPlacement(programArtifact, initialEnergy, startPosition);
    }
    
    /**
     * Validates that the placement has valid parameters.
     * 
     * @throws IllegalArgumentException if any parameter is invalid
     */
    public OrganismPlacement {
        if (programArtifact == null) {
            throw new IllegalArgumentException("ProgramArtifact cannot be null");
        }
        if (initialEnergy < 0) {
            throw new IllegalArgumentException("Initial energy cannot be negative");
        }
        if (startPosition == null || startPosition.length == 0) {
            throw new IllegalArgumentException("Start position cannot be null or empty");
        }
        for (int pos : startPosition) {
            if (pos < 0) {
                throw new IllegalArgumentException("Start position coordinates cannot be negative");
            }
        }
    }
    
    /**
     * Gets the start position as the initial IP position.
     * 
     * @return The start position coordinates
     */
    public int[] getInitialIpPosition() {
        return startPosition.clone();
    }
}
