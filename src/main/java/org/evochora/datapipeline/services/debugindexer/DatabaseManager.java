package org.evochora.datapipeline.services.debugindexer;

import org.evochora.server.config.SimulationConfiguration;
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
    private final SimulationConfiguration.DatabaseConfig databaseConfig;
    private Connection connection;
    private PreparedStatement tickInsertStatement;
    private int batchCount = 0; // Count of ticks in current batch
    private int actualBatchCount = 0; // Count of actual batches executed
    private volatile boolean isClosing = false; // Flag to prevent race conditions
    private final Object connectionLock = new Object(); // Synchronization for database operations
    
    /**
     * Creates a new DatabaseManager with full configuration support.
     * @param debugDbPath The debug database path
     * @param batchSize The batch size for operations
     * @param databaseConfig Database configuration for optimizations
     */
    public DatabaseManager(String debugDbPath, int batchSize, SimulationConfiguration.DatabaseConfig databaseConfig) {
        this.debugDbPath = debugDbPath;
        this.batchSize = batchSize;
        this.databaseConfig = databaseConfig != null ? databaseConfig : new SimulationConfiguration.DatabaseConfig();
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
        // Ensure SQLite driver is loaded
        Class.forName("org.sqlite.JDBC");
        
        // Create connection
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + pathOrUrl);
        
        // Apply performance optimizations
        try (Statement stmt = conn.createStatement()) {
            // WAL mode for better concurrency
            stmt.execute("PRAGMA journal_mode=WAL");
            
            // Synchronous mode for performance vs safety trade-off
            stmt.execute("PRAGMA synchronous=NORMAL"); // Default to NORMAL for good balance
            
            // Cache size for better performance
            if (databaseConfig != null && databaseConfig.cacheSize > 0) {
                stmt.execute("PRAGMA cache_size=" + databaseConfig.cacheSize);
            } else {
                stmt.execute("PRAGMA cache_size=10000"); // Default cache size
            }
            
            // Temp store in memory for better performance
            stmt.execute("PRAGMA temp_store=MEMORY");
            
            // Optimize for performance
            stmt.execute("PRAGMA optimize");
            
            // Set busy timeout to handle concurrent access
            stmt.execute("PRAGMA busy_timeout=30000"); // 30 seconds timeout
            
            log.debug("Created optimized database connection with WAL mode and performance settings");
        }
        
        return conn;
    }
    
    /**
     * Ensures the database connection is available and properly initialized.
     * This method is thread-safe and handles connection creation and setup.
     * 
     * @return true if connection is available, false if setup failed
     */
    public boolean ensureConnection() {
        synchronized (connectionLock) {
            if (isClosing) {
                return false;
            }
            
            if (connection != null && !isConnectionClosed()) {
                return true;
            }
            
            try {
                log.debug("Creating database connection to: {}", debugDbPath);
                // Create new connection
                connection = createOptimizedConnection(debugDbPath);
                
                log.debug("Setting up database schema...");
                // Setup database schema
                setupDatabaseSchema();
                
                log.debug("Preparing insert statement...");
                // Prepare insert statement
                prepareInsertStatement();
                
                log.debug("Database connection established and schema initialized successfully");
                return true;
                
            } catch (Exception e) {
                log.error("Failed to establish database connection: {}", e.getMessage(), e);
                closeQuietly();
                return false;
            }
        }
    }
    
    /**
     * Sets up the database schema for debug data storage.
     * Creates tables for ticks, organisms, and other debug information.
     */
    private void setupDatabaseSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // Create ticks table
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS debug_ticks (
                    tick_number INTEGER PRIMARY KEY,
                    tick_data BLOB NOT NULL,
                    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
                )
                """);
            
            // Create index for performance
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_debug_ticks_tick_number ON debug_ticks(tick_number)");
            
            log.debug("Database schema initialized successfully");
        }
    }
    
    /**
     * Prepares the insert statement for batch operations.
     */
    private void prepareInsertStatement() throws SQLException {
        if (tickInsertStatement != null) {
            tickInsertStatement.close();
        }
        
        tickInsertStatement = connection.prepareStatement(
            "INSERT OR REPLACE INTO debug_ticks (tick_number, tick_data) VALUES (?, ?)"
        );
    }
    
    /**
     * Writes a prepared tick to the database using batch operations.
     * This method is thread-safe and handles batching automatically.
     * 
     * @param tickNumber The tick number
     * @param tickData The compressed tick data
     */
    public void writePreparedTick(long tickNumber, byte[] tickData) {
        synchronized (connectionLock) {
            if (isClosing || connection == null || isConnectionClosed()) {
                log.warn("Database connection not available, skipping tick {}", tickNumber);
                return;
            }
            
            try {
                tickInsertStatement.setLong(1, tickNumber);
                tickInsertStatement.setBytes(2, tickData);
                tickInsertStatement.addBatch();
                
                batchCount++;
                
                // Execute batch when it reaches the configured size
                if (batchCount >= batchSize) {
                    executeBatch();
                }
                
            } catch (SQLException e) {
                log.error("Failed to write tick {} to database: {}", tickNumber, e.getMessage(), e);
                // Mark connection as unhealthy for future operations
                closeQuietly();
            }
        }
    }
    
    /**
     * Executes the current batch of database operations.
     * This method is thread-safe and handles batch execution.
     */
    public void executeBatch() {
        synchronized (connectionLock) {
            if (isClosing || connection == null || isConnectionClosed() || batchCount == 0) {
                return;
            }
            
            try {
                tickInsertStatement.executeBatch();
                actualBatchCount++;
                
                log.debug("Executed batch of {} ticks (batch #{})", batchCount, actualBatchCount);
                
                // Reset batch counter
                batchCount = 0;
                
            } catch (SQLException e) {
                log.error("Failed to execute batch: {}", e.getMessage(), e);
                closeQuietly();
            }
        }
    }
    
    /**
     * Flushes any pending batch operations to the database.
     * This method should be called before closing the connection.
     */
    public void flush() {
        synchronized (connectionLock) {
            if (batchCount > 0) {
                executeBatch();
            }
        }
    }
    
    /**
     * Commits the WAL (Write-Ahead Log) to ensure all data is written to the main database file.
     * This method should be called before pausing or stopping the service to prevent WAL files from remaining.
     */
    public void commitWAL() {
        synchronized (connectionLock) {
            if (isClosing || connection == null || isConnectionClosed()) {
                return;
            }
            
            try {
                // First flush any pending operations
                flush();
                
                // Execute WAL checkpoint to commit all WAL data to main database
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("PRAGMA wal_checkpoint(FULL)");
                    log.debug("WAL checkpoint completed successfully");
                }
                
            } catch (SQLException e) {
                log.warn("Failed to commit WAL: {}", e.getMessage());
            }
        }
    }
    
    /**
     * Checks if the database connection is available and healthy.
     * 
     * @return true if connection is available, false otherwise
     */
    public boolean isConnectionAvailable() {
        synchronized (connectionLock) {
            return connection != null && !isConnectionClosed() && !isClosing;
        }
    }
    
    /**
     * Checks if the connection is closed.
     * 
     * @return true if connection is closed, false otherwise
     */
    private boolean isConnectionClosed() {
        try {
            return connection == null || connection.isClosed();
        } catch (SQLException e) {
            return true;
        }
    }
    
    /**
     * Closes the database connection and cleans up resources.
     * This method is thread-safe and handles cleanup gracefully.
     */
    public void close() {
        synchronized (connectionLock) {
            isClosing = true;
            
            try {
                // Flush any pending operations and commit WAL
                flush();
                commitWAL();
                
                // Close prepared statement
                if (tickInsertStatement != null) {
                    tickInsertStatement.close();
                    tickInsertStatement = null;
                }
                
                // Close connection
                if (connection != null && !connection.isClosed()) {
                    connection.close();
                    connection = null;
                }
                
                log.debug("Database connection closed successfully");
                
            } catch (SQLException e) {
                log.warn("Error closing database connection: {}", e.getMessage());
            } finally {
                isClosing = false;
            }
        }
    }
    
    /**
     * Closes the database connection quietly, ignoring any errors.
     * This method is used for cleanup in error scenarios.
     */
    public void closeQuietly() {
        try {
            close();
        } catch (Exception e) {
            log.debug("Error during quiet close: {}", e.getMessage());
        }
    }
    
    /**
     * Gets the current batch count.
     * 
     * @return The number of ticks in the current batch
     */
    public int getBatchCount() {
        synchronized (connectionLock) {
            return batchCount;
        }
    }
    
    /**
     * Gets the total number of batches executed.
     * 
     * @return The number of batches executed
     */
    public int getActualBatchCount() {
        synchronized (connectionLock) {
            return actualBatchCount;
        }
    }
}
