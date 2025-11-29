package org.evochora.datapipeline.services.analytics.plugins;

import org.evochora.datapipeline.api.analytics.AbstractAnalyticsPlugin;
import org.evochora.datapipeline.api.analytics.ManifestEntry;
import org.evochora.datapipeline.api.analytics.VisualizationHint;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;

/**
 * Generates population metrics (alive count, avg energy) in Parquet format using DuckDB.
 * <p>
 * Note: 'Dead count' is not tracked because TickData only contains living organisms.
 * To track deaths, we would need stateful tracking across ticks/batches, which is complex
 * with sampling. Thus, we focus on the snapshot state (Alive & Energy).
 */
public class PopulationMetricsPlugin extends AbstractAnalyticsPlugin {

    private static final Logger log = LoggerFactory.getLogger(PopulationMetricsPlugin.class);

    @Override
    public ManifestEntry getManifestEntry() {
        ManifestEntry entry = new ManifestEntry();
        entry.id = metricId; // e.g. "population"
        entry.name = "Population Overview";
        entry.description = "Overview of alive organisms, total deaths, and average energy over time.";
        
        entry.dataSources = new HashMap<>();
        // Default: "raw" level pointing to all parquet files in raw folder
        entry.dataSources.put("raw", metricId + "/raw/*.parquet");
        
        entry.visualization = new VisualizationHint();
        entry.visualization.type = "line-chart";
        entry.visualization.config = new HashMap<>();
        entry.visualization.config.put("x", "tick");
        entry.visualization.config.put("y", List.of("alive_count", "total_dead"));
        entry.visualization.config.put("y2", List.of("avg_energy")); // Second axis

        return entry;
    }

    @Override
    public void processBatch(List<TickData> batch) {
        if (batch.isEmpty()) return;

        // Determine batch range for filename
        long startTick = batch.get(0).getTickNumber();
        long endTick = batch.get(batch.size() - 1).getTickNumber();
        String filename = String.format("batch_%020d_%020d.parquet", startTick, endTick);
        
        log.debug("Processing batch {}-{} for population metrics", startTick, endTick);

        // Use DuckDB to aggregate and write Parquet
        // We use an in-memory DB for processing, then export to a temp file
        try {
            // 1. Setup DuckDB
            // Load driver explicitly to ensure it's registered
            Class.forName("org.duckdb.DuckDBDriver");
            
            try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
                try (Statement stmt = conn.createStatement()) {
                    
                    // 2. Create Schema
                    stmt.execute("CREATE TABLE metrics (" +
                        "tick BIGINT, " +
                        "alive_count INTEGER, " +
                        "total_dead BIGINT, " +
                        "avg_energy DOUBLE" +
                        ")");

                    // 3. Insert Data
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO metrics VALUES (?, ?, ?, ?)")) {
                        
                        for (TickData tick : batch) {
                            // Check sampling
                            if (tick.getTickNumber() % samplingInterval != 0) {
                                continue;
                            }
                            
                            long tickNum = tick.getTickNumber();
                            int alive = 0;
                            long totalEnergy = 0;
                            
                            for (OrganismState org : tick.getOrganismsList()) {
                                // SimulationEngine only sends living organisms.
                                alive++;
                                totalEnergy += org.getEnergy();
                            }
                            
                            // Calculate total dead based on monotonic counter
                            long totalCreated = tick.getTotalOrganismsCreated();
                            long totalDead = totalCreated - alive;
                            
                            double avgEnergy = alive > 0 ? (double) totalEnergy / alive : 0.0;
                            
                            ps.setLong(1, tickNum);
                            ps.setInt(2, alive);
                            ps.setLong(3, totalDead);
                            ps.setDouble(4, avgEnergy);
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }

                    // 4. Export to Parquet (Local Temp File)
                    // We need a temp file path that DuckDB can write to
                    Path tempFile = Files.createTempFile(context.getTempDirectory(), "pop_", ".parquet");
                    try {
                        // Escape path for SQL (Windows paths need escaping)
                        String exportPath = tempFile.toAbsolutePath().toString().replace("\\", "/");
                        String exportSql = String.format(
                            "COPY metrics TO '%s' (FORMAT PARQUET, CODEC 'ZSTD')", 
                            exportPath
                        );
                        stmt.execute(exportSql);
                        
                        // 5. Stream to Storage
                        // Use "raw" LOD level for now (Phase 3)
                        try (InputStream in = Files.newInputStream(tempFile);
                             OutputStream out = context.openArtifactStream(metricId, "raw", filename)) {
                            in.transferTo(out);
                        }
                        
                    } finally {
                        Files.deleteIfExists(tempFile);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to process population metrics batch", e);
        }
    }
}
