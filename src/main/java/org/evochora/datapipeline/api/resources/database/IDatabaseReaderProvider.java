package org.evochora.datapipeline.api.resources.database;

import java.sql.SQLException;

/**
 * Factory interface for creating per-request database readers.
 * <p>
 * Implementations (H2Database, PostgresDatabase) create readers with dedicated
 * connections and schema pre-set, enabling concurrent requests with isolation.
 */
public interface IDatabaseReaderProvider {
    /**
     * Creates a new reader for the specified simulation run.
     * @param runId Simulation run ID (sets schema)
     * @return Reader with dedicated connection
     * @throws SQLException if no schema exists for the run or no connection can be acquired.
     *         The reader owns its connection and must be closed by the caller to release it.
     */
    IDatabaseReader createReader(String runId) throws SQLException;
    
    /**
     * Finds the latest simulation run ID in the database.
     * @return Latest run-id or null if database is empty
     * @throws SQLException if database query fails
     */
    String findLatestRunId() throws SQLException;
}
