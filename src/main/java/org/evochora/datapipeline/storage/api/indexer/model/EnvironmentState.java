package org.evochora.datapipeline.storage.api.indexer.model;

/**
 * Data model representing a single environment state record.
 * 
 * <p>This record contains all the necessary information to represent
 * the state of a single cell in the environment at a specific tick.
 * The position is stored as a Position record to support n-dimensional
 * coordinates flexibly.</p>
 * 
 * <p>Environment state data includes:
 * <ul>
 *   <li>Cell position (n-dimensional coordinates as Position record)</li>
 *   <li>Molecule type and value</li>
 *   <li>Owner information</li>
 *   <li>Tick timestamp</li>
 * </ul>
 * </p>
 * 
 * @author evochora
 * @since 1.0
 */
public record EnvironmentState(
    long tick,
    Position position,  // n-dimensional coordinates as Position record
    String moleculeType,
    int moleculeValue,  // integer value for molecule amount
    long owner
) {
    
    /**
     * Creates a new EnvironmentState record.
     * 
     * @param tick the simulation tick when this state was recorded
     * @param position the n-dimensional position coordinates as Position record
     * @param moleculeType the type of molecule present in the cell
     * @param moleculeValue the value/amount of the molecule
     * @param owner the ID of the organism that owns this cell (0 if unowned)
     */
    public EnvironmentState {
        if (position == null) {
            throw new IllegalArgumentException("Position cannot be null");
        }
        if (moleculeType == null || moleculeType.trim().isEmpty()) {
            throw new IllegalArgumentException("Molecule type cannot be null or empty");
        }
    }
    
    /**
     * Checks if this cell is owned by an organism.
     * 
     * @return true if owner is greater than 0, false otherwise
     */
    public boolean isOwned() {
        return owner > 0;
    }
    
    /**
     * Checks if this cell contains a molecule.
     * 
     * @return true if molecule value is greater than 0, false otherwise
     */
    public boolean hasMolecule() {
        return moleculeValue > 0;
    }
}
