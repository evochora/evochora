package org.evochora.datapipeline.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;

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
     *
     * @param simulationRunId Raw simulation run ID (must not be null or empty).
     * @return Sanitized schema name in uppercase.
     * @throws IllegalArgumentException if runId is null, empty, or results in name exceeding 256 chars.
     */
    public static String toSchemaName(String simulationRunId) {
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
     *
     * @param connection H2 JDBC connection (must be in manual commit mode).
     * @param simulationRunId Raw simulation run ID.
     * @throws SQLException if schema creation fails (excluding the known H2 bug).
     */
    public static void createSchemaIfNotExists(Connection connection, String simulationRunId) throws SQLException {
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
     * <strong>Note:</strong> This does NOT create the schema. Call {@link #createSchemaIfNotExists(Connection, String)}
     * first if the schema might not exist.
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
}

