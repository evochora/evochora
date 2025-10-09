/*
 * Copyright (c) 2024-Present Perracodex. Use of this source code is governed by an MIT license.
 */

package org.evochora.datapipeline.resources.database;

import com.typesafe.config.Config;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.common.protobuf.ProtobufConverter;
import org.evochora.datapipeline.common.utils.RateBucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * H2 database implementation using HikariCP for connection pooling.
 */
public class H2Database extends AbstractDatabaseResource {

    private static final Logger log = LoggerFactory.getLogger(H2Database.class);
    private final HikariDataSource dataSource;
    private final AtomicLong diskWrites = new AtomicLong(0);
    private final Map<String, RateBucket> rateBuckets = new ConcurrentHashMap<>();

    public H2Database(String name, Config options) {
        super(name, options);

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(getJdbcUrl(options));
        hikariConfig.setMaximumPoolSize(options.hasPath("maxPoolSize") ? options.getInt("maxPoolSize") : 10);
        hikariConfig.setMinimumIdle(options.hasPath("minIdle") ? options.getInt("minIdle") : 2);
        this.dataSource = new HikariDataSource(hikariConfig);
        int metricsWindowSizeMs = options.hasPath("metricsWindowSizeMs") ? options.getInt("metricsWindowSizeMs") : 5000;
        rateBuckets.put("disk_writes", new RateBucket(metricsWindowSizeMs));
    }

    private String getJdbcUrl(Config options) {
        if (options.hasPath("jdbcUrl")) {
            return options.getString("jdbcUrl");
        }
        if (!options.hasPath("dataDirectory")) {
            throw new IllegalArgumentException("Either 'jdbcUrl' or 'dataDirectory' must be configured for H2Database.");
        }
        String dataDir = options.getString("dataDirectory");
        String expandedPath = expandPath(dataDir);
        log.debug("Expanded dataDirectory: '{}' -> '{}'", dataDir, expandedPath);
        return "jdbc:h2:" + expandedPath + "/evochora;MODE=PostgreSQL";
    }

    private static String expandPath(String path) {
        if (path == null || !path.contains("${")) return path;
        StringBuilder result = new StringBuilder();
        int pos = 0;
        while (pos < path.length()) {
            int startVar = path.indexOf("${", pos);
            if (startVar == -1) {
                result.append(path.substring(pos));
                break;
            }
            result.append(path.substring(pos, startVar));
            int endVar = path.indexOf("}", startVar + 2);
            if (endVar == -1) throw new IllegalArgumentException("Unclosed variable in path: " + path);
            String varName = path.substring(startVar + 2, endVar);
            String value = System.getProperty(varName, System.getenv(varName));
            if (value == null) throw new IllegalArgumentException("Undefined variable '${" + varName + "}' in path: " + path);
            result.append(value);
            pos = endVar + 1;
        }
        return result.toString();
    }

    @Override
    protected Object acquireDedicatedConnection() throws Exception {
        Connection conn = dataSource.getConnection();
        conn.setAutoCommit(false);
        return conn;
    }

    @Override
    protected String toSchemaName(String simulationRunId) {
        return "sim_" + simulationRunId.replace("-", "_");
    }

    @Override
    protected void doSetSchema(Object connection, String schemaName) throws Exception {
        ((Connection) connection).createStatement().execute("SET SCHEMA " + schemaName);
    }

    @Override
    protected void doCreateSchema(Object connection, String schemaName) throws Exception {
        Connection conn = (Connection) connection;
        conn.createStatement().execute("CREATE SCHEMA IF NOT EXISTS " + schemaName);
        conn.commit();
    }

    @Override
    protected void doInsertMetadata(Object connection, SimulationMetadata metadata) throws Exception {
        Connection conn = (Connection) connection;
        try {
            conn.createStatement().execute("CREATE TABLE IF NOT EXISTS metadata (\"key\" VARCHAR PRIMARY KEY, \"value\" JSON)");
            Map<String, String> kvPairs = new HashMap<>();
            kvPairs.put("environment", ProtobufConverter.toJson(metadata.getEnvironment()));
            String simInfoJson = String.format("{\"runId\":\"%s\",\"startTime\":%d,\"seed\":%d}",
                    metadata.getSimulationRunId(), metadata.getStartTimeMs(), metadata.getInitialSeed());
            kvPairs.put("simulation_info", simInfoJson);

            PreparedStatement stmt = conn.prepareStatement("MERGE INTO metadata (\"key\", \"value\") KEY(\"key\") VALUES (?, ?)");
            for (Map.Entry<String, String> entry : kvPairs.entrySet()) {
                stmt.setString(1, entry.getKey());
                stmt.setString(2, entry.getValue());
                stmt.addBatch();
            }
            stmt.executeBatch();
            conn.commit();
            rowsInserted.addAndGet(kvPairs.size());
            queriesExecuted.incrementAndGet();
            diskWrites.incrementAndGet();
            rateBuckets.get("disk_writes").record(System.currentTimeMillis());
        } catch (SQLException e) {
            try {
                conn.rollback();
            } catch (SQLException re) {
                log.warn("Rollback failed", re);
            }
            recordError("INSERT_METADATA_FAILED", "Failed to insert metadata", "Error: " + e.getMessage());
            throw e;
        }
    }

    @Override
    protected void addCustomMetrics(Map<String, Number> metrics) {
        metrics.put("h2_disk_writes_per_sec", rateBuckets.get("disk_writes").getRate(System.currentTimeMillis()));
        if (dataSource != null && !dataSource.isClosed()) {
            metrics.put("h2_pool_active_connections", dataSource.getHikariPoolMXBean().getActiveConnections());
            metrics.put("h2_pool_idle_connections", dataSource.getHikariPoolMXBean().getIdleConnections());
        }
    }

    public void stop() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}