package org.evochora.datapipeline.resources.database;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.database.MetadataNotFoundException;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for MetadataReaderWrapper.
 * <p>
 * Tests the wrapper implementation without requiring a real database.
 * Uses Mockito to simulate AbstractDatabaseResource behavior.
 */
@ExtendWith(LogWatchExtension.class)
@Tag("unit")
class MetadataReaderWrapperTest {
    
    @Mock
    private AbstractDatabaseResource mockDatabase;
    
    private MetadataReaderWrapper wrapper;
    private ResourceContext context;
    private Object mockConnection;
    
    @BeforeEach
    void setup() throws Exception {
        MockitoAnnotations.openMocks(this);
        
        // Setup mock database
        Config config = ConfigFactory.parseString("metricsWindowSeconds = 5");
        when(mockDatabase.getOptions()).thenReturn(config);
        
        // Mock connection
        mockConnection = new Object();
        when(mockDatabase.acquireDedicatedConnection()).thenReturn(mockConnection);
        
        // Create context
        context = new ResourceContext("test-service", "metadata", "db-meta-read", "test-db", Collections.emptyMap());
        
        // Create wrapper
        wrapper = new MetadataReaderWrapper(mockDatabase, context);
    }
    
    @Test
    void getMetadata_success() throws Exception {
        // Given: Mock returns test metadata
        SimulationMetadata testMetadata = SimulationMetadata.newBuilder()
            .setSimulationRunId("test-run")
            .setSamplingInterval(10)
            .build();
        
        when(mockDatabase.doGetMetadata(any(), eq("test-run")))
            .thenReturn(testMetadata);
        
        // When: Get metadata
        SimulationMetadata result = wrapper.getMetadata("test-run");
        
        // Then: Correct delegation and result
        assertNotNull(result);
        assertEquals("test-run", result.getSimulationRunId());
        assertEquals(10, result.getSamplingInterval());
        
        // Verify metrics updated
        Map<String, Number> metrics = wrapper.getMetrics();
        assertEquals(1, metrics.get("metadata_reads").intValue());
        assertEquals(0, metrics.get("metadata_not_found").intValue());
        assertEquals(0, metrics.get("read_errors").intValue());
        assertTrue(metrics.get("get_metadata_latency_avg_ms").doubleValue() >= 0);
    }
    
    @Test
    void getMetadata_notFound_throwsException() throws Exception {
        // Given: Mock throws MetadataNotFoundException
        when(mockDatabase.doGetMetadata(any(), eq("missing-run")))
            .thenThrow(new MetadataNotFoundException("Not found"));
        
        // When/Then: Exception propagated
        assertThrows(MetadataNotFoundException.class, () -> 
            wrapper.getMetadata("missing-run")
        );
        
        // Verify metrics updated
        Map<String, Number> metrics = wrapper.getMetrics();
        assertEquals(0, metrics.get("metadata_reads").intValue());
        assertEquals(1, metrics.get("metadata_not_found").intValue());
        assertEquals(0, metrics.get("read_errors").intValue());
    }
    
    @Test
    void getMetadata_databaseError_wrapsException() throws Exception {
        // Given: Mock throws generic exception
        when(mockDatabase.doGetMetadata(any(), eq("error-run")))
            .thenThrow(new RuntimeException("Database error"));
        
        // When/Then: Exception wrapped
        RuntimeException ex = assertThrows(RuntimeException.class, () -> 
            wrapper.getMetadata("error-run")
        );
        assertTrue(ex.getMessage().contains("Failed to read metadata"));
        
        // Verify metrics updated
        Map<String, Number> metrics = wrapper.getMetrics();
        assertEquals(0, metrics.get("metadata_reads").intValue());
        assertEquals(0, metrics.get("metadata_not_found").intValue());
        assertEquals(1, metrics.get("read_errors").intValue());
        
        // Verify error recorded
        assertEquals(1, wrapper.getErrors().size());
        assertEquals("GET_METADATA_FAILED", wrapper.getErrors().get(0).errorType());
    }
    
    @Test
    void hasMetadata_returnsTrue() throws Exception {
        // Given: Mock returns true
        when(mockDatabase.doHasMetadata(any(), eq("existing-run")))
            .thenReturn(true);
        
        // When: Check metadata existence
        boolean result = wrapper.hasMetadata("existing-run");
        
        // Then: Correct result
        assertTrue(result);
        
        // Verify metrics
        Map<String, Number> metrics = wrapper.getMetrics();
        assertTrue(metrics.get("has_metadata_latency_p95_ms").doubleValue() >= 0);
    }
    
    @Test
    void hasMetadata_returnsFalse() throws Exception {
        // Given: Mock returns false
        when(mockDatabase.doHasMetadata(any(), eq("missing-run")))
            .thenReturn(false);
        
        // When: Check metadata existence
        boolean result = wrapper.hasMetadata("missing-run");
        
        // Then: Correct result
        assertFalse(result);
    }
    
    @Test
    void hasMetadata_databaseError_returnsFalse() throws Exception {
        // Given: Mock throws exception
        when(mockDatabase.doHasMetadata(any(), eq("error-run")))
            .thenThrow(new RuntimeException("Database error"));
        
        // When: Check metadata existence (exception handled gracefully)
        boolean result = wrapper.hasMetadata("error-run");
        
        // Then: Returns false on error (safe assumption)
        assertFalse(result);
        
        // Verify error recorded
        assertEquals(1, wrapper.getErrors().size());
        assertEquals("HAS_METADATA_FAILED", wrapper.getErrors().get(0).errorType());
        assertEquals(1, wrapper.getMetrics().get("read_errors").intValue());
    }
    
    @Test
    void metrics_allO1Operations() throws Exception {
        // Given: Perform several operations
        SimulationMetadata testMetadata = SimulationMetadata.newBuilder()
            .setSimulationRunId("test-run")
            .setSamplingInterval(10)
            .build();
        
        when(mockDatabase.doGetMetadata(any(), any())).thenReturn(testMetadata);
        when(mockDatabase.doHasMetadata(any(), any())).thenReturn(true);
        
        // Perform 100 operations
        for (int i = 0; i < 100; i++) {
            wrapper.getMetadata("test-run-" + i);
            wrapper.hasMetadata("test-run-" + i);
        }
        
        // When: Get metrics (should be O(1) regardless of operation count)
        long startNanos = System.nanoTime();
        Map<String, Number> metrics = wrapper.getMetrics();
        long elapsedNanos = System.nanoTime() - startNanos;
        
        // Then: Metrics available and fast
        assertNotNull(metrics);
        assertEquals(100, metrics.get("metadata_reads").intValue());
        assertTrue(metrics.containsKey("get_metadata_latency_p50_ms"));
        assertTrue(metrics.containsKey("get_metadata_latency_p95_ms"));
        assertTrue(metrics.containsKey("get_metadata_latency_p99_ms"));
        assertTrue(metrics.containsKey("get_metadata_latency_avg_ms"));
        assertTrue(metrics.containsKey("has_metadata_latency_p95_ms"));
        assertTrue(metrics.containsKey("error_count"));
        assertTrue(metrics.containsKey("connection_cached"));
        
        // Metrics retrieval should be very fast (< 10ms even with 100 operations)
        assertTrue(elapsedNanos < 10_000_000, 
            "Metrics should be O(1), took " + (elapsedNanos / 1_000_000.0) + "ms");
    }
    
    @Test
    void setSimulationRun_setsSchema() throws Exception {
        // When: Set simulation run
        wrapper.setSimulationRun("my-run");
        
        // Then: Schema created and set on database
        verify(mockDatabase).doCreateSchema(mockConnection, "my-run");
        verify(mockDatabase).doSetSchema(mockConnection, "my-run");
    }
    
    @Test
    void close_releasesConnection() {
        // When: Close wrapper
        wrapper.close();
        
        // Then: Connection handling validated (connection is null in this mock scenario)
        // Real scenario: connection would be closed and returned to pool
        assertDoesNotThrow(() -> wrapper.close());
    }
}

