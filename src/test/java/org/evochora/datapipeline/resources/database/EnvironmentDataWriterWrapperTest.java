package org.evochora.datapipeline.resources.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.CellStateTestHelper;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import com.typesafe.config.ConfigFactory;

/**
 * Unit tests for EnvironmentDataWriterWrapper.
 * <p>
 * Tests wrapper operations, metrics collection, and error handling.
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class EnvironmentDataWriterWrapperTest {
    
    @TempDir
    Path tempDir;
    
    private H2Database database;
    private EnvironmentDataWriterWrapper wrapper;
    
    @BeforeEach
    void setUp() throws Exception {
        // Create H2Database with file-based database
        // Use forward slashes in path (works on all platforms, avoids Config parsing issues with backslashes)
        String dbPath = tempDir.toString().replace("\\", "/");
        var config = ConfigFactory.parseString("""
            jdbcUrl = "jdbc:h2:file:%s/test-wrapper"
            """.formatted(dbPath));
        
        database = new H2Database("test-db", config);
        
        // Create wrapper
        ResourceContext context = new ResourceContext("test-service", "port", "db-env-write", "test-db", Map.of());
        wrapper = (EnvironmentDataWriterWrapper) database.getWrappedResource(context);
        
        // Set schema
        wrapper.setSimulationRun("test-run");
    }
    
    @AfterEach
    void tearDown() throws Exception {
        if (wrapper != null) {
            wrapper.close();
        }
        if (database != null) {
            database.close();
        }
    }
    
    @Test
    void testWriteEnvironmentChunks_Success() {
        // Given: A chunk with snapshot data
        TickData snapshot = TickData.newBuilder()
            .setTickNumber(1L)
            .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                CellStateTestHelper.createCellStateBuilder(0, 100, 1, 50, 0).build(),
                CellStateTestHelper.createCellStateBuilder(1, 101, 1, 60, 0).build()
            )))
            .build();
        
        TickDataChunk chunk = TickDataChunk.newBuilder()
            .setSnapshot(snapshot)
            .build();
        
        // When: Write chunk
        wrapper.writeEnvironmentChunks(List.of(chunk));
        
        // Then: Should succeed (no exception)
        assertThat(wrapper.isHealthy()).isTrue();
    }
    
    @Test
    void testWriteEnvironmentChunks_Metrics() {
        // Given: Multiple chunks
        TickData snapshot1 = TickData.newBuilder()
            .setTickNumber(1L)
            .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                CellStateTestHelper.createCellStateBuilder(0, 100, 1, 50, 0).build(),
                CellStateTestHelper.createCellStateBuilder(1, 101, 1, 60, 0).build()
            )))
            .build();
        TickData snapshot2 = TickData.newBuilder()
            .setTickNumber(100L)
            .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                CellStateTestHelper.createCellStateBuilder(2, 102, 1, 70, 0).build()
            )))
            .build();
        
        TickDataChunk chunk1 = TickDataChunk.newBuilder().setSnapshot(snapshot1).build();
        TickDataChunk chunk2 = TickDataChunk.newBuilder().setSnapshot(snapshot2).build();
        
        // When: Write chunks
        wrapper.writeEnvironmentChunks(List.of(chunk1, chunk2));
        
        // Then: Metrics should reflect 2 chunks and 1 batch
        Map<String, Number> metrics = wrapper.getMetrics();
        assertThat(metrics).containsKeys(
            "chunks_written", "batches_written", "write_errors",
            "chunks_per_second", "batches_per_second",
            "write_latency_p50_ms", "write_latency_p95_ms", "write_latency_p99_ms", "write_latency_avg_ms"
        );
        
        assertThat(metrics.get("chunks_written").longValue()).isEqualTo(2);
        assertThat(metrics.get("batches_written").longValue()).isEqualTo(1);
        assertThat(metrics.get("write_errors").longValue()).isEqualTo(0);
        
        // Latency metrics should be non-negative
        assertThat(metrics.get("write_latency_p50_ms").doubleValue()).isGreaterThanOrEqualTo(0.0);
        assertThat(metrics.get("write_latency_avg_ms").doubleValue()).isGreaterThanOrEqualTo(0.0);
    }
    
    @Test
    void testWriteEnvironmentChunks_EmptyList() {
        // Given: Empty chunk list
        
        // When: Write empty list
        wrapper.writeEnvironmentChunks(List.of());
        
        // Then: Should succeed without errors
        assertThat(wrapper.isHealthy()).isTrue();
        
        Map<String, Number> metrics = wrapper.getMetrics();
        assertThat(metrics.get("chunks_written").longValue()).isEqualTo(0);
        assertThat(metrics.get("batches_written").longValue()).isEqualTo(0);
    }
    
    @Test
    void testCollectMetrics_AllFields() {
        // Given: Wrapper with some writes
        TickData snapshot = TickData.newBuilder()
            .setTickNumber(1L)
            .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                CellStateTestHelper.createCellStateBuilder(0, 100, 1, 50, 0).build()
            )))
            .build();
        
        TickDataChunk chunk = TickDataChunk.newBuilder().setSnapshot(snapshot).build();
        wrapper.writeEnvironmentChunks(List.of(chunk));
        
        // When: Get metrics
        Map<String, Number> metrics = wrapper.getMetrics();
        
        // Then: All expected fields should be present
        assertThat(metrics).containsKeys(
            // From parent (AbstractDatabaseWrapper)
            "connection_cached",
            // From EnvironmentDataWriterWrapper
            "chunks_written",
            "batches_written",
            "write_errors",
            "chunks_per_second",
            "batches_per_second",
            "write_latency_p50_ms",
            "write_latency_p95_ms",
            "write_latency_p99_ms",
            "write_latency_avg_ms"
        );
    }
    
    @Test
    void testCreateEnvironmentDataTable_Explicit() throws Exception {
        // Given: Wrapper
        
        // When: Explicitly create environment table
        wrapper.createEnvironmentDataTable(2);
        
        // Then: Should succeed
        assertThat(wrapper.isHealthy()).isTrue();
        
        // And: Subsequent writes should work
        TickData snapshot = TickData.newBuilder()
            .setTickNumber(1L)
            .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                CellStateTestHelper.createCellStateBuilder(0, 100, 1, 50, 0).build()
            )))
            .build();
        
        TickDataChunk chunk = TickDataChunk.newBuilder().setSnapshot(snapshot).build();
        wrapper.writeEnvironmentChunks(List.of(chunk));
    }
}
