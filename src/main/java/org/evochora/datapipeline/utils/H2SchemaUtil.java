package org.evochora.datapipeline.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * H2-specific utility for schema management in simulation runs.
 * <p>
 * This utility encapsulates ALL H2 schema operations, including:
 * <ul>
 *   <li>Schema name sanitization (run ID → H2-compliant identifier)</li>
 *   <li>Schema creation with H2 bug workarounds</li>
 *   <li>Schema switching (SET SCHEMA)</li>
 * </ul>
 * <p>
 * <strong>H2-Specific Design:</strong>
 * Other databases (PostgreSQL, MySQL) have different schema naming rules and SQL syntax.
 * This utility is intentionally H2-specific and should NOT be used for other databases.
 * <p>
 * <strong>Usage:</strong>
 * <pre>
 * // In H2Database, H2TopicResource, etc.
 * Connection conn = ...;
 * String runId = "20251006143025-550e8400-e29b-41d4-a716-446655440000";
 * 
 * H2SchemaUtil.createSchemaIfNotExists(conn, runId);
 * H2SchemaUtil.setSchema(conn, runId);
 * </pre>
 */
public final class H2SchemaUtil {
    
    private static final Logger log = LoggerFactory.getLogger(H2SchemaUtil.class);
    
    private H2SchemaUtil() {
        // Utility class - prevent instantiation
    }
    
    /**
     * Converts simulation run ID to H2-compliant schema name.
     * <p>
     * <strong>Sanitization Rules:</strong>
     * <ul>
     *   <li>Prepends "sim_" prefix</li>
     *   <li>Replaces all non-alphanumeric characters with underscore</li>
     *   <li>Converts to uppercase (H2 stores identifiers in uppercase)</li>
     *   <li>Validates length (H2 identifier limit: 256 characters)</li>
     * </ul>
     * <p>
     * <strong>Example:</strong>
     * <pre>
     * 20251006143025-550e8400-e29b-41d4-a716-446655440000
     * → SIM_20251006143025_550E8400_E29B_41D4_A716_446655440000
     * </pre>
     * <p>
     * <strong>Visibility:</strong> Package-private. Use {@link #setupRunSchema} for schema setup.
     *
     * @param simulationRunId Raw simulation run ID (must not be null or empty).
     * @return Sanitized schema name in uppercase.
     * @throws IllegalArgumentException if runId is null, empty, or results in name exceeding 256 chars.
     */
    static String toSchemaName(String simulationRunId) {
        if (simulationRunId == null || simulationRunId.isEmpty()) {
            throw new IllegalArgumentException("Simulation run ID cannot be null or empty");
        }
        
        // Sanitize: replace all non-alphanumeric characters with underscore
        String sanitized = "sim_" + simulationRunId.replaceAll("[^a-zA-Z0-9]", "_");
        
        // Validate length (H2 identifier limit is 256 chars)
        if (sanitized.length() > 256) {
            throw new IllegalArgumentException(
                "Schema name too long (" + sanitized.length() + " chars, max 256). " +
                "RunId: " + simulationRunId.substring(0, Math.min(50, simulationRunId.length())) + "..."
            );
        }
        
        // H2 is case-insensitive and stores identifiers in uppercase
        // Return uppercase for consistency with H2's internal representation
        return sanitized.toUpperCase();
    }
    
    /**
     * Creates an H2 schema for the given simulation run if it doesn't exist.
     * <p>
     * <strong>H2 Bug Workaround (v2.2.224):</strong>
     * "CREATE SCHEMA IF NOT EXISTS" can fail with "object already exists" when multiple
     * connections create the same schema concurrently. This method catches that specific
     * error and treats it as success (schema was created by another connection).
     * <p>
     * <strong>Transaction Handling:</strong>
     * This method commits on success and rolls back on failure. The connection should
     * be in manual commit mode (autoCommit=false).
     * <p>
     * <strong>Visibility:</strong> Package-private. Use {@link #setupRunSchema} for schema setup.
     *
     * @param connection H2 JDBC connection (must be in manual commit mode).
     * @param simulationRunId Raw simulation run ID.
     * @throws SQLException if schema creation fails (excluding the known H2 bug).
     */
    static void createSchemaIfNotExists(Connection connection, String simulationRunId) throws SQLException {
        String schemaName = toSchemaName(simulationRunId);
        
        try {
            connection.createStatement().execute("CREATE SCHEMA IF NOT EXISTS \"" + schemaName + "\"");
            connection.commit();
            log.debug("Created H2 schema: {}", schemaName);
        } catch (SQLException e) {
            // Workaround for H2 bug in version 2.2.224:
            // "CREATE SCHEMA IF NOT EXISTS" can fail with "object already exists"
            // when multiple connections create the same schema concurrently
            if (e.getMessage() != null && e.getMessage().contains("object already exists")) {
                // Expected in parallel scenarios - schema created by another connection
                connection.rollback();  // Clean up failed transaction
                log.debug("Schema '{}' already exists (created by another connection)", schemaName);
            } else {
                throw e;  // Unexpected SQL error - propagate to caller
            }
        }
    }
    
    /**
     * Sets the active schema for the given H2 connection.
     * <p>
     * All subsequent SQL statements on this connection will execute in the specified schema.
     * <p>
     * <strong>Note:</strong> This does NOT create the schema. The schema must already exist
     * (e.g., created by {@link #setupRunSchema}).
     * <p>
     * <strong>Use Cases:</strong>
     * <ul>
     *   <li>Topic delegates switching their own connection to a run-specific schema</li>
     *   <li>Database resources switching to a schema after it was created</li>
     * </ul>
     *
     * @param connection H2 JDBC connection.
     * @param simulationRunId Raw simulation run ID.
     * @throws SQLException if SET SCHEMA fails (e.g., schema doesn't exist).
     */
    public static void setSchema(Connection connection, String simulationRunId) throws SQLException {
        String schemaName = toSchemaName(simulationRunId);
        connection.createStatement().execute("SET SCHEMA \"" + schemaName + "\"");
        log.debug("Set H2 schema to: {}", schemaName);
    }
    
    /**
     * Performs a complete schema setup: creates schema, switches to it, and executes table creation.
     * <p>
     * This method is atomic - either all steps succeed (commit), or none do (rollback).
     * It combines {@link #createSchemaIfNotExists}, {@link #setSchema}, and custom table creation
     * into one transaction-safe operation.
     * <p>
     * <strong>Transaction Management:</strong>
     * This method manages transactions internally:
     * <ul>
     *   <li>Saves the current auto-commit state</li>
     *   <li>Disables auto-commit for the duration of setup</li>
     *   <li>Commits on success</li>
     *   <li>Rolls back on error</li>
     *   <li>Restores the original auto-commit state</li>
     * </ul>
     * <p>
     * <strong>Usage Example:</strong>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection()) {
     *     String schemaName = H2SchemaUtil.setupRunSchema(conn, "SIM_20250101_UUID", this::createTables);
     *     log.info("Setup complete for schema: {}", schemaName);
     * }
     * 
     * private void createTables(Connection conn) throws SQLException {
     *     try (Statement stmt = conn.createStatement()) {
     *         stmt.execute("CREATE TABLE IF NOT EXISTS my_table (id BIGINT PRIMARY KEY)");
     *     }
     * }
     * }</pre>
     *
     * @param connection The database connection (must not be null).
     * @param simulationRunId The simulation run ID (used as schema name).
     * @param callback Callback to create tables and setup resources in the schema (must not be null).
     * @throws SQLException if schema creation, table creation, or transaction management fails.
     * @throws NullPointerException if connection or callback is null.
     */
    public static void setupRunSchema(
        Connection connection,
        String simulationRunId,
        SchemaSetupCallback callback
    ) throws SQLException {
        
        if (connection == null) {
            throw new NullPointerException("Connection cannot be null");
        }
        if (callback == null) {
            throw new NullPointerException("Callback cannot be null");
        }
        
        boolean wasAutoCommit = connection.getAutoCommit();
        
        try {
            // Start transaction
            connection.setAutoCommit(false);
            
            log.debug("Setting up schema for run: {}", simulationRunId);
            
            // Step 1: Create schema (if not exists)
            createSchemaIfNotExists(connection, simulationRunId);
            
            // Step 2: Switch to schema
            setSchema(connection, simulationRunId);
            
            // Step 3: Execute caller's setup logic (tables + triggers)
            String schemaName = toSchemaName(simulationRunId);
            callback.setup(connection, schemaName);
            
            // Commit transaction
            connection.commit();
            
            log.debug("Schema setup complete for run: {}", simulationRunId);
            
        } catch (Exception e) {
            // Rollback on any error
            try {
                connection.rollback();
                log.warn("Schema setup failed for run: {}, rolled back - Cause: {}", simulationRunId, e.getMessage());
            } catch (SQLException rollbackEx) {
                log.error("Failed to rollback after schema setup error for run: {}", simulationRunId);
            }
            throw new SQLException("Schema setup failed for run: " + simulationRunId, e);
        } finally {
            // Restore original auto-commit state
            try {
                connection.setAutoCommit(wasAutoCommit);
            } catch (SQLException e) {
                log.warn("Failed to restore auto-commit state after schema setup for run: {}", simulationRunId);
            }
        }
    }
    
    /**
     * Performs schema cleanup: deregisters triggers and performs cleanup operations.
     * <p>
     * This method provides a consistent way to cleanup schema-specific resources.
     * While it currently may not perform SQL operations, it provides a connection
     * for future extensibility (e.g., DROP TRIGGER, DROP SCHEMA).
     * <p>
     * <strong>Usage Example:</strong>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection()) {
     *     H2SchemaUtil.cleanupRunSchema(conn, "SIM_20250101_UUID",
     *         (connection, schemaName) -> {
     *             H2InsertTrigger.deregisterNotificationQueue(topicName, schemaName);
     *         });
     * }
     * }</pre>
     *
     * @param connection The database connection (must not be null).
     * @param simulationRunId The simulation run ID (used as schema name).
     * @param callback Callback to perform cleanup operations (must not be null).
     * @throws SQLException if cleanup fails.
     * @throws NullPointerException if connection or callback is null.
     */
    public static void cleanupRunSchema(
        Connection connection,
        String simulationRunId,
        SchemaCleanupCallback callback
    ) throws SQLException {
        
        if (connection == null) {
            throw new NullPointerException("Connection cannot be null");
        }
        if (callback == null) {
            throw new NullPointerException("Callback cannot be null");
        }
        
        log.debug("Cleaning up schema for run: {}", simulationRunId);
        
        String schemaName = toSchemaName(simulationRunId);
        callback.cleanup(connection, schemaName);
        
        log.debug("Schema cleanup complete for run: {}", simulationRunId);
    }
    
    /**
     * Functional interface for schema setup callbacks.
     * <p>
     * Implementations should create all necessary tables, indexes, constraints,
     * and register triggers/listeners in the current schema.
     * Use {@code CREATE TABLE IF NOT EXISTS} for idempotency.
     */
    @FunctionalInterface
    public interface SchemaSetupCallback {
        /**
         * Performs setup operations in the current schema.
         *
         * @param connection The database connection (already switched to target schema).
         * @param schemaName The sanitized schema name (uppercase, H2-compliant).
         * @throws SQLException if setup fails.
         */
        void setup(Connection connection, String schemaName) throws SQLException;
    }
    
    /**
     * Executes a CREATE TABLE IF NOT EXISTS statement with H2-specific error handling.
     * <p>
     * <strong>H2 Bug Workaround (v2.2.224):</strong>
     * "CREATE TABLE IF NOT EXISTS" can fail with "object already exists" when multiple
     * connections create the same table concurrently. This method catches that specific
     * error and treats it as success (table was created by another connection).
     * <p>
     * This is the recommended way to create tables in H2 when competing consumers
     * may initialize concurrently.
     *
     * @param statement The statement to execute with (must not be null).
     * @param sql The CREATE TABLE IF NOT EXISTS SQL (must not be null).
     * @param tableName The table name for logging (must not be null).
     * @throws SQLException if table creation fails for reasons other than "already exists".
     */
    public static void executeTableCreation(Statement statement, String sql, String tableName) throws SQLException {
        if (statement == null || sql == null || tableName == null) {
            throw new IllegalArgumentException("statement, sql, and tableName must not be null");
        }
        
        try {
            statement.execute(sql);
            log.debug("Created table: {}", tableName);
        } catch (SQLException e) {
            // H2 bug workaround: "CREATE TABLE IF NOT EXISTS" can fail with "object already exists"
            // Error code 42101 = Table/View already exists
            // Error code 50000 = General error (may include "object already exists")
            if ((e.getErrorCode() == 42101 || e.getErrorCode() == 50000) 
                && e.getMessage() != null 
                && e.getMessage().contains("already exists")) {
                log.debug("Table '{}' already exists (created by another connection)", tableName);
            } else {
                throw e;  // Re-throw if it's a different error
            }
        }
    }
    
    /**
     * Functional interface for schema cleanup callbacks.
     * <p>
     * Implementations should deregister triggers/listeners, drop objects,
     * or perform other cleanup operations.
     */
    @FunctionalInterface
    public interface SchemaCleanupCallback {
        /**
         * Performs cleanup operations for a schema.
         *
         * @param connection The database connection.
         * @param schemaName The sanitized schema name (uppercase, H2-compliant).
         * @throws SQLException if cleanup fails.
         */
        void cleanup(Connection connection, String schemaName) throws SQLException;
    }
}

