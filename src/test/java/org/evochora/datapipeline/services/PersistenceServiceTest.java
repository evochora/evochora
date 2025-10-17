package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.BatchInfo;
import org.evochora.datapipeline.api.contracts.SystemContracts;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.IIdempotencyTracker;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.queues.IInputQueueResource;
import org.evochora.datapipeline.api.resources.queues.IOutputQueueResource;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageWrite;
import org.evochora.datapipeline.api.resources.topics.ITopicWriter;
import org.evochora.datapipeline.api.services.IService.State;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.ExpectLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
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
@ExtendWith({MockitoExtension.class, LogWatchExtension.class})
@AllowLog(level = LogLevel.WARN, messagePattern = "PersistenceService initialized WITHOUT batch-topic - event-driven indexing disabled!")
class PersistenceServiceTest {

    @Mock
    private IInputQueueResource<TickData> mockInputQueue;
    
    @Mock
    private IBatchStorageWrite mockStorage;

    @Mock
    private ITopicWriter<BatchInfo> mockBatchTopic;

    @Mock
    private IOutputQueueResource<SystemContracts.DeadLetterMessage> mockDLQ;

    @Mock
    private IIdempotencyTracker<Long> mockIdempotencyTracker;

    private PersistenceService service;
    private Map<String, List<IResource>> resources;
    private Config config;

    @BeforeEach
    void setUp() {
        resources = new HashMap<>();
        resources.put("input", Collections.singletonList(mockInputQueue));
        resources.put("storage", Collections.singletonList(mockStorage));
        // topic is optional - add it per test as needed
        
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
        when(mockStorage.writeBatch(anyList(), anyLong(), anyLong()))
            .thenReturn("001/batch_0000000000000000100_0000000000000000102.pb.zst");

        // Start service in background
        service.start();

        // Wait for batch to be processed
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("batches_written").longValue() > 0);

        // Verify storage was called with batch API
        verify(mockStorage).writeBatch(argThat(list -> list.size() == 3), eq(100L), eq(102L));
        
        // Verify metrics
        assertEquals(1, service.getMetrics().get("batches_written").longValue());
        assertEquals(3, service.getMetrics().get("ticks_written").longValue());
        
        // Service stops itself due to InterruptedException, no need to call stop()
    }

    @Test
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*PersistenceService.*",
               messagePattern = "Batch consistency violation: first=.*, last=.*, sending to DLQ")
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*PersistenceService.*",
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
        verify(mockStorage, never()).writeBatch(anyList(), anyLong(), anyLong());
        
        // Verify metrics
        assertEquals(0, service.getMetrics().get("batches_written").longValue());
        assertEquals(1, service.getMetrics().get("batches_failed").longValue());
        
        // Service stops itself due to InterruptedException, no need to call stop()
    }

    @Test
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*PersistenceService.*",
               messagePattern = "Batch contains tick with empty or null simulationRunId, sending to DLQ")
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
        verify(mockStorage, never()).writeBatch(anyList(), anyLong(), anyLong());

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
               messagePattern = "Batch contains tick with empty or null simulationRunId, sending to DLQ")
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
        verify(mockStorage, never()).writeBatch(anyList(), anyLong(), anyLong());

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
    @AllowLog(level = LogLevel.WARN, loggerPattern = ".*PersistenceService.*",
              messagePattern = "\\[.*\\] Removed .* duplicate ticks, .* unique ticks remain: range \\[.*\\]")
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
        when(mockStorage.writeBatch(anyList(), anyLong(), anyLong()))
            .thenReturn("batch_file.pb.zst");

        // Mock idempotency tracker - uses atomic checkAndMarkProcessed()
        // First tick is duplicate (returns false), others are new (returns true)
        // Changed from String to Long keys (tick number only)
        when(mockIdempotencyTracker.checkAndMarkProcessed(100L)).thenReturn(false); // Already processed
        when(mockIdempotencyTracker.checkAndMarkProcessed(101L)).thenReturn(true);  // New
        when(mockIdempotencyTracker.checkAndMarkProcessed(102L)).thenReturn(true);  // New

        service.start();

        // Wait for batch to be processed
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("batches_written").longValue() > 0);

        // Verify batch write was called
        verify(mockStorage).writeBatch(argThat(list -> list.size() == 2), eq(100L), eq(102L));

        // Verify the atomic method was actually called for all ticks
        verify(mockIdempotencyTracker).checkAndMarkProcessed(100L);
        verify(mockIdempotencyTracker).checkAndMarkProcessed(101L);
        verify(mockIdempotencyTracker).checkAndMarkProcessed(102L);

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
        when(mockStorage.writeBatch(anyList(), anyLong(), anyLong()))
            .thenThrow(new IOException("Transient failure"))
            .thenReturn("batch_file.pb.zst");
        
        service.start();
        
        // Wait for batch to be processed
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("batches_written").longValue() > 0);
        
        // Verify storage was called twice (retry)
        verify(mockStorage, times(2)).writeBatch(anyList(), anyLong(), anyLong());
        
        // Verify success metrics
        assertEquals(1, service.getMetrics().get("batches_written").longValue());
        assertEquals(0, service.getMetrics().get("batches_failed").longValue());
        
        // Service stops itself due to InterruptedException, no need to call stop()
    }

    @Test
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*PersistenceService.*",
               messagePattern = "Failed to write batch .* after .* retries, sending to DLQ")
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
        when(mockStorage.writeBatch(anyList(), anyLong(), anyLong()))
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
               messagePattern = "Failed to write batch .* after .* retries, sending to DLQ")
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*PersistenceService.*",
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
        when(mockStorage.writeBatch(anyList(), anyLong(), anyLong()))
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
        when(mockStorage.writeBatch(anyList(), anyLong(), anyLong()))
            .thenReturn("batch_file.pb.zst");
        
        service.start();

        // Wait for first batch to be processed and service to stop
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getCurrentState() == State.STOPPED);

        // Verify first batch was written
        verify(mockStorage).writeBatch(anyList(), anyLong(), anyLong());

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
        when(mockStorage.writeBatch(anyList(), anyLong(), anyLong()))
            .thenReturn("batch_file.pb.zst");
        
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
        verify(mockStorage, never()).writeBatch(anyList(), anyLong(), anyLong());
        
        // Verify metrics remain zero
        Map<String, Number> metrics = service.getMetrics();
        assertEquals(0, metrics.get("batches_written").longValue());
        assertEquals(0, metrics.get("ticks_written").longValue());
        
        // Service stops itself due to InterruptedException, no need to call stop()
    }

    @Test
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*PersistenceService.*",
               messagePattern = "Batch consistency violation: first=.*, last=.*, sending to DLQ")
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*PersistenceService.*",
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

        // Wait for errors to be recorded
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getErrors().size() >= 2);

        // Verify errors were recorded (2 errors: batch violation + DLQ not configured)
        assertEquals(2, service.getErrors().size());
        assertEquals("BATCH_CONSISTENCY_VIOLATION", service.getErrors().get(0).errorType());
        assertEquals("DLQ_NOT_CONFIGURED", service.getErrors().get(1).errorType());
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
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*PersistenceService.*",
               messagePattern = "Batch contains tick with empty or null simulationRunId, sending to DLQ")
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*PersistenceService.*",
               messagePattern = "Batch consistency violation: first=.*, last=.*, sending to DLQ")
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
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*PersistenceService.*",
               messagePattern = "Batch contains tick with empty or null simulationRunId, sending to DLQ")
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
        when(mockStorage.writeBatch(anyList(), anyLong(), anyLong()))
            .thenReturn("batch_file.pb.zst");

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
               messagePattern = "Failed to write batch .* after .* retries, sending to DLQ")
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
        when(mockStorage.writeBatch(anyList(), anyLong(), anyLong()))
            .thenThrow(new IOException("Persistent failure"));
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
        verify(mockStorage, times(9)).writeBatch(anyList(), anyLong(), anyLong());

        // Verify DLQ was called after exhausting retries
        verify(mockDLQ).offer(any(SystemContracts.DeadLetterMessage.class));

        // Service stops itself due to InterruptedException, no need to call stop()
    }

    @Test
    void shouldSendBatchNotificationAfterSuccessfulWrite() throws Exception {
        // Given
        resources.put("topic", Collections.singletonList(mockBatchTopic));
        service = new PersistenceService("test-persistence", config, resources);
        List<TickData> batch = createTestBatch("run-123", 0, 99);
        
        when(mockInputQueue.drainTo(anyList(), anyInt(), anyLong(), any(TimeUnit.class)))
            .thenAnswer(invocation -> {
                List<TickData> target = invocation.getArgument(0);
                target.addAll(batch);
                return batch.size();
            })
            .thenReturn(0); // Then return 0 to prevent infinite loop
            
        when(mockStorage.writeBatch(anyList(), anyLong(), anyLong()))
            .thenReturn("run-123/batch_0000000000000000000_0000000000000000099.pb.zst");
        
        // When
        service.start();
        await().atMost(2, TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("batches_written").longValue() == 1);
        service.stop();
        
        // Then
        verify(mockStorage).writeBatch(anyList(), eq(0L), eq(99L));
        verify(mockBatchTopic).send(argThat(notification -> 
            notification.getSimulationRunId().equals("run-123") &&
            notification.getStorageKey().equals("run-123/batch_0000000000000000000_0000000000000000099.pb.zst") &&
            notification.getTickStart() == 0 &&
            notification.getTickEnd() == 99 &&
            notification.getWrittenAtMs() > 0
        ));
        
        assertEquals(1, service.getMetrics().get("batches_written").longValue());
        assertEquals(1, service.getMetrics().get("notifications_sent").longValue());
        assertEquals(0, service.getMetrics().get("notifications_failed").longValue());
    }

    @Test
    @ExpectLog(level = LogLevel.WARN, messagePattern = "Failed batch has no DLQ configured, data will be lost: 100 ticks")
    void shouldRetryBatchIfTopicFails() throws Exception {
        // Given
        resources.put("topic", Collections.singletonList(mockBatchTopic));
        List<TickData> batch = createTestBatch("run-123", 0, 99);
        
        when(mockInputQueue.drainTo(anyList(), anyInt(), anyLong(), any(TimeUnit.class)))
            .thenAnswer(invocation -> {
                List<TickData> target = invocation.getArgument(0);
                target.addAll(batch);
                return batch.size();
            })
            .thenReturn(0);
            
        when(mockStorage.writeBatch(anyList(), anyLong(), anyLong()))
            .thenReturn("run-123/batch_0000000000000000000_0000000000000000099.pb.zst");
        
        // Topic always fails (InterruptedException)
        doThrow(new InterruptedException("Topic unavailable"))
            .when(mockBatchTopic).send(any(BatchInfo.class));
        
        service = new PersistenceService("test-persistence", config, resources);
        
        // When
        service.start();
        await().atMost(2, TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("batches_failed").longValue() == 1);
        
        // Service stops itself due to InterruptedException
        
        // Then - Storage was called once, topic was called once, then interrupted
        verify(mockStorage, times(1)).writeBatch(anyList(), eq(0L), eq(99L));
        verify(mockBatchTopic, times(1)).send(any(BatchInfo.class));
        
        assertEquals(0, service.getMetrics().get("batches_written").longValue());
        assertEquals(0, service.getMetrics().get("notifications_sent").longValue());
        assertEquals(1, service.getMetrics().get("notifications_failed").longValue());
        assertEquals(1, service.getMetrics().get("batches_failed").longValue());
    }

    @Test
    @ExpectLog(level = LogLevel.WARN, messagePattern = "Failed to write batch or send notification \\[ticks 0-99\\] after 2 retries, sending to DLQ")
    @ExpectLog(level = LogLevel.WARN, messagePattern = "Failed batch has no DLQ configured, data will be lost: 100 ticks")
    void shouldNotSendNotificationIfStorageWriteFails() throws Exception {
        // Given
        List<TickData> batch = createTestBatch("run-123", 0, 99);
        
        when(mockInputQueue.drainTo(anyList(), anyInt(), anyLong(), any(TimeUnit.class)))
            .thenAnswer(invocation -> {
                List<TickData> target = invocation.getArgument(0);
                target.addAll(batch);
                return batch.size();
            })
            .thenReturn(0);
            
        // Storage always fails
        when(mockStorage.writeBatch(anyList(), anyLong(), anyLong()))
            .thenThrow(new IOException("Storage unavailable"));
        
        service = new PersistenceService("test-persistence", config, resources);
        
        // When
        service.start();
        await().atMost(2, TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("batches_failed").longValue() == 1);
        service.stop();
        
        // Then - Topic was never called because storage failed
        verify(mockStorage, times(3)).writeBatch(anyList(), eq(0L), eq(99L)); // 1 + 2 retries
        verify(mockBatchTopic, never()).send(any(BatchInfo.class));
        
        assertEquals(0, service.getMetrics().get("batches_written").longValue());
        assertEquals(0, service.getMetrics().get("notifications_sent").longValue());
        assertEquals(0, service.getMetrics().get("notifications_failed").longValue());
        assertEquals(1, service.getMetrics().get("batches_failed").longValue());
    }

    @Test
    void shouldIncludeCorrectBatchInfoFields() throws Exception {
        // Given
        resources.put("topic", Collections.singletonList(mockBatchTopic));
        service = new PersistenceService("test-persistence", config, resources);
        List<TickData> batch = createTestBatch("run-abc-def", 1000, 1099);
        
        long beforeWrite = System.currentTimeMillis();
        
        when(mockInputQueue.drainTo(anyList(), anyInt(), anyLong(), any(TimeUnit.class)))
            .thenAnswer(invocation -> {
                List<TickData> target = invocation.getArgument(0);
                target.addAll(batch);
                return batch.size();
            })
            .thenReturn(0);
            
        when(mockStorage.writeBatch(anyList(), anyLong(), anyLong()))
            .thenReturn("run-abc-def/batch_0000000000000001000_0000000000000001099.pb.zst");
        
        // When
        service.start();
        await().atMost(2, TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("batches_written").longValue() == 1);
        service.stop();
        
        long afterWrite = System.currentTimeMillis();
        
        // Then
        verify(mockBatchTopic).send(argThat(notification -> {
            assertEquals("run-abc-def", notification.getSimulationRunId());
            assertEquals("run-abc-def/batch_0000000000000001000_0000000000000001099.pb.zst", notification.getStorageKey());
            assertEquals(1000, notification.getTickStart());
            assertEquals(1099, notification.getTickEnd());
            assertTrue(notification.getWrittenAtMs() >= beforeWrite);
            assertTrue(notification.getWrittenAtMs() <= afterWrite);
            return true;
        }));
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