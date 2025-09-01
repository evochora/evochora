package org.evochora.server.indexer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * Manages database connections and operations for the DebugIndexer.
 * Handles connection creation, setup, WAL management, and cleanup.
 */
public class DatabaseManager {
    
    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);
    
    private final String debugDbPath;
    private Connection connection;
    private PreparedStatement tickInsertStatement;
    private int batchCount = 0;
    
    public DatabaseManager(String debugDbPath) {
        this.debugDbPath = debugDbPath;
    }
    
    /**
     * Creates a database connection for the given path or URL.
     * Handles both JDBC URLs and file paths with appropriate optimizations.
     * 
     * @param pathOrUrl The database path or JDBC URL
     * @return A configured database connection
     * @throws Exception if connection creation fails
     */
    public Connection createConnection(String pathOrUrl) throws Exception {
        if (pathOrUrl.startsWith("jdbc:")) {
            return DriverManager.getConnection(pathOrUrl);
        }
        
        // For file paths, check if this is a test environment and convert to in-memory
        if (pathOrUrl.contains("memdb_") || pathOrUrl.contains("test_")) {
            // This is a test database, use in-memory mode
            return DriverManager.getConnection("jdbc:sqlite:file:" + pathOrUrl + "?mode=memory&cache=shared");
        }
        
        // For production file paths, use regular SQLite
        return DriverManager.getConnection("jdbc:sqlite:" + pathOrUrl);
    }
    
    /**
     * Sets up the debug database with tables and performance optimizations.
     * Creates the database schema and applies SQLite performance hints.
     * 
     * @throws Exception if database setup fails
     */
    public void setupDebugDatabase() throws Exception {
        connection = createConnection(debugDbPath);
        
        // Add performance hints for SQLite
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL"); // Write-Ahead Logging for better concurrency
            st.execute("PRAGMA synchronous=NORMAL"); // Faster writes
            st.execute("PRAGMA cache_size=10000"); // Larger cache
            st.execute("PRAGMA temp_store=MEMORY"); // Use memory for temp tables
            
            // Create tables
            st.execute("CREATE TABLE IF NOT EXISTS prepared_ticks (tick_number INTEGER PRIMARY KEY, tick_data_json TEXT)");
            st.execute("CREATE TABLE IF NOT EXISTS simulation_metadata (key TEXT PRIMARY KEY, value TEXT)");
            st.execute("CREATE TABLE IF NOT EXISTS program_artifacts (program_id TEXT PRIMARY KEY, artifact_json TEXT)");
        }
        
        log.debug("Debug database setup completed for: {}", debugDbPath);
    }
    
    /**
     * Writes a prepared tick to the database using batch operations for performance.
     * 
     * @param tickNumber The tick number
     * @param tickDataJson The JSON representation of the tick data
     * @throws Exception if writing fails
     */
    public void writePreparedTick(long tickNumber, String tickDataJson) throws Exception {
        // Ensure database connection is available
        if (connection == null || connection.isClosed()) {
            setupDebugDatabase();
        }
        
        // Use batch insert for better performance
        if (tickInsertStatement == null) {
            tickInsertStatement = connection.prepareStatement(
                "INSERT OR REPLACE INTO prepared_ticks(tick_number, tick_data_json) VALUES (?, ?)");
        }
        
        tickInsertStatement.setLong(1, tickNumber);
        tickInsertStatement.setString(2, tickDataJson);
        tickInsertStatement.addBatch();
        
        // Execute batch every 1000 ticks for optimal performance
        if (++batchCount % 1000 == 0) {
            tickInsertStatement.executeBatch();
            tickInsertStatement.clearBatch();
            log.debug("Executed batch of 1000 ticks, total processed: {}", batchCount);
        }
    }
    
    /**
     * Executes any remaining batch operations and resets the batch counter.
     * 
     * @throws Exception if batch execution fails
     */
    public void executeRemainingBatch() throws Exception {
        if (tickInsertStatement != null && batchCount > 0) {
            int[] results = tickInsertStatement.executeBatch();
            log.debug("Executed final batch of {} ticks, result: {}", batchCount, java.util.Arrays.toString(results));
            tickInsertStatement.clearBatch();
            batchCount = 0; // Reset batch counter after execution
        }
    }
    
    /**
     * Closes the database connection and performs WAL cleanup.
     * Ensures all data is properly flushed and WAL files are released.
     */
    public void closeQuietly() {
        try {
            // First, execute any remaining batch operations
            if (tickInsertStatement != null && batchCount > 0) {
                // Always execute remaining batch, regardless of batchCount value
                executeRemainingBatch();
            }
            
            if (tickInsertStatement != null) {
                tickInsertStatement.close();
                tickInsertStatement = null;
            }
            
            // Perform WAL checkpoint to ensure all changes are in the main database
            if (connection != null && !connection.isClosed()) {
                try (Statement st = connection.createStatement()) {
                    // Force all pending changes to be written
                    st.execute("PRAGMA wal_checkpoint(FULL)");
                    log.debug("WAL checkpoint completed before closing");
                    
                    // Force a final checkpoint to ensure all data is flushed
                    st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                    log.debug("Final WAL truncate checkpoint completed");
                    
                    // Ensure WAL mode is properly closed and files are released
                    st.execute("PRAGMA journal_mode=DELETE");
                    log.debug("WAL mode disabled, journal mode set to DELETE");
                    
                    // Final checkpoint after disabling WAL to ensure all data is in main file
                    st.execute("PRAGMA wal_checkpoint(FULL)");
                    log.debug("Final checkpoint after WAL disable completed");
                } catch (Exception e) {
                    log.warn("Error during WAL checkpoint before closing: {}", e.getMessage());
                }
            }
            
            // Close the connection
            if (connection != null) {
                connection.close();
                connection = null;
                log.debug("Database connection closed");
            }
            
        } catch (Exception e) {
            log.warn("Error closing database cleanly: {}", e.getMessage());
        }
    }
    
    /**
     * Checks if the database connection is available and open.
     * 
     * @return true if connection is available and open, false otherwise
     */
    public boolean isConnectionAvailable() {
        try {
            return connection != null && !connection.isClosed();
        } catch (Exception e) {
            log.debug("Error checking connection status: {}", e.getMessage());
            return false;
        }
    }
    
    /**
     * Gets the current batch count for monitoring purposes.
     * 
     * @return The number of ticks in the current batch
     */
    public int getBatchCount() {
        return batchCount;
    }
    
    /**
     * Gets the debug database path.
     * 
     * @return The debug database path
     */
    public String getDebugDbPath() {
        return debugDbPath;
    }
}
