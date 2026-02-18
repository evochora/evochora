package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.MetadataInfo;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.SystemContracts;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.api.resources.queues.IInputQueueResource;
import org.evochora.datapipeline.api.resources.queues.IOutputQueueResource;
import org.evochora.datapipeline.api.resources.queues.StreamingBatch;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageWrite;
import org.evochora.datapipeline.api.resources.topics.ITopicWriter;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for MetadataPersistenceService with mocked dependencies.
 * Tests cover configuration validation, message processing, retry logic, DLQ handling,
 * and one-shot service lifecycle.
 */
@Tag("unit")
@ExtendWith(MockitoExtension.class)
class MetadataPersistenceServiceTest {

    @Mock
    private IInputQueueResource<SimulationMetadata> mockInputQueue;

    @Mock
    private IBatchStorageWrite mockStorage;

    @Mock
    private ITopicWriter<MetadataInfo> mockTopic;

    @Mock
    private IOutputQueueResource<SystemContracts.DeadLetterMessage> mockDLQ;

    private MetadataPersistenceService service;
    private Map<String, List<IResource>> resources;
    private Config config;

    @BeforeEach
    void setUp() {
        resources = new HashMap<>();
        resources.put("input", Collections.singletonList(mockInputQueue));
        resources.put("storage", Collections.singletonList(mockStorage));
        resources.put("topic", Collections.singletonList(mockTopic));

        config = ConfigFactory.parseMap(Map.of(
            "maxRetries", 2,
            "retryBackoffMs", 50
        ));
    }

    // ========== Constructor Tests ==========

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*MetadataPersistenceService.*")
    void testConstructorWithRequiredResources() {
        service = new MetadataPersistenceService("test-metadata-persistence", config, resources);

        assertNotNull(service);
        assertEquals("test-metadata-persistence", service.serviceName);
        assertTrue(service.isHealthy());
        assertEquals(0, service.getErrors().size());
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*MetadataPersistenceService.*")
    void testConstructorWithOptionalDLQ() {
        resources.put("dlq", Collections.singletonList(mockDLQ));

        service = new MetadataPersistenceService("test-metadata-persistence", config, resources);

        assertNotNull(service);
        assertTrue(service.isHealthy());
    }

    @Test
    void testConstructorWithInvalidMaxRetries() {
        Config invalidConfig = ConfigFactory.parseMap(Map.of("maxRetries", -1));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            new MetadataPersistenceService("test-metadata-persistence", invalidConfig, resources);
        });

        assertTrue(exception.getMessage().contains("maxRetries cannot be negative"));
    }

    @Test
    void testConstructorWithInvalidRetryBackoff() {
        Config invalidConfig = ConfigFactory.parseMap(Map.of("retryBackoffMs", -1));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            new MetadataPersistenceService("test-metadata-persistence", invalidConfig, resources);
        });

        assertTrue(exception.getMessage().contains("retryBackoffMs cannot be negative"));
    }

    @Test
    void testConstructorWithMissingInputResource() {
        resources.remove("input");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            new MetadataPersistenceService("test-metadata-persistence", config, resources);
        });

        assertTrue(exception.getMessage().contains("Resource port 'input' is not configured"));
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*MetadataPersistenceService.*")
    void testConstructorWithMissingStorageResource() {
        resources.remove("storage");

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            new MetadataPersistenceService("test-metadata-persistence", config, resources);
        });

        assertTrue(exception.getMessage().contains("Resource port 'storage' is not configured"));
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*MetadataPersistenceService.*")
    @AllowLog(level = LogLevel.WARN, messagePattern = "MetadataPersistenceService initialized WITHOUT topic.*")
    void testConstructorWithMissingTopicResource() {
        resources.remove("topic");

        // Topic is now optional - service should start with WARN log
        MetadataPersistenceService service = new MetadataPersistenceService("test-metadata-persistence", config, resources);

        assertNotNull(service);
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*MetadataPersistenceService.*")
    void testConstructorWithDefaultConfiguration() {
        Config emptyConfig = ConfigFactory.parseMap(Map.of());

        service = new MetadataPersistenceService("test-metadata-persistence", emptyConfig, resources);

        assertNotNull(service);
        // Default values: maxRetries=3, retryBackoffMs=1000
    }

    // ========== Message Processing Tests ==========

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*MetadataPersistenceService.*")
    void testSuccessfulMetadataWrite() throws Exception {
        service = new MetadataPersistenceService("test-metadata-persistence", config, resources);

        SimulationMetadata metadata = createTestMetadata("sim-123");

        // Mock queue to return metadata
        when(mockInputQueue.receiveBatch(anyInt(), anyLong(), any(TimeUnit.class))).thenReturn(batchOf(metadata));
        when(mockStorage.writeMessage(anyString(), any())).thenReturn(StoragePath.of("sim-123/raw/metadata.pb"));

        // Start service (will process message and stop)
        service.start();

        // Wait for message to be processed
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("metadata_written").longValue() > 0);

        // Wait for service to stop itself (one-shot pattern)
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getCurrentState() == State.STOPPED);

        // Verify storage was called with correct key and metadata
        verify(mockStorage).writeMessage("sim-123/raw/metadata.pb", metadata);

        // Verify topic notification was sent
        verify(mockTopic).setSimulationRun(eq("sim-123"));
        verify(mockTopic).send(argThat(info ->
            info.getSimulationRunId().equals("sim-123") &&
            info.getStoragePath().startsWith("sim-123/raw/metadata.pb") &&
            info.getWrittenAtMs() > 0
        ));

        // Verify metrics
        assertEquals(1, service.getMetrics().get("metadata_written").longValue());
        assertEquals(0, service.getMetrics().get("metadata_failed").longValue());
        assertTrue(service.getMetrics().get("bytes_written").longValue() > 0);
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*MetadataPersistenceService.*")
    void testStorageKeyGeneration() throws Exception {
        service = new MetadataPersistenceService("test-metadata-persistence", config, resources);

        SimulationMetadata metadata = createTestMetadata("my-simulation-run-42");

        when(mockInputQueue.receiveBatch(anyInt(), anyLong(), any(TimeUnit.class))).thenReturn(batchOf(metadata));
        when(mockStorage.writeMessage(anyString(), any())).thenReturn(StoragePath.of("sim-123/raw/metadata.pb"));

        service.start();

        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("metadata_written").longValue() > 0);

        // Verify correct storage key format: {simulationRunId}/raw/metadata.pb
        verify(mockStorage).writeMessage("my-simulation-run-42/raw/metadata.pb", metadata);
    }

    // ========== Validation Tests ==========

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*MetadataPersistenceService.*")
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*MetadataPersistenceService.*",
               messagePattern = ".*empty or null simulationRunId.*")
    void testEmptySimulationRunId() throws Exception {
        resources.put("dlq", Collections.singletonList(mockDLQ));
        service = new MetadataPersistenceService("test-metadata-persistence", config, resources);

        SimulationMetadata metadata = SimulationMetadata.newBuilder()
            .setSimulationRunId("")  // Empty ID
            .setInitialSeed(42)
            .build();

        when(mockInputQueue.receiveBatch(anyInt(), anyLong(), any(TimeUnit.class))).thenReturn(batchOf(metadata));
        when(mockInputQueue.getResourceName()).thenReturn("context-data");
        when(mockDLQ.offer(any())).thenReturn(true);

        service.start();

        // Wait for error to be recorded
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("metadata_failed").longValue() > 0);

        // Verify no storage write attempted
        verify(mockStorage, never()).writeMessage(anyString(), any());

        // Verify DLQ was called
        verify(mockDLQ).offer(any(SystemContracts.DeadLetterMessage.class));

        // Verify error recorded
        assertEquals(1, service.getErrors().size());
        assertTrue(service.getErrors().get(0).errorType().contains("INVALID_SIMULATION_RUN_ID"));
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*MetadataPersistenceService.*")
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*MetadataPersistenceService.*",
               messagePattern = ".*empty or null simulationRunId.*")
    void testNullSimulationRunId() throws Exception {
        resources.put("dlq", Collections.singletonList(mockDLQ));
        service = new MetadataPersistenceService("test-metadata-persistence", config, resources);

        SimulationMetadata metadata = SimulationMetadata.newBuilder()
            .setInitialSeed(42)
            // simulationRunId not set (defaults to empty string in proto3)
            .build();

        when(mockInputQueue.receiveBatch(anyInt(), anyLong(), any(TimeUnit.class))).thenReturn(batchOf(metadata));
        when(mockInputQueue.getResourceName()).thenReturn("context-data");
        when(mockDLQ.offer(any())).thenReturn(true);

        service.start();

        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("metadata_failed").longValue() > 0);

        verify(mockDLQ).offer(any(SystemContracts.DeadLetterMessage.class));
    }

    // ========== Retry Logic Tests ==========

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*MetadataPersistenceService.*")
    @AllowLog(level = LogLevel.WARN, loggerPattern = ".*MetadataPersistenceService.*",
              messagePattern = ".*Failed to write metadata.*retrying.*")
    void testRetryOnTransientFailure() throws Exception {
        service = new MetadataPersistenceService("test-metadata-persistence", config, resources);

        SimulationMetadata metadata = createTestMetadata("sim-123");

        when(mockInputQueue.receiveBatch(anyInt(), anyLong(), any(TimeUnit.class))).thenReturn(batchOf(metadata));

        // First attempt fails, second succeeds
        when(mockStorage.writeMessage(anyString(), any()))
            .thenThrow(new IOException("Transient error"))
            .thenReturn(StoragePath.of("sim-123/raw/metadata.pb"));

        service.start();

        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("metadata_written").longValue() > 0);

        // Verify retry occurred (2 writeMessage calls)
        verify(mockStorage, times(2)).writeMessage("sim-123/raw/metadata.pb", metadata);

        // Verify success
        assertEquals(1, service.getMetrics().get("metadata_written").longValue());
        assertEquals(0, service.getMetrics().get("metadata_failed").longValue());
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*MetadataPersistenceService.*")
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*MetadataPersistenceService.*",
               messagePattern = ".*Failed to write metadata .* after .* retries, sending to DLQ")
    void testAllRetriesExhausted() throws Exception {
        resources.put("dlq", Collections.singletonList(mockDLQ));
        service = new MetadataPersistenceService("test-metadata-persistence", config, resources);

        SimulationMetadata metadata = createTestMetadata("sim-123");

        when(mockInputQueue.receiveBatch(anyInt(), anyLong(), any(TimeUnit.class))).thenReturn(batchOf(metadata));
        when(mockInputQueue.getResourceName()).thenReturn("context-data");
        doThrow(new IOException("Persistent error")).when(mockStorage).writeMessage(anyString(), any());
        when(mockDLQ.offer(any())).thenReturn(true);

        service.start();

        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("metadata_failed").longValue() > 0);

        // Verify all retry attempts made (maxRetries=2 means 3 total attempts)
        verify(mockStorage, times(3)).writeMessage("sim-123/raw/metadata.pb", metadata);

        // Verify failure recorded
        assertEquals(0, service.getMetrics().get("metadata_written").longValue());
        assertEquals(1, service.getMetrics().get("metadata_failed").longValue());

        // Verify DLQ was called
        verify(mockDLQ).offer(any(SystemContracts.DeadLetterMessage.class));
    }

    // ========== DLQ Tests ==========

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*MetadataPersistenceService.*")
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*MetadataPersistenceService.*",
               messagePattern = ".*Failed to write metadata .* after .* retries, sending to DLQ")
    void testDLQWithConfiguredQueue() throws Exception {
        resources.put("dlq", Collections.singletonList(mockDLQ));
        service = new MetadataPersistenceService("test-metadata-persistence", config, resources);

        SimulationMetadata metadata = createTestMetadata("sim-456");

        when(mockInputQueue.receiveBatch(anyInt(), anyLong(), any(TimeUnit.class))).thenReturn(batchOf(metadata));
        when(mockInputQueue.getResourceName()).thenReturn("context-data");
        doThrow(new IOException("Storage failure")).when(mockStorage).writeMessage(anyString(), any());
        when(mockDLQ.offer(any())).thenReturn(true);

        service.start();

        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("metadata_failed").longValue() > 0);

        // Verify DLQ message structure
        verify(mockDLQ).offer(argThat(dlqMsg -> {
            assertEquals("SimulationMetadata", dlqMsg.getMessageType());
            assertEquals("sim-456", dlqMsg.getMetadataOrDefault("simulationRunId", ""));
            assertEquals("sim-456/raw/metadata.pb", dlqMsg.getMetadataOrDefault("storageKey", ""));
            assertTrue(dlqMsg.getFailureReason().contains("Storage failure"));
            return true;
        }));
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*MetadataPersistenceService.*")
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*MetadataPersistenceService.*",
               messagePattern = ".*Failed metadata has no DLQ configured.*")
    void testDLQNotConfigured() throws Exception {
        // No DLQ in resources
        service = new MetadataPersistenceService("test-metadata-persistence", config, resources);

        SimulationMetadata metadata = createTestMetadata("sim-789");

        when(mockInputQueue.receiveBatch(anyInt(), anyLong(), any(TimeUnit.class))).thenReturn(batchOf(metadata));
        doThrow(new IOException("Storage failure")).when(mockStorage).writeMessage(anyString(), any());

        service.start();

        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("metadata_failed").longValue() > 0);

        // Verify failure recorded but no DLQ call
        assertEquals(1, service.getMetrics().get("metadata_failed").longValue());
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*MetadataPersistenceService.*")
    @ExpectLog(level = LogLevel.WARN, loggerPattern = ".*MetadataPersistenceService.*",
               messagePattern = ".*DLQ is full.*")
    void testDLQFull() throws Exception {
        resources.put("dlq", Collections.singletonList(mockDLQ));
        service = new MetadataPersistenceService("test-metadata-persistence", config, resources);

        SimulationMetadata metadata = createTestMetadata("sim-full");

        when(mockInputQueue.receiveBatch(anyInt(), anyLong(), any(TimeUnit.class))).thenReturn(batchOf(metadata));
        when(mockInputQueue.getResourceName()).thenReturn("context-data");
        doThrow(new IOException("Storage failure")).when(mockStorage).writeMessage(anyString(), any());
        when(mockDLQ.offer(any())).thenReturn(false); // DLQ full

        service.start();

        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getErrors().size() > 0);

        // Verify error recorded for DLQ full
        assertTrue(service.getErrors().stream()
            .anyMatch(e -> e.errorType().equals("DLQ_FULL")));
    }

    // ========== Shutdown Tests ==========

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*MetadataPersistenceService.*")
    void testGracefulShutdownBeforeMessageReceived() throws Exception {
        service = new MetadataPersistenceService("test-metadata-persistence", config, resources);

        // Mock queue to return empty batch (simulating no message available)
        // Service will check isStopRequested() between polls
        when(mockInputQueue.receiveBatch(anyInt(), anyLong(), any(TimeUnit.class))).thenReturn(emptyBatch());

        service.start();

        // Wait a bit to ensure service is running and polling
        Thread.sleep(100);

        // Stop service while it's waiting (graceful shutdown via isStopRequested)
        service.stop();

        // Verify service stopped cleanly
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getCurrentState() == State.STOPPED);

        // No metadata processed
        assertEquals(0, service.getMetrics().get("metadata_written").longValue());
    }

    // ========== Metrics Tests ==========

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*MetadataPersistenceService.*")
    void testMetricsAccuracy() throws Exception {
        service = new MetadataPersistenceService("test-metadata-persistence", config, resources);

        SimulationMetadata metadata = createTestMetadata("sim-metrics");

        when(mockInputQueue.receiveBatch(anyInt(), anyLong(), any(TimeUnit.class))).thenReturn(batchOf(metadata));
        when(mockStorage.writeMessage(anyString(), any())).thenReturn(StoragePath.of("sim-123/raw/metadata.pb"));

        service.start();

        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("metadata_written").longValue() == 1);

        Map<String, Number> metrics = service.getMetrics();
        assertEquals(1, metrics.get("metadata_written").longValue());
        assertEquals(0, metrics.get("metadata_failed").longValue());
        assertTrue(metrics.get("bytes_written").longValue() > 0);
        assertTrue(metrics.get("bytes_written").longValue() == metadata.getSerializedSize());
    }

    // ========== Helper Methods ==========

    private SimulationMetadata createTestMetadata(String simulationRunId) {
        return SimulationMetadata.newBuilder()
            .setSimulationRunId(simulationRunId)
            .setInitialSeed(12345)
            .setStartTimeMs(System.currentTimeMillis())
            .build();
    }

    @SafeVarargs
    private static <T> StreamingBatch<T> batchOf(T... items) {
        List<T> list = List.of(items);
        return new StreamingBatch<T>() {
            @Override public int size() { return list.size(); }
            @Override public Iterator<T> iterator() { return list.iterator(); }
            @Override public void commit() {}
            @Override public void close() {}
        };
    }

    private static <T> StreamingBatch<T> emptyBatch() {
        return new StreamingBatch<T>() {
            @Override public int size() { return 0; }
            @Override public Iterator<T> iterator() { return Collections.emptyIterator(); }
            @Override public void commit() {}
            @Override public void close() {}
        };
    }
}
