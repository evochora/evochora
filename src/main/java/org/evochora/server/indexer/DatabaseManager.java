package org.evochora.server.indexer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Manages database connections and operations for the DebugIndexer.
 * Handles connection creation, setup, WAL management, and cleanup.
 */
public class DatabaseManager {
    
    private static final Logger log = LoggerFactory.getLogger(DatabaseManager.class);
    
    private final String debugDbPath;
    private final int batchSize;
    private Connection connection;
    private PreparedStatement tickInsertStatement;
    private int batchCount = 0; // Count of ticks in current batch
    private int actualBatchCount = 0; // Count of actual batches executed
    
    public DatabaseManager(String debugDbPath) {
        this(debugDbPath, 1000); // Default batch size for backward compatibility
    }
    
    public DatabaseManager(String debugDbPath, int batchSize) {
        this.debugDbPath = debugDbPath;
        this.batchSize = batchSize;
    }
    
    /**
     * Creates an optimized database connection with PRAGMA settings.
     * This method is used for all database connections to ensure consistent performance optimizations.
     * 
     * @param pathOrUrl The database path or JDBC URL
     * @return A configured database connection with performance optimizations
     * @throws Exception if connection creation fails
     */
    public Connection createOptimizedConnection(String pathOrUrl) throws Exception {
        Connection conn;
        
        if (pathOrUrl.startsWith("jdbc:")) {
            conn = DriverManager.getConnection(pathOrUrl);
        } else if (pathOrUrl.contains("memdb_") || pathOrUrl.contains("test_")) {
            // This is a test database, use in-memory mode
            conn = DriverManager.getConnection("jdbc:sqlite:file:" + pathOrUrl + "?mode=memory&cache=shared");
        } else {
            // For production file paths, use regular SQLite
            conn = DriverManager.getConnection("jdbc:sqlite:" + pathOrUrl);
        }
        
        // Apply performance optimizations
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL"); // Write-Ahead Logging for better concurrency
            st.execute("PRAGMA synchronous=NORMAL"); // Faster writes
            st.execute("PRAGMA cache_size=10000"); // Larger cache
            st.execute("PRAGMA temp_store=MEMORY"); // Use memory for temp tables
        }
        
        return conn;
    }

    /**
     * Creates a database connection for the given path or URL.
     * Handles both JDBC URLs and file paths with appropriate optimizations.
     * 
     * @param pathOrUrl The database path or JDBC URL
     * @return A configured database connection
     * @throws Exception if connection creation fails
     * @deprecated Use createOptimizedConnection() instead for better performance
     */
    @Deprecated
    public Connection createConnection(String pathOrUrl) throws Exception {
        return createOptimizedConnection(pathOrUrl);
    }
    
    /**
     * Sets up the debug database with tables and performance optimizations.
     * Creates the database schema and applies SQLite performance hints.
     * 
     * @throws Exception if database setup fails
     */
    public void setupDebugDatabase() throws Exception {
        connection = createOptimizedConnection(debugDbPath);
        
        // Create tables
        try (Statement st = connection.createStatement()) {
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
        
        // Only proceed if statement is still valid
        if (tickInsertStatement == null) {
            throw new IllegalStateException("Database statement is not available - database may be closed");
        }
        
        try {
            // Check if statement is still valid before using it
            if (tickInsertStatement != null && tickInsertStatement.isClosed()) {
                log.warn("Statement is closed, recreating for tick {}", tickNumber);
                tickInsertStatement = connection.prepareStatement(
                    "INSERT OR REPLACE INTO prepared_ticks(tick_number, tick_data_json) VALUES (?, ?)");
            }
            
            tickInsertStatement.setLong(1, tickNumber);
            tickInsertStatement.setString(2, tickDataJson);
            tickInsertStatement.addBatch();
        } catch (SQLException e) {
            log.warn("Failed to add tick {} to batch: {} - statement may be closed", tickNumber, e.getMessage());
            // Mark statement as invalid to prevent further attempts
            tickInsertStatement = null;
            // Mark database as unhealthy since statement is closed
            throw new IllegalStateException("Database statement is not available - database may be closed", e);
        }
        
        // Execute batch every batchSize ticks for optimal performance
        if (++batchCount % batchSize == 0) {
            // Only execute batch if statement still exists and is valid (avoid race condition during shutdown)
            if (tickInsertStatement != null) {
                try {
                    // Check if statement is still valid before executing batch
                    if (tickInsertStatement.isClosed()) {
                        log.warn("Statement is closed during batch execution, skipping batch for safety");
                        tickInsertStatement = null;
                        return;
                    }
                    
                    tickInsertStatement.executeBatch();
                    actualBatchCount++; // Increment actual batch count
                    if (tickInsertStatement != null) { // Added null check here too
                        tickInsertStatement.clearBatch();
                    }
                    log.debug("Executed batch of {} ticks, total processed: {}", batchSize, batchCount);
                } catch (Exception e) {
                    log.warn("Failed to execute batch during processing: {} - statement may be closed", e.getMessage());
                    // Mark statement as invalid to prevent further attempts
                    tickInsertStatement = null;
                }
            }
        }
    }
    
    /**
     * Executes any remaining batch operations and resets the batch counter.
     * 
     * @throws Exception if batch execution fails
     */
    public void executeRemainingBatch() throws Exception {
        if (tickInsertStatement != null && batchCount > 0) {
            try {
                int[] results = tickInsertStatement.executeBatch();
                actualBatchCount++; // Increment actual batch count
                log.debug("Executed final batch of {} ticks, result: {}", batchCount, java.util.Arrays.toString(results));
                // Only clear batch if statement still exists (avoid race condition during shutdown)
                if (tickInsertStatement != null) {
                    tickInsertStatement.clearBatch();
                }
                batchCount = 0; // Reset batch counter after execution
                // Note: actualBatchCount is not reset here as it tracks total batches executed
            } catch (Exception e) {
                log.warn("Failed to execute remaining batch during shutdown: {} - statement may be closed", e.getMessage());
                // Mark statement as invalid to prevent further attempts
                tickInsertStatement = null;
                batchCount = 0; // Reset batch counter even if execution failed
                // Note: actualBatchCount is not reset here as it tracks total batches executed
            }
        }
    }
    
    /**
     * Closes the database connection and performs WAL cleanup.
     * Ensures all data is properly flushed and WAL files are released.
     */
    public void closeQuietly() {
        log.debug("DEBUG: closeQuietly() called - batchCount: {}, statement null: {}", 
                 batchCount, tickInsertStatement == null);
        try {
            // First, execute any remaining batch operations
            if (tickInsertStatement != null && batchCount > 0) {
                log.debug("DEBUG: Executing remaining batch before close");
                // Always execute remaining batch, regardless of batchCount value
                executeRemainingBatch();
            }
            
            if (tickInsertStatement != null) {
                log.debug("DEBUG: Closing tickInsertStatement");
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
     * @return The number of actual batches executed
     */
    public int getBatchCount() {
        return actualBatchCount;
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
