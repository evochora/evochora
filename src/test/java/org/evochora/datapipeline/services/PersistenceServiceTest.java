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
import org.evochora.datapipeline.api.services.IService.State;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.ExpectLog;
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
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*PersistenceService.*")
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
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*PersistenceService.*")
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
    @ExpectLog(level = LogLevel.ERROR, loggerPattern = ".*PersistenceService.*",
               messagePattern = "Batch consistency violation: first tick simulationRunId=.*")
    @ExpectLog(level = LogLevel.ERROR, loggerPattern = ".*PersistenceService.*",
               messagePattern = "Failed batch has no DLQ configured, data will be lost: .*")
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
    @ExpectLog(level = LogLevel.ERROR, loggerPattern = ".*PersistenceService.*",
               messagePattern = "Batch contains tick with empty or null simulationRunId")
    void testEmptySimulationRunIdValidation() throws Exception {
        resources.put("dlq", Collections.singletonList(mockDLQ));
        service = new PersistenceService("test-persistence", config, resources);

        // Mock queue behavior - return batch with empty simulationRunId, then throw InterruptedException
        when(mockInputQueue.drainTo(any(List.class), anyInt(), anyLong(), any(TimeUnit.class)))
            .thenAnswer(invocation -> {
                List<TickData> batch = invocation.getArgument(0);
                batch.add(TickData.newBuilder().setSimulationRunId("").setTickNumber(100).build());
                return 1;
            })
            .thenThrow(new InterruptedException("Test shutdown"));
        when(mockDLQ.offer(any(SystemContracts.DeadLetterMessage.class))).thenReturn(true);
        when(mockInputQueue.getResourceName()).thenReturn("test-input-queue");

        service.start();

        // Wait for batch to be rejected
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("batches_failed").longValue() > 0);

        // Verify batch was not written to storage
        verify(mockStorage, never()).openWriter(anyString());

        // Verify DLQ was called
        verify(mockDLQ).offer(any(SystemContracts.DeadLetterMessage.class));

        // Verify metrics
        assertEquals(0, service.getMetrics().get("batches_written").longValue());
        assertEquals(1, service.getMetrics().get("batches_failed").longValue());

        // Verify error was recorded
        assertEquals(1, service.getErrors().size());
        assertEquals("INVALID_SIMULATION_RUN_ID", service.getErrors().get(0).errorType());

        // Service stops itself due to InterruptedException, no need to call stop()
    }

    @Test
    @ExpectLog(level = LogLevel.ERROR, loggerPattern = ".*PersistenceService.*",
               messagePattern = "Batch contains tick with empty or null simulationRunId")
    void testNullSimulationRunIdValidation() throws Exception {
        resources.put("dlq", Collections.singletonList(mockDLQ));
        service = new PersistenceService("test-persistence", config, resources);

        // Mock queue behavior - return batch with null simulationRunId, then throw InterruptedException
        when(mockInputQueue.drainTo(any(List.class), anyInt(), anyLong(), any(TimeUnit.class)))
            .thenAnswer(invocation -> {
                List<TickData> batch = invocation.getArgument(0);
                // Create a TickData without setting simulationRunId (will be empty string in protobuf)
                batch.add(TickData.newBuilder().setTickNumber(100).build());
                return 1;
            })
            .thenThrow(new InterruptedException("Test shutdown"));
        when(mockDLQ.offer(any(SystemContracts.DeadLetterMessage.class))).thenReturn(true);
        when(mockInputQueue.getResourceName()).thenReturn("test-input-queue");

        service.start();

        // Wait for batch to be rejected
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("batches_failed").longValue() > 0);

        // Verify batch was not written to storage
        verify(mockStorage, never()).openWriter(anyString());

        // Verify DLQ was called
        verify(mockDLQ).offer(any(SystemContracts.DeadLetterMessage.class));

        // Verify metrics
        assertEquals(0, service.getMetrics().get("batches_written").longValue());
        assertEquals(1, service.getMetrics().get("batches_failed").longValue());

        // Verify error was recorded
        assertEquals(1, service.getErrors().size());
        assertEquals("INVALID_SIMULATION_RUN_ID", service.getErrors().get(0).errorType());

        // Service stops itself due to InterruptedException, no need to call stop()
    }

    @Test
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*PersistenceService.*",
               messagePattern = "Duplicate tick detected: .*", occurrences = -1)
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

        // Mock idempotency tracker - uses atomic checkAndMarkProcessed()
        // First tick is duplicate (returns false), others are new (returns true)
        when(mockIdempotencyTracker.checkAndMarkProcessed("sim-123:100")).thenReturn(false); // Already processed
        when(mockIdempotencyTracker.checkAndMarkProcessed("sim-123:101")).thenReturn(true);  // New
        when(mockIdempotencyTracker.checkAndMarkProcessed("sim-123:102")).thenReturn(true);  // New

        service.start();

        // Wait for batch to be processed
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("batches_written").longValue() > 0);

        // Verify only 2 ticks were written (duplicate skipped)
        verify(mockMessageWriter, times(2)).writeMessage(any(TickData.class));

        // Verify the atomic method was actually called for all ticks
        verify(mockIdempotencyTracker).checkAndMarkProcessed("sim-123:100");
        verify(mockIdempotencyTracker).checkAndMarkProcessed("sim-123:101");
        verify(mockIdempotencyTracker).checkAndMarkProcessed("sim-123:102");

        // Verify duplicate detection metrics
        assertEquals(1, service.getMetrics().get("duplicate_ticks_detected").longValue());

        // Service stops itself due to InterruptedException, no need to call stop()
    }

    @Test
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*PersistenceService.*",
               messagePattern = "Failed to write batch .* \\(attempt .*\\): .*, retrying in .*ms", occurrences = -1)
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
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*PersistenceService.*",
               messagePattern = "Failed to write batch .* \\(attempt .*\\): .*, retrying in .*ms", occurrences = -1)
    @ExpectLog(level = LogLevel.ERROR, loggerPattern = ".*PersistenceService.*",
               messagePattern = "Failed to write batch .* after .* retries: .*")
    void testDLQHandlingAfterAllRetriesExhausted() throws Exception {
        // Use faster retry backoff to speed up test (10ms vs 100ms base)
        Config fastRetryConfig = ConfigFactory.parseMap(Map.of(
            "maxBatchSize", 100,
            "batchTimeoutSeconds", 2,
            "maxRetries", 2,
            "retryBackoffMs", 10  // 10 + 20 + 40 = 70ms total vs 700ms with 100ms base
        ));
        resources.put("dlq", Collections.singletonList(mockDLQ));
        service = new PersistenceService("test-persistence", fastRetryConfig, resources);

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
        when(mockInputQueue.getResourceName()).thenReturn("test-input-queue");

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
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*PersistenceService.*",
               messagePattern = "Failed to write batch .* \\(attempt .*\\): .*, retrying in .*ms", occurrences = -1)
    @ExpectLog(level = LogLevel.ERROR, loggerPattern = ".*PersistenceService.*",
               messagePattern = "Failed to write batch .* after .* retries: .*")
    @ExpectLog(level = LogLevel.ERROR, loggerPattern = ".*PersistenceService.*",
               messagePattern = "Failed batch has no DLQ configured, data will be lost: .*")
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

        // Wait for first batch to be processed and service to stop
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getCurrentState() == State.STOPPED);

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

        // Wait until service processes the empty batch (drainTo called at least once)
        await().atMost(2, java.util.concurrent.TimeUnit.SECONDS)
            .untilAsserted(() -> verify(mockInputQueue, atLeastOnce())
                .drainTo(any(List.class), anyInt(), anyLong(), any(TimeUnit.class)));

        // Verify no storage operations occurred for empty batch
        verify(mockStorage, never()).openWriter(anyString());
        
        // Verify metrics remain zero
        Map<String, Number> metrics = service.getMetrics();
        assertEquals(0, metrics.get("batches_written").longValue());
        assertEquals(0, metrics.get("ticks_written").longValue());
        
        // Service stops itself due to InterruptedException, no need to call stop()
    }

    @Test
    @ExpectLog(level = LogLevel.ERROR, loggerPattern = ".*PersistenceService.*",
               messagePattern = "Batch consistency violation: first tick simulationRunId=.*")
    @ExpectLog(level = LogLevel.ERROR, loggerPattern = ".*PersistenceService.*",
               messagePattern = "Failed batch has no DLQ configured, data will be lost: .*")
    void testErrorTrackingAndClearing() throws Exception {
        service = new PersistenceService("test-persistence", config, resources);

        // Mock queue behavior - return mixed simulationRunIds to trigger error, then throw InterruptedException
        when(mockInputQueue.drainTo(any(List.class), anyInt(), anyLong(), any(TimeUnit.class)))
            .thenAnswer(invocation -> {
                List<TickData> batch = invocation.getArgument(0);
                batch.add(createTickData("sim-123", 100));
                batch.add(createTickData("sim-456", 101)); // Different simulationRunId causes error
                return 2;
            })
            .thenThrow(new InterruptedException("Test shutdown"));

        service.start();

        // Wait for error to be recorded
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> !service.getErrors().isEmpty());

        // Verify error was recorded
        assertEquals(1, service.getErrors().size());
        assertEquals("BATCH_CONSISTENCY_VIOLATION", service.getErrors().get(0).errorType());
        // Details should contain "First: sim-123, Last: sim-456, Batch size: 2"
        assertTrue(service.getErrors().get(0).details().contains("First:"));
        assertTrue(service.getErrors().get(0).details().contains("Last:"));

        // Clear errors
        service.clearErrors();

        // Verify errors list is now empty
        assertEquals(0, service.getErrors().size());

        // Service stops itself due to InterruptedException, no need to call stop()
    }

    @Test
    @ExpectLog(level = LogLevel.ERROR, loggerPattern = ".*PersistenceService.*",
               messagePattern = "Batch contains tick with empty or null simulationRunId")
    @ExpectLog(level = LogLevel.ERROR, loggerPattern = ".*PersistenceService.*",
               messagePattern = "Batch consistency violation: first tick simulationRunId=.*")
    void testMultipleErrorsAccumulate() throws Exception {
        resources.put("dlq", Collections.singletonList(mockDLQ));
        service = new PersistenceService("test-persistence", config, resources);

        // Mock queue behavior - return multiple batches with errors
        when(mockInputQueue.drainTo(any(List.class), anyInt(), anyLong(), any(TimeUnit.class)))
            .thenAnswer(invocation -> {
                List<TickData> batch = invocation.getArgument(0);
                batch.add(TickData.newBuilder().setSimulationRunId("").setTickNumber(100).build()); // Empty ID
                return 1;
            })
            .thenAnswer(invocation -> {
                List<TickData> batch = invocation.getArgument(0);
                batch.add(createTickData("sim-123", 100));
                batch.add(createTickData("sim-456", 101)); // Mixed IDs
                return 2;
            })
            .thenThrow(new InterruptedException("Test shutdown"));
        when(mockDLQ.offer(any(SystemContracts.DeadLetterMessage.class))).thenReturn(true);
        when(mockInputQueue.getResourceName()).thenReturn("test-input-queue");

        service.start();

        // Wait for errors to accumulate
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getErrors().size() >= 2);

        // Verify multiple errors were recorded
        assertTrue(service.getErrors().size() >= 2);
        // First error should be INVALID_SIMULATION_RUN_ID
        assertEquals("INVALID_SIMULATION_RUN_ID", service.getErrors().get(0).errorType());
        // Second error should be BATCH_CONSISTENCY_VIOLATION
        assertEquals("BATCH_CONSISTENCY_VIOLATION", service.getErrors().get(1).errorType());

        // Service stops itself due to InterruptedException, no need to call stop()
    }

    @Test
    @ExpectLog(level = LogLevel.ERROR, loggerPattern = ".*PersistenceService.*",
               messagePattern = "Batch contains tick with empty or null simulationRunId")
    void testErrorDetailsContainUsefulInformation() throws Exception {
        resources.put("dlq", Collections.singletonList(mockDLQ));
        service = new PersistenceService("test-persistence", config, resources);

        // Mock queue behavior - return batch with empty simulationRunId
        when(mockInputQueue.drainTo(any(List.class), anyInt(), anyLong(), any(TimeUnit.class)))
            .thenAnswer(invocation -> {
                List<TickData> batch = invocation.getArgument(0);
                batch.add(TickData.newBuilder().setSimulationRunId("").setTickNumber(100).build());
                return 1;
            })
            .thenThrow(new InterruptedException("Test shutdown"));
        when(mockDLQ.offer(any(SystemContracts.DeadLetterMessage.class))).thenReturn(true);
        when(mockInputQueue.getResourceName()).thenReturn("test-input-queue");

        service.start();

        // Wait for error to be recorded
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> !service.getErrors().isEmpty());

        // Verify error details contain batch size
        var error = service.getErrors().get(0);
        assertEquals("INVALID_SIMULATION_RUN_ID", error.errorType());
        assertEquals("Batch contains tick with empty or null simulationRunId", error.message());
        assertTrue(error.details().contains("Batch size: 1"));

        // Service stops itself due to InterruptedException, no need to call stop()
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*PersistenceService.*")
    void testHealthCheckReflectsServiceState() throws Exception {
        service = new PersistenceService("test-persistence", config, resources);

        // Service should be healthy after construction (READY state)
        assertTrue(service.isHealthy());

        // Start service - should still be healthy (RUNNING state)
        when(mockInputQueue.drainTo(any(List.class), anyInt(), anyLong(), any(TimeUnit.class)))
            .thenReturn(0)
            .thenThrow(new InterruptedException("Test shutdown"));

        service.start();

        // Wait until service is actually running (has called drainTo at least once)
        await().atMost(2, java.util.concurrent.TimeUnit.SECONDS)
            .untilAsserted(() -> verify(mockInputQueue, atLeastOnce())
                .drainTo(any(List.class), anyInt(), anyLong(), any(TimeUnit.class)));

        // Should still be healthy during normal operation
        assertTrue(service.isHealthy());

        // Service stops itself due to InterruptedException
        await().atMost(2, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getCurrentState() == State.STOPPED);

        // After stopping, should still be healthy (STOPPED != ERROR)
        assertTrue(service.isHealthy());
    }

    @Test
    void testCurrentBatchSizeResetToZero() throws Exception {
        service = new PersistenceService("test-persistence", config, resources);

        // Mock queue behavior - first return data, then return 0 (empty drain), then throw InterruptedException
        when(mockInputQueue.drainTo(any(List.class), anyInt(), anyLong(), any(TimeUnit.class)))
            .thenAnswer(invocation -> {
                List<TickData> batch = invocation.getArgument(0);
                batch.addAll(createTestBatch("sim-123", 100, 102));
                return 3;
            })
            .thenReturn(0) // Empty drain
            .thenThrow(new InterruptedException("Test shutdown"));
        when(mockStorage.openWriter(anyString())).thenReturn(mockMessageWriter);

        service.start();

        // Wait for first batch to be processed
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("batches_written").longValue() > 0);

        // Note: We don't check currentBatchSize == 3 here because it's a race condition:
        // the service may have already processed the empty drain and reset it to 0.

        // Wait for empty drain to occur (which resets currentBatchSize to 0)
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("current_batch_size").intValue() == 0);

        // Verify currentBatchSize was reset to 0
        assertEquals(0, service.getMetrics().get("current_batch_size").intValue());

        // Service stops itself due to InterruptedException, no need to call stop()
    }

    @Test
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*PersistenceService.*",
               messagePattern = "Failed to write batch .* \\(attempt .*\\): .*, retrying in .*ms", occurrences = -1)
    @ExpectLog(level = LogLevel.ERROR, loggerPattern = ".*PersistenceService.*",
               messagePattern = "Failed to write batch .* after .* retries: .*")
    void testRetryBackoffCap() throws Exception {
        // Configure with high maxRetries to test exponential backoff
        Config highRetryConfig = ConfigFactory.parseMap(Map.of(
            "maxBatchSize", 100,
            "batchTimeoutSeconds", 1,
            "maxRetries", 8,  // 8 retries: backoff will be 10, 20, 40, 80, 160, 320, 640, 1280ms (total ~2550ms)
            "retryBackoffMs", 10  // Small base to make test fast while still testing exponential behavior
        ));
        resources.put("dlq", Collections.singletonList(mockDLQ));
        service = new PersistenceService("test-persistence", highRetryConfig, resources);

        // Mock queue behavior - return data, then throw InterruptedException
        when(mockInputQueue.drainTo(any(List.class), anyInt(), anyLong(), any(TimeUnit.class)))
            .thenAnswer(invocation -> {
                List<TickData> batch = invocation.getArgument(0);
                batch.addAll(createTestBatch("sim-123", 100, 102));
                return 3;
            })
            .thenThrow(new InterruptedException("Test shutdown"));

        // Storage always fails to trigger retries
        when(mockStorage.openWriter(anyString())).thenThrow(new IOException("Persistent failure"));
        when(mockDLQ.offer(any(SystemContracts.DeadLetterMessage.class))).thenReturn(true);
        when(mockInputQueue.getResourceName()).thenReturn("test-input-queue");

        long startTime = System.currentTimeMillis();
        service.start();

        // Wait for batch to fail after all retries
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("batches_failed").longValue() > 0);

        long elapsedTime = System.currentTimeMillis() - startTime;

        // With 8 retries and 10ms base backoff:
        // Backoff times: 10 + 20 + 40 + 80 + 160 + 320 + 640 + 1280 = 2550ms
        // The test verifies exponential backoff works correctly

        // Expected total backoff time: ~2550ms, plus processing overhead
        // We verify it's under 3500ms (giving 950ms buffer for processing)
        assertTrue(elapsedTime < 3500,
            String.format("Retry with exponential backoff took %dms, expected < 3500ms", elapsedTime));

        // Verify all retries were attempted (8 retries + 1 initial = 9 total attempts)
        verify(mockStorage, times(9)).openWriter(anyString());

        // Verify DLQ was called after exhausting retries
        verify(mockDLQ).offer(any(SystemContracts.DeadLetterMessage.class));

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