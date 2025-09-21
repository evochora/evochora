package org.evochora.datapipeline.storage.api.indexer;

import org.evochora.datapipeline.storage.api.indexer.model.EnvironmentState;

import java.util.List;

/**
 * Interface for writing environment state data to persistent storage.
 * 
 * <p>This interface defines the contract for storing environment state information
 * including cell positions, molecule data, and ownership information for each tick.
 * The interface is designed to be database-agnostic and supports batch operations
 * for efficient data persistence.</p>
 * 
 * <p>Environment state data includes:
 * <ul>
 *   <li>Cell position (n-dimensional coordinates)</li>
 *   <li>Molecule type and value</li>
 *   <li>Owner information</li>
 *   <li>Tick timestamp</li>
 * </ul>
 * </p>
 * 
 * @author evochora
 * @since 1.0
 */
public interface IEnvironmentStateWriter {
    
    /**
     * Initializes the writer with the environment dimensions and simulation run ID.
     * This method must be called before any write operations.
     *
     * @param dimensions the number of dimensions in the environment
     * @param simulationRunId the unique identifier for this simulation run
     * @throws RuntimeException if initialization fails
     */
    void initialize(int dimensions, String simulationRunId);
    
    /**
     * Writes a batch of environment state records to persistent storage.
     * 
     * <p>This method accepts a list of environment state records and persists
     * them to the underlying storage system. The implementation should handle
     * batch operations efficiently, regardless of whether the list contains
     * a single record or thousands of records.</p>
     * 
     * <p>The method is designed to be called by indexer services that collect
     * and batch environment state data before writing to storage.</p>
     * 
     * @param environmentStates the list of environment state records to persist.
     *                         Must not be null, but may be empty.
     * @throws IllegalArgumentException if environmentStates is null or dimensions don't match
     * @throws RuntimeException if the write operation fails due to storage issues
     */
    void writeEnvironmentStates(List<EnvironmentState> environmentStates);

    /**
     * Closes the writer and releases all resources.
     * 
     * <p>This method should be called when the writer is no longer needed.
     * It will close database connections, stop background services,
     * and perform any necessary cleanup operations.</p>
     * 
     * <p>After calling this method, the writer should not be used for
     * further operations. If needed, a new instance should be created.</p>
     * 
     * @throws RuntimeException if closing fails
     */
    void close();
    
}
