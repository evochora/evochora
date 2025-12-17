package org.evochora.datapipeline.resources.database;

import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.CellStateTestHelper;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.evochora.junit.extensions.logging.LogWatchExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

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
    void testWriteEnvironmentCells_Success() {
        // Given: Environment properties and tick data
        EnvironmentProperties envProps = new EnvironmentProperties(new int[]{10, 10}, false);
        TickData tick = TickData.newBuilder()
            .setTickNumber(1L)
            .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                CellStateTestHelper.createCellStateBuilder(0, 100, 1, 50, 0).build(),
                CellStateTestHelper.createCellStateBuilder(1, 101, 1, 60, 0).build()
            )))
            .build();
        
        // When: Write cells
        wrapper.writeEnvironmentCells(List.of(tick), envProps);
        
        // Then: Should succeed (no exception)
        assertThat(wrapper.isHealthy()).isTrue();
    }
    
    @Test
    void testWriteEnvironmentCells_Metrics() {
        // Given: Environment properties and multiple ticks
        EnvironmentProperties envProps = new EnvironmentProperties(new int[]{10, 10}, false);
        TickData tick1 = TickData.newBuilder()
            .setTickNumber(1L)
            .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                CellStateTestHelper.createCellStateBuilder(0, 100, 1, 50, 0).build(),
                CellStateTestHelper.createCellStateBuilder(1, 101, 1, 60, 0).build()
            )))
            .build();
        TickData tick2 = TickData.newBuilder()
            .setTickNumber(2L)
            .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                CellStateTestHelper.createCellStateBuilder(2, 102, 1, 70, 0).build()
            )))
            .build();
        
        // When: Write cells
        wrapper.writeEnvironmentCells(List.of(tick1, tick2), envProps);
        
        // Then: Metrics should reflect 3 cells and 1 batch
        Map<String, Number> metrics = wrapper.getMetrics();
        assertThat(metrics).containsKeys(
            "cells_written", "batches_written", "write_errors",
            "cells_per_second", "batches_per_second",
            "write_latency_p50_ms", "write_latency_p95_ms", "write_latency_p99_ms", "write_latency_avg_ms"
        );
        
        assertThat(metrics.get("cells_written").longValue()).isEqualTo(3);
        assertThat(metrics.get("batches_written").longValue()).isEqualTo(1);
        assertThat(metrics.get("write_errors").longValue()).isEqualTo(0);
        
        // Latency metrics should be non-negative
        assertThat(metrics.get("write_latency_p50_ms").doubleValue()).isGreaterThanOrEqualTo(0.0);
        assertThat(metrics.get("write_latency_avg_ms").doubleValue()).isGreaterThanOrEqualTo(0.0);
    }
    
    @Test
    void testWriteEnvironmentCells_EmptyList() {
        // Given: Empty tick list
        EnvironmentProperties envProps = new EnvironmentProperties(new int[]{10, 10}, false);
        
        // When: Write empty list
        wrapper.writeEnvironmentCells(List.of(), envProps);
        
        // Then: Should succeed without errors
        assertThat(wrapper.isHealthy()).isTrue();
        
        Map<String, Number> metrics = wrapper.getMetrics();
        assertThat(metrics.get("cells_written").longValue()).isEqualTo(0);
        assertThat(metrics.get("batches_written").longValue()).isEqualTo(0);
    }
    
    @Test
    void testCollectMetrics_AllFields() {
        // Given: Wrapper with some writes
        EnvironmentProperties envProps = new EnvironmentProperties(new int[]{10, 10}, false);
        TickData tick = TickData.newBuilder()
            .setTickNumber(1L)
            .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                CellStateTestHelper.createCellStateBuilder(0, 100, 1, 50, 0).build()
            )))
            .build();
        wrapper.writeEnvironmentCells(List.of(tick), envProps);
        
        // When: Get metrics
        Map<String, Number> metrics = wrapper.getMetrics();
        
        // Then: All expected fields should be present
        assertThat(metrics).containsKeys(
            // From parent (AbstractDatabaseWrapper)
            "connection_cached",
            // From EnvironmentDataWriterWrapper
            "cells_written",
            "batches_written",
            "write_errors",
            "cells_per_second",
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
        EnvironmentProperties envProps = new EnvironmentProperties(new int[]{10, 10}, false);
        TickData tick = TickData.newBuilder()
            .setTickNumber(1L)
            .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                CellStateTestHelper.createCellStateBuilder(0, 100, 1, 50, 0).build()
            )))
            .build();
        wrapper.writeEnvironmentCells(List.of(tick), envProps);
    }
}

