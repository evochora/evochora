package org.evochora.datapipeline.services.analytics.plugins;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.analytics.IAnalyticsContext;
import org.evochora.datapipeline.api.analytics.ManifestEntry;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("unit")
class PopulationMetricsPluginTest {

    @TempDir
    Path tempDir;

    @Test
    void testProcessBatch_GeneratesParquet() throws Exception {
        // 1. Setup
        PopulationMetricsPlugin plugin = new PopulationMetricsPlugin();
        Config config = ConfigFactory.parseMap(Map.of("metricId", "pop"));
        plugin.configure(config);

        IAnalyticsContext context = mock(IAnalyticsContext.class);
        when(context.getTempDirectory()).thenReturn(tempDir);
        when(context.getMetadata()).thenReturn(SimulationMetadata.getDefaultInstance());

        // Capture output
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        when(context.openArtifactStream(eq("pop"), eq("raw"), anyString())).thenReturn(outputStream);

        plugin.initialize(context);

        // 2. Create Input Data
        List<TickData> batch = new ArrayList<>();
        // Tick 1: 2 organisms, 1 created total (wait, total >= alive)
        batch.add(createTick(1, 10, 2)); 
        // Tick 2: 3 organisms, 12 created total
        batch.add(createTick(2, 12, 3));

        // 3. Execute
        plugin.processBatch(batch);

        // 4. Verify Output (Parquet Magic Bytes)
        byte[] parquetBytes = outputStream.toByteArray();
        assertThat(parquetBytes.length).isGreaterThan(0);
        String magic = new String(parquetBytes, 0, 4, StandardCharsets.US_ASCII);
        assertThat(magic).isEqualTo("PAR1");

        // 5. Verify Data Content (Load back via DuckDB)
        // We write the bytes to a file so DuckDB can read it
        Path resultFile = tempDir.resolve("result.parquet");
        java.nio.file.Files.write(resultFile, parquetBytes);

        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement stmt = conn.createStatement()) {
                ResultSet rs = stmt.executeQuery("SELECT * FROM '" + resultFile.toAbsolutePath() + "' ORDER BY tick");
                
                // Row 1
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("tick")).isEqualTo(1);
                assertThat(rs.getInt("alive_count")).isEqualTo(2);
                assertThat(rs.getLong("total_dead")).isEqualTo(10 - 2); // 8
                
                // Row 2
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("tick")).isEqualTo(2);
                assertThat(rs.getInt("alive_count")).isEqualTo(3);
                assertThat(rs.getLong("total_dead")).isEqualTo(12 - 3); // 9
                
                assertThat(rs.next()).isFalse();
            }
        }
    }
    
    @Test
    void testManifestEntry() {
        PopulationMetricsPlugin plugin = new PopulationMetricsPlugin();
        plugin.configure(ConfigFactory.parseMap(Map.of("metricId", "test-pop")));
        
        ManifestEntry entry = plugin.getManifestEntry();
        assertThat(entry.id).isEqualTo("test-pop");
        assertThat(entry.dataSources).containsKey("raw");
        assertThat(entry.dataSources.get("raw")).contains("test-pop/raw/*.parquet");
        assertThat(entry.visualization.type).isEqualTo("line-chart");
    }

    private TickData createTick(long tickNum, long totalCreated, int aliveCount) {
        TickData.Builder builder = TickData.newBuilder()
                .setTickNumber(tickNum)
                .setTotalOrganismsCreated(totalCreated);
        
        for (int i = 0; i < aliveCount; i++) {
            builder.addOrganisms(OrganismState.newBuilder()
                .setOrganismId(i)
                .setEnergy(1000)
                .build());
        }
        return builder.build();
    }
}

