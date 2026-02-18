package org.evochora.cli.cleanup;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

/**
 * Cleans up database schemas for simulation runs.
 * <p>
 * Schema naming: SIM_{YYYYMMDD}_{HHmmssSS}_{UUID with underscores and uppercase}
 * <p>
 * Example: SIM_20260117_22042059_D59177FC_1EBA_4CE8_85A6_36BD2032E3CB
 */
public class DatabaseCleaner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseCleaner.class);

    private static final String CHUNK_DIR_CONFIG_PATH =
            "pipeline.resources.index-database.options.h2EnvironmentStrategy.options.chunkDirectory";

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final Path chunkDirectory;

    /**
     * Creates a new DatabaseCleaner.
     *
     * @param config application configuration
     */
    public DatabaseCleaner(Config config) {
        Config dbConfig = config.getConfig("pipeline.database");
        this.jdbcUrl = dbConfig.getString("jdbcUrl");
        this.username = dbConfig.getString("username");
        this.password = dbConfig.getString("password");
        this.chunkDirectory = config.hasPath(CHUNK_DIR_CONFIG_PATH)
                ? Path.of(config.getString(CHUNK_DIR_CONFIG_PATH))
                : null;
    }

    /**
     * Lists all simulation run IDs in the database.
     * Converts schema names to normalized run ID format.
     *
     * @return list of run IDs (normalized format)
     */
    public List<String> listRunIds() {
        List<String> runIds = new ArrayList<>();

        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME LIKE 'SIM_%'")) {

            while (rs.next()) {
                String schemaName = rs.getString(1);
                String runId = schemaNameToRunId(schemaName);
                if (runId != null) {
                    runIds.add(runId);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to list database schemas: {}", e.getMessage());
        }

        return runIds;
    }

    /**
     * Performs cleanup on database schemas.
     *
     * @param toKeep run IDs to keep
     * @param toDelete run IDs to delete
     * @param force if true, actually delete; if false, dry-run only
     * @param out output writer for progress
     * @return statistics with kept and deleted counts
     */
    public CleanupService.AreaStats cleanup(List<String> toKeep, List<String> toDelete, boolean force, PrintWriter out) {
        out.printf("Database (%s):%n", jdbcUrl);

        // Build mapping from runId to schemaName
        Map<String, String> runIdToSchema = new HashMap<>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME LIKE 'SIM_%'")) {

            while (rs.next()) {
                String schemaName = rs.getString(1);
                String runId = schemaNameToRunId(schemaName);
                if (runId != null) {
                    runIdToSchema.put(runId, schemaName);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to list database schemas: {}", e.getMessage());
            out.printf("  ERROR: %s%n%n", e.getMessage());
            return new CleanupService.AreaStats(0, 0);
        }

        int keepCount = 0;
        int deleteCount = 0;

        // Show what will be kept
        for (String runId : toKeep) {
            String schemaName = runIdToSchema.get(runId);
            if (schemaName != null) {
                out.printf("  %s KEEP   %s%n", "\u2713", schemaName);
                keepCount++;
            }
        }

        // Show/delete what will be removed
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             Statement stmt = conn.createStatement()) {

            for (String runId : toDelete) {
                String schemaName = runIdToSchema.get(runId);
                if (schemaName != null) {
                    if (force) {
                        try {
                            stmt.execute("DROP SCHEMA \"" + schemaName + "\" CASCADE");
                            boolean chunksClean = deleteChunkDirectory(schemaName);
                            if (chunksClean) {
                                out.printf("  %s DELETE %s (deleted)%n", "\u2717", schemaName);
                            } else {
                                out.printf("  %s DELETE %s (deleted, chunk cleanup failed)%n", "\u2717", schemaName);
                            }
                            deleteCount++;
                        } catch (SQLException e) {
                            out.printf("  %s DELETE %s (FAILED: %s)%n", "\u2717", schemaName, e.getMessage());
                            log.error("Failed to drop schema {}: {}", schemaName, e.getMessage());
                        }
                    } else {
                        out.printf("  %s DELETE %s%n", "\u2717", schemaName);
                        deleteCount++;
                    }
                }
            }
        } catch (SQLException e) {
            log.error("Database connection failed: {}", e.getMessage());
        }

        out.println();
        return new CleanupService.AreaStats(keepCount, deleteCount);
    }

    /**
     * Deletes the chunk file directory for a schema, if it exists.
     *
     * @param schemaName the H2 schema name (used as subdirectory name)
     * @return {@code true} if no chunk directory existed or it was fully deleted,
     *         {@code false} if deletion partially or fully failed
     */
    private boolean deleteChunkDirectory(String schemaName) {
        if (chunkDirectory == null) {
            return true;
        }
        Path schemaDir = chunkDirectory.resolve(schemaName).normalize();
        if (!schemaDir.startsWith(chunkDirectory)) {
            log.warn("Skipping chunk cleanup for schema '{}': resolved path escapes base directory", schemaName);
            return false;
        }
        if (!Files.exists(schemaDir)) {
            return true;
        }
        boolean[] success = {true};
        try (Stream<Path> walk = Files.walk(schemaDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    success[0] = false;
                    log.warn("Failed to delete chunk file {}: {}", path, e.getMessage());
                }
            });
            log.debug("Deleted chunk directory: {}", schemaDir);
        } catch (IOException e) {
            log.warn("Failed to walk chunk directory {}: {}", schemaDir, e.getMessage());
            return false;
        }
        return success[0];
    }

    /**
     * Converts a database schema name to a normalized run ID.
     * <p>
     * SIM_20260117_22042059_D59177FC_1EBA_4CE8_85A6_36BD2032E3CB
     * -> 20260117-22042059-d59177fc-1eba-4ce8-85a6-36bd2032e3cb
     *
     * @param schemaName the database schema name
     * @return normalized run ID, or null if not a valid simulation schema
     */
    private String schemaNameToRunId(String schemaName) {
        if (!schemaName.startsWith("SIM_")) {
            return null;
        }

        // Remove SIM_ prefix
        String rest = schemaName.substring(4);

        // Replace underscores with dashes and convert to lowercase
        return rest.replace('_', '-').toLowerCase();
    }

    /**
     * Converts a normalized run ID to a database schema name.
     * <p>
     * 20260117-22042059-d59177fc-1eba-4ce8-85a6-36bd2032e3cb
     * -> SIM_20260117_22042059_D59177FC_1EBA_4CE8_85A6_36BD2032E3CB
     *
     * @param runId the normalized run ID
     * @return database schema name
     */
    @SuppressWarnings("unused")
    private String runIdToSchemaName(String runId) {
        return "SIM_" + runId.replace('-', '_').toUpperCase();
    }

    /**
     * Attempts to compact the H2 database to reclaim disk space.
     * <p>
     * This will only succeed if there are no other active connections to the database.
     * If other connections exist, the method returns a failure result without disconnecting them.
     *
     * @return result indicating success or failure with explanation
     */
    public CompactionResult compact() {
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             Statement stmt = conn.createStatement()) {

            // Check for other active sessions (excluding our own)
            int otherSessions = 0;
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS WHERE SESSION_ID != SESSION_ID()")) {
                if (rs.next()) {
                    otherSessions = rs.getInt(1);
                }
            }

            if (otherSessions > 0) {
                return new CompactionResult(false,
                    String.format("%d active connection(s) detected. Cannot compact while database is in use.",
                        otherSessions));
            }

            // No other connections, safe to compact
            stmt.execute("SHUTDOWN COMPACT");
            return new CompactionResult(true, "Database compacted successfully.");

        } catch (SQLException e) {
            log.error("Database compaction failed: {}", e.getMessage());
            return new CompactionResult(false, "Compaction failed: " + e.getMessage());
        }
    }
}
