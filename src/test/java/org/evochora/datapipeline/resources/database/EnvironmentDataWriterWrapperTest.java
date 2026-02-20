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
            h2EnvironmentStrategy {
              className = "org.evochora.datapipeline.resources.database.h2.RowPerChunkStrategy"
              options { chunkDirectory = "%s/env-chunks" }
            }
            """.formatted(dbPath, dbPath));
        
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
    
    // ========================================================================
    // writeRawChunk + commitRawChunks tests
    // ========================================================================

    @Test
    void testWriteRawChunk_Success() throws Exception {
        // Given: A raw protobuf chunk
        wrapper.createEnvironmentDataTable(2);

        TickData snapshot = TickData.newBuilder()
            .setTickNumber(1L)
            .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                CellStateTestHelper.createCellStateBuilder(0, 100, 1, 50, 0).build()
            )))
            .build();
        TickDataChunk chunk = TickDataChunk.newBuilder()
            .setFirstTick(1L).setLastTick(1L).setTickCount(1)
            .setSnapshot(snapshot)
            .build();
        byte[] rawBytes = chunk.toByteArray();

        // When: Write raw chunk and commit
        wrapper.writeRawChunk(1L, 1L, 1, rawBytes);
        wrapper.commitRawChunks();

        // Then: Metrics reflect the write
        Map<String, Number> metrics = wrapper.getMetrics();
        assertThat(metrics.get("chunks_written").longValue()).isEqualTo(1);
        assertThat(metrics.get("batches_written").longValue()).isEqualTo(1);
        assertThat(metrics.get("write_errors").longValue()).isEqualTo(0);
    }

    @Test
    void testWriteRawChunk_MultipleChunks_MetricsAccumulate() throws Exception {
        // Given: Multiple raw chunks
        wrapper.createEnvironmentDataTable(2);

        TickData snap1 = TickData.newBuilder()
            .setTickNumber(0L)
            .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                CellStateTestHelper.createCellStateBuilder(0, 100, 1, 50, 0).build()
            )))
            .build();
        TickData snap2 = TickData.newBuilder()
            .setTickNumber(100L)
            .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                CellStateTestHelper.createCellStateBuilder(1, 101, 1, 60, 0).build()
            )))
            .build();

        byte[] raw1 = TickDataChunk.newBuilder()
            .setFirstTick(0L).setLastTick(0L).setTickCount(1)
            .setSnapshot(snap1).build().toByteArray();
        byte[] raw2 = TickDataChunk.newBuilder()
            .setFirstTick(100L).setLastTick(100L).setTickCount(1)
            .setSnapshot(snap2).build().toByteArray();

        // When: Write 2 raw chunks and commit
        wrapper.writeRawChunk(0L, 0L, 1, raw1);
        wrapper.writeRawChunk(100L, 100L, 1, raw2);
        wrapper.commitRawChunks();

        // Then: 2 chunks written, 1 batch committed
        Map<String, Number> metrics = wrapper.getMetrics();
        assertThat(metrics.get("chunks_written").longValue()).isEqualTo(2);
        assertThat(metrics.get("batches_written").longValue()).isEqualTo(1);
        assertThat(metrics.get("write_errors").longValue()).isEqualTo(0);
        assertThat(metrics.get("write_latency_avg_ms").doubleValue()).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void testWriteRawChunk_LatencyTracked() throws Exception {
        // Given
        wrapper.createEnvironmentDataTable(2);

        TickData snapshot = TickData.newBuilder()
            .setTickNumber(1L)
            .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                CellStateTestHelper.createCellStateBuilder(0, 100, 1, 50, 0).build()
            )))
            .build();
        byte[] rawBytes = TickDataChunk.newBuilder()
            .setFirstTick(1L).setLastTick(1L).setTickCount(1)
            .setSnapshot(snapshot).build().toByteArray();

        // When
        wrapper.writeRawChunk(1L, 1L, 1, rawBytes);

        // Then: Latency should be recorded
        Map<String, Number> metrics = wrapper.getMetrics();
        assertThat(metrics.get("write_latency_p50_ms").doubleValue()).isGreaterThanOrEqualTo(0.0);
    }

    // ========================================================================
    // createEnvironmentDataTable tests
    // ========================================================================

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
