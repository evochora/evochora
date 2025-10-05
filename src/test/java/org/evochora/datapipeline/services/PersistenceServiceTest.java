package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.SystemContracts;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.IIdempotencyTracker;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.queues.IInputQueueResource;
import org.evochora.datapipeline.api.resources.queues.IOutputQueueResource;
import org.evochora.datapipeline.api.resources.storage.IStorageWriteResource;
import org.evochora.datapipeline.api.resources.storage.MessageWriter;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for PersistenceService with mocked dependencies.
 * Tests cover batch processing, deduplication, retry logic, DLQ handling, and configuration validation.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class PersistenceServiceTest {

    @Mock
    private IInputQueueResource<TickData> mockInputQueue;
    
    @Mock
    private IStorageWriteResource mockStorage;
    
    @Mock
    private IOutputQueueResource<SystemContracts.DeadLetterMessage> mockDLQ;
    
    @Mock
    private IIdempotencyTracker<String> mockIdempotencyTracker;
    
    @Mock
    private MessageWriter mockMessageWriter;

    private PersistenceService service;
    private Map<String, List<IResource>> resources;
    private Config config;

    @BeforeEach
    void setUp() {
        resources = new HashMap<>();
        resources.put("input", Collections.singletonList(mockInputQueue));
        resources.put("storage", Collections.singletonList(mockStorage));
        
        config = ConfigFactory.parseMap(Map.of(
            "maxBatchSize", 100,
            "batchTimeoutSeconds", 2,
            "maxRetries", 2,
            "retryBackoffMs", 100
        ));
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*PersistenceService.*")
    void testConstructorWithRequiredResources() {
        service = new PersistenceService("test-persistence", config, resources);
        
        assertNotNull(service);
        assertEquals("test-persistence", service.serviceName);
        assertTrue(service.isHealthy());
        assertEquals(0, service.getErrors().size());
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*PersistenceService.*")
    void testConstructorWithOptionalResources() {
        resources.put("dlq", Collections.singletonList(mockDLQ));
        resources.put("idempotencyTracker", Collections.singletonList(mockIdempotencyTracker));
        
        service = new PersistenceService("test-persistence", config, resources);
        
        assertNotNull(service);
        assertTrue(service.isHealthy());
    }

    @Test
    void testConstructorWithInvalidMaxBatchSize() {
        Config invalidConfig = ConfigFactory.parseMap(Map.of("maxBatchSize", 0));
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            new PersistenceService("test-persistence", invalidConfig, resources);
        });
        
        assertTrue(exception.getMessage().contains("maxBatchSize must be positive"));
    }

    @Test
    void testConstructorWithInvalidBatchTimeout() {
        Config invalidConfig = ConfigFactory.parseMap(Map.of("batchTimeoutSeconds", -1));
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            new PersistenceService("test-persistence", invalidConfig, resources);
        });
        
        assertTrue(exception.getMessage().contains("batchTimeoutSeconds must be positive"));
    }

    @Test
    void testConstructorWithInvalidMaxRetries() {
        Config invalidConfig = ConfigFactory.parseMap(Map.of("maxRetries", -1));
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            new PersistenceService("test-persistence", invalidConfig, resources);
        });
        
        assertTrue(exception.getMessage().contains("maxRetries cannot be negative"));
    }

    @Test
    void testConstructorWithInvalidRetryBackoff() {
        Config invalidConfig = ConfigFactory.parseMap(Map.of("retryBackoffMs", -1));
        
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            new PersistenceService("test-persistence", invalidConfig, resources);
        });
        
        assertTrue(exception.getMessage().contains("retryBackoffMs cannot be negative"));
    }

    @Test
    void testConstructorWithMissingInputResource() {
        resources.remove("input");
        
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            new PersistenceService("test-persistence", config, resources);
        });
        
        assertTrue(exception.getMessage().contains("Resource port 'input' is not configured"));
    }

    @Test
    void testConstructorWithMissingStorageResource() {
        resources.remove("storage");
        
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            new PersistenceService("test-persistence", config, resources);
        });
        
        assertTrue(exception.getMessage().contains("Resource port 'storage' is not configured"));
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*PersistenceService.*")
    void testSuccessfulBatchWrite() throws Exception {
        service = new PersistenceService("test-persistence", config, resources);
        
        // Mock queue behavior - return data on first call, then throw InterruptedException to stop
        when(mockInputQueue.drainTo(any(List.class), anyInt(), anyLong(), any(TimeUnit.class)))
            .thenAnswer(invocation -> {
                List<TickData> batch = invocation.getArgument(0);
                batch.addAll(createTestBatch("sim-123", 100, 102));
                return 3;
            })
            .thenThrow(new InterruptedException("Test shutdown"));
        when(mockStorage.openWriter(anyString())).thenReturn(mockMessageWriter);
        
        // Start service in background
        service.start();
        
        // Wait for batch to be processed
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("batches_written").longValue() > 0);
        
        // Verify storage was called
        verify(mockStorage).openWriter("sim-123/batch_0000000000000000100_0000000000000000102.pb");
        verify(mockMessageWriter, times(3)).writeMessage(any(TickData.class));
        verify(mockMessageWriter).close();
        
        // Verify metrics
        assertEquals(1, service.getMetrics().get("batches_written").longValue());
        assertEquals(3, service.getMetrics().get("ticks_written").longValue());
        
        // Service stops itself due to InterruptedException, no need to call stop()
    }

    @Test
    @AllowLog(level = LogLevel.ERROR, loggerPattern = ".*PersistenceService.*")
    void testBatchConsistencyViolation() throws Exception {
        service = new PersistenceService("test-persistence", config, resources);
        
        // Mock queue behavior - return mixed simulationRunIds, then throw InterruptedException
        when(mockInputQueue.drainTo(any(List.class), anyInt(), anyLong(), any(TimeUnit.class)))
            .thenAnswer(invocation -> {
                List<TickData> batch = invocation.getArgument(0);
                batch.add(createTickData("sim-123", 100));
                batch.add(createTickData("sim-456", 101)); // Different simulationRunId
                return 2;
            })
            .thenThrow(new InterruptedException("Test shutdown"));
        
        service.start();
        
        // Wait for batch to be processed
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("batches_failed").longValue() > 0);
        
        // Verify batch was not written to storage
        verify(mockStorage, never()).openWriter(anyString());
        
        // Verify metrics
        assertEquals(0, service.getMetrics().get("batches_written").longValue());
        assertEquals(1, service.getMetrics().get("batches_failed").longValue());
        
        // Service stops itself due to InterruptedException, no need to call stop()
    }

    @Test
    @AllowLog(level = LogLevel.ERROR, loggerPattern = ".*PersistenceService.*")
    void testDuplicateTickDetection() throws Exception {
        resources.put("idempotencyTracker", Collections.singletonList(mockIdempotencyTracker));
        service = new PersistenceService("test-persistence", config, resources);
        
        // Mock queue behavior - return data, then throw InterruptedException
        when(mockInputQueue.drainTo(any(List.class), anyInt(), anyLong(), any(TimeUnit.class)))
            .thenAnswer(invocation -> {
                List<TickData> batch = invocation.getArgument(0);
                batch.addAll(createTestBatch("sim-123", 100, 102));
                return 3;
            })
            .thenThrow(new InterruptedException("Test shutdown"));
        when(mockStorage.openWriter(anyString())).thenReturn(mockMessageWriter);
        
        // Mock idempotency tracker - first tick is duplicate, others are new
        when(mockIdempotencyTracker.isProcessed("sim-123:100")).thenReturn(true);
        when(mockIdempotencyTracker.isProcessed("sim-123:101")).thenReturn(false);
        when(mockIdempotencyTracker.isProcessed("sim-123:102")).thenReturn(false);
        
        service.start();
        
        // Wait for batch to be processed
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("batches_written").longValue() > 0);
        
        // Verify only 2 ticks were written (duplicate skipped)
        verify(mockMessageWriter, times(2)).writeMessage(any(TickData.class));
        
        // Verify duplicate detection metrics
        assertEquals(1, service.getMetrics().get("duplicate_ticks_detected").longValue());
        
        // Service stops itself due to InterruptedException, no need to call stop()
    }

    @Test
    @AllowLog(level = LogLevel.WARN, loggerPattern = ".*PersistenceService.*")
    void testRetryLogicWithTransientFailure() throws Exception {
        service = new PersistenceService("test-persistence", config, resources);
        
        // Mock queue behavior - return data, then throw InterruptedException
        when(mockInputQueue.drainTo(any(List.class), anyInt(), anyLong(), any(TimeUnit.class)))
            .thenAnswer(invocation -> {
                List<TickData> batch = invocation.getArgument(0);
                batch.addAll(createTestBatch("sim-123", 100, 102));
                return 3;
            })
            .thenThrow(new InterruptedException("Test shutdown"));
        
        // First call fails, second succeeds
        when(mockStorage.openWriter(anyString()))
            .thenThrow(new IOException("Transient failure"))
            .thenReturn(mockMessageWriter);
        
        service.start();
        
        // Wait for batch to be processed
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("batches_written").longValue() > 0);
        
        // Verify storage was called twice (retry)
        verify(mockStorage, times(2)).openWriter(anyString());
        verify(mockMessageWriter).close();
        
        // Verify success metrics
        assertEquals(1, service.getMetrics().get("batches_written").longValue());
        assertEquals(0, service.getMetrics().get("batches_failed").longValue());
        
        // Service stops itself due to InterruptedException, no need to call stop()
    }

    @Test
    @AllowLog(level = LogLevel.ERROR, loggerPattern = ".*PersistenceService.*")
    void testDLQHandlingAfterAllRetriesExhausted() throws Exception {
        resources.put("dlq", Collections.singletonList(mockDLQ));
        service = new PersistenceService("test-persistence", config, resources);
        
        // Mock queue behavior - return data, then throw InterruptedException
        when(mockInputQueue.drainTo(any(List.class), anyInt(), anyLong(), any(TimeUnit.class)))
            .thenAnswer(invocation -> {
                List<TickData> batch = invocation.getArgument(0);
                batch.addAll(createTestBatch("sim-123", 100, 102));
                return 3;
            })
            .thenThrow(new InterruptedException("Test shutdown"));
        when(mockStorage.openWriter(anyString()))
            .thenThrow(new IOException("Persistent failure"));
        when(mockDLQ.offer(any(SystemContracts.DeadLetterMessage.class))).thenReturn(true);
        
        service.start();
        
        // Wait for batch to be processed
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("batches_failed").longValue() > 0);
        
        // Verify DLQ was called
        verify(mockDLQ).offer(any(SystemContracts.DeadLetterMessage.class));
        
        // Verify failure metrics
        assertEquals(0, service.getMetrics().get("batches_written").longValue());
        assertEquals(1, service.getMetrics().get("batches_failed").longValue());
        
        // Service stops itself due to InterruptedException, no need to call stop()
    }

    @Test
    @AllowLog(level = LogLevel.ERROR, loggerPattern = ".*PersistenceService.*")
    void testDLQHandlingWhenNotConfigured() throws Exception {
        service = new PersistenceService("test-persistence", config, resources);
        
        // Mock queue behavior - return data, then throw InterruptedException
        when(mockInputQueue.drainTo(any(List.class), anyInt(), anyLong(), any(TimeUnit.class)))
            .thenAnswer(invocation -> {
                List<TickData> batch = invocation.getArgument(0);
                batch.addAll(createTestBatch("sim-123", 100, 102));
                return 3;
            })
            .thenThrow(new InterruptedException("Test shutdown"));
        when(mockStorage.openWriter(anyString()))
            .thenThrow(new IOException("Persistent failure"));
        
        service.start();
        
        // Wait for batch to be processed
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("batches_failed").longValue() > 0);
        
        // Verify failure metrics
        assertEquals(0, service.getMetrics().get("batches_written").longValue());
        assertEquals(1, service.getMetrics().get("batches_failed").longValue());
        
        // Service stops itself due to InterruptedException, no need to call stop()
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*PersistenceService.*")
    void testGracefulShutdownWithPartialBatch() throws Exception {
        service = new PersistenceService("test-persistence", config, resources);
        
        // Mock queue behavior - return data on first call, then throw InterruptedException
        when(mockInputQueue.drainTo(any(List.class), anyInt(), anyLong(), any(TimeUnit.class)))
            .thenAnswer(invocation -> {
                List<TickData> batch = invocation.getArgument(0);
                batch.addAll(createTestBatch("sim-123", 100, 102));
                return 3;
            })
            .thenThrow(new InterruptedException("Shutdown signal"));
        when(mockStorage.openWriter(anyString())).thenReturn(mockMessageWriter);
        
        service.start();
        
        // Wait a bit for first batch to be processed
        await().atMost(2, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("batches_written").longValue() > 0);
        
        // Stop service (this will trigger InterruptedException)
        service.stop();
        
        // Verify first batch was written
        verify(mockStorage).openWriter(anyString());
        verify(mockMessageWriter).close();
        
        // Verify metrics
        assertEquals(1, service.getMetrics().get("batches_written").longValue());
        
        // Service stops itself due to InterruptedException, no need to call stop()
    }

    @Test
    void testMetricsAccuracy() throws Exception {
        service = new PersistenceService("test-persistence", config, resources);
        
        // Mock queue behavior - return data, then throw InterruptedException
        when(mockInputQueue.drainTo(any(List.class), anyInt(), anyLong(), any(TimeUnit.class)))
            .thenAnswer(invocation -> {
                List<TickData> batch = invocation.getArgument(0);
                batch.addAll(createTestBatch("sim-123", 100, 102));
                return 3;
            })
            .thenThrow(new InterruptedException("Test shutdown"));
        when(mockStorage.openWriter(anyString())).thenReturn(mockMessageWriter);
        
        service.start();
        
        // Wait for batch to be processed
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("batches_written").longValue() > 0);
        
        Map<String, Number> metrics = service.getMetrics();
        
        assertEquals(1, metrics.get("batches_written").longValue());
        assertEquals(3, metrics.get("ticks_written").longValue());
        assertEquals(0, metrics.get("batches_failed").longValue());
        assertEquals(0, metrics.get("duplicate_ticks_detected").longValue());
        assertEquals(3, metrics.get("current_batch_size").intValue());
        
        // Service stops itself due to InterruptedException, no need to call stop()
    }

    @Test
    void testEmptyBatchHandling() throws Exception {
        service = new PersistenceService("test-persistence", config, resources);
        
        // Mock queue behavior - return 0 (no data), then throw InterruptedException
        when(mockInputQueue.drainTo(any(List.class), anyInt(), anyLong(), any(TimeUnit.class)))
            .thenReturn(0) // No data available
            .thenThrow(new InterruptedException("Test shutdown"));
        
        service.start();
        
        // Wait a bit to ensure no processing occurs
        Thread.sleep(100);
        
        // Verify no storage operations
        verify(mockStorage, never()).openWriter(anyString());
        
        // Verify metrics remain zero
        Map<String, Number> metrics = service.getMetrics();
        assertEquals(0, metrics.get("batches_written").longValue());
        assertEquals(0, metrics.get("ticks_written").longValue());
        
        // Service stops itself due to InterruptedException, no need to call stop()
    }

    // Helper methods
    private List<TickData> createTestBatch(String simulationRunId, long startTick, long endTick) {
        List<TickData> batch = new ArrayList<>();
        for (long tick = startTick; tick <= endTick; tick++) {
            batch.add(createTickData(simulationRunId, tick));
        }
        return batch;
    }

    private TickData createTickData(String simulationRunId, long tickNumber) {
        return TickData.newBuilder()
            .setSimulationRunId(simulationRunId)
            .setTickNumber(tickNumber)
            .build();
    }
}