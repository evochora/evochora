package org.evochora.datapipeline.api.resources.database;

/**
 * Interface for database connection management capabilities.
 * <p>
 * This interface provides methods for managing database connections in resource-aware
 * implementations. It separates connection management concerns from pure capability interfaces.
 * <p>
 * <strong>Usage:</strong>
 * <ul>
 *   <li>Resource-aware database implementations implement this interface</li>
 *   <li>Direct database implementations (like H2DatabaseReader) don't need this</li>
 *   <li>This allows clean separation between capability and resource management</li>
 * </ul>
 * <p>
 * <strong>Future Extensions:</strong>
 * <ul>
 *   <li>Can be extended with additional connection management methods</li>
 *   <li>Examples: acquireConnection(), isConnectionActive(), getConnectionPoolStatus()</li>
 *   <li>Maintains clean interface segregation</li>
 * </ul>
 */
public interface IConnectionManageable {
    
    /**
     * Releases the cached database connection back to the pool.
     * <p>
     * Call before long idle periods (e.g., during polling sleeps) to reduce
     * connection pool pressure. Connection will be re-acquired automatically on next operation.
     * <p>
     * <strong>Use Cases:</strong>
     * <ul>
     *   <li>During polling intervals to free up connection pool resources</li>
     *   <li>Before long-running operations that don't need database access</li>
     *   <li>During service pause/resume cycles</li>
     * </ul>
     */
    void releaseConnection();
}
