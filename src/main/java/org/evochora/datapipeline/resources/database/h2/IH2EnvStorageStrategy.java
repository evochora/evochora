package org.evochora.datapipeline.resources.database.h2;

import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.runtime.model.EnvironmentProperties;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * H2-specific strategy interface for storing environment data.
 * <p>
 * Different strategies trade off between storage size, query performance,
 * and write performance. This interface is H2-specific and cannot be used
 * with other database backends.
 * <p>
 * <strong>Rationale:</strong> Storage requirements for environment data can vary
 * dramatically based on environment size and tick count:
 * <ul>
 *   <li>Small runs (1000×1000, 10^6 ticks): ~15 GB with row-per-cell</li>
 *   <li>Large runs (10000×10000, 10^8 ticks): ~500 TB with row-per-cell ❌</li>
 * </ul>
 * Different storage strategies enable different trade-offs without code changes.
 * <p>
 * <strong>Phase 14.3:</strong> Only write operations needed. Query methods
 * will be added in Phase 14.5 (HTTP API implementation).
 */
public interface IH2EnvStorageStrategy {
    
    /**
     * Creates the necessary schema (tables, indexes) for this storage strategy.
     * <p>
     * Must be idempotent - calling multiple times should be safe.
     *
     * @param conn Database connection (with autoCommit=false)
     * @param dimensions Number of spatial dimensions
     * @throws SQLException if schema creation fails
     */
    void createSchema(Connection conn, int dimensions) throws SQLException;
    
    /**
     * Writes environment data for multiple ticks using this storage strategy.
     * <p>
     * <strong>Transaction Management:</strong> This method is executed within a transaction
     * managed by the caller (H2Database). Implementations should <strong>NOT</strong> call
     * {@code commit()} or {@code rollback()} themselves. If an exception is thrown, the
     * caller is responsible for rolling back the transaction.
     * <p>
     * <strong>Rationale:</strong> Keeps strategy focused on SQL operations only, while
     * H2Database manages transaction lifecycle consistently across all methods.
     *
     * @param conn Database connection (with autoCommit=false, transaction managed by caller)
     * @param ticks List of ticks with cell data to write
     * @param envProps Environment properties for coordinate conversion
     * @throws SQLException if write fails (caller will rollback)
     */
    void writeTicks(Connection conn, List<TickData> ticks, 
                    EnvironmentProperties envProps) throws SQLException;
}


