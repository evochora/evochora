package org.evochora.datapipeline.services;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.SystemContracts;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
    private IStorageWriteResource mockStorage;

    @Mock
    private IOutputQueueResource<SystemContracts.DeadLetterMessage> mockDLQ;

    @Mock
    private MessageWriter mockMessageWriter;

    private MetadataPersistenceService service;
    private Map<String, List<IResource>> resources;
    private Config config;

    @BeforeEach
    void setUp() {
        resources = new HashMap<>();
        resources.put("input", Collections.singletonList(mockInputQueue));
        resources.put("storage", Collections.singletonList(mockStorage));

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
        when(mockInputQueue.take()).thenReturn(metadata);
        when(mockStorage.openWriter(anyString())).thenReturn(mockMessageWriter);

        // Start service (will process message and stop)
        service.start();

        // Wait for message to be processed
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("metadata_written").longValue() > 0);

        // Wait for service to stop itself (one-shot pattern)
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getCurrentState() == State.STOPPED);

        // Verify storage was called with correct key
        verify(mockStorage).openWriter("sim-123/metadata.pb");
        verify(mockMessageWriter).writeMessage(metadata);
        verify(mockMessageWriter).close();

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

        when(mockInputQueue.take()).thenReturn(metadata);
        when(mockStorage.openWriter(anyString())).thenReturn(mockMessageWriter);

        service.start();

        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("metadata_written").longValue() > 0);

        // Verify correct storage key format: {simulationRunId}/metadata.pb
        verify(mockStorage).openWriter("my-simulation-run-42/metadata.pb");
    }

    // ========== Validation Tests ==========

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*MetadataPersistenceService.*")
    @ExpectLog(level = LogLevel.ERROR, loggerPattern = ".*MetadataPersistenceService.*",
               messagePattern = ".*empty or null simulationRunId.*")
    void testEmptySimulationRunId() throws Exception {
        resources.put("dlq", Collections.singletonList(mockDLQ));
        service = new MetadataPersistenceService("test-metadata-persistence", config, resources);

        SimulationMetadata metadata = SimulationMetadata.newBuilder()
            .setSimulationRunId("")  // Empty ID
            .setInitialSeed(42)
            .build();

        when(mockInputQueue.take()).thenReturn(metadata);
        when(mockInputQueue.getResourceName()).thenReturn("context-data");
        when(mockDLQ.offer(any())).thenReturn(true);

        service.start();

        // Wait for error to be recorded
        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("metadata_failed").longValue() > 0);

        // Verify no storage write attempted
        verify(mockStorage, never()).openWriter(anyString());

        // Verify DLQ was called
        verify(mockDLQ).offer(any(SystemContracts.DeadLetterMessage.class));

        // Verify error recorded
        assertEquals(1, service.getErrors().size());
        assertTrue(service.getErrors().get(0).errorType().contains("INVALID_SIMULATION_RUN_ID"));
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*MetadataPersistenceService.*")
    @ExpectLog(level = LogLevel.ERROR, loggerPattern = ".*MetadataPersistenceService.*",
               messagePattern = ".*empty or null simulationRunId.*")
    void testNullSimulationRunId() throws Exception {
        resources.put("dlq", Collections.singletonList(mockDLQ));
        service = new MetadataPersistenceService("test-metadata-persistence", config, resources);

        SimulationMetadata metadata = SimulationMetadata.newBuilder()
            .setInitialSeed(42)
            // simulationRunId not set (defaults to empty string in proto3)
            .build();

        when(mockInputQueue.take()).thenReturn(metadata);
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

        when(mockInputQueue.take()).thenReturn(metadata);

        // First attempt fails, second succeeds
        when(mockStorage.openWriter(anyString()))
            .thenThrow(new IOException("Transient error"))
            .thenReturn(mockMessageWriter);

        service.start();

        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("metadata_written").longValue() > 0);

        // Verify retry occurred (2 openWriter calls)
        verify(mockStorage, times(2)).openWriter("sim-123/metadata.pb");

        // Verify success
        assertEquals(1, service.getMetrics().get("metadata_written").longValue());
        assertEquals(0, service.getMetrics().get("metadata_failed").longValue());
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*MetadataPersistenceService.*")
    @AllowLog(level = LogLevel.WARN, loggerPattern = ".*MetadataPersistenceService.*")
    @ExpectLog(level = LogLevel.ERROR, loggerPattern = ".*MetadataPersistenceService.*",
               messagePattern = ".*Failed to write metadata .* after .* retries.*")
    void testAllRetriesExhausted() throws Exception {
        resources.put("dlq", Collections.singletonList(mockDLQ));
        service = new MetadataPersistenceService("test-metadata-persistence", config, resources);

        SimulationMetadata metadata = createTestMetadata("sim-123");

        when(mockInputQueue.take()).thenReturn(metadata);
        when(mockInputQueue.getResourceName()).thenReturn("context-data");
        when(mockStorage.openWriter(anyString())).thenThrow(new IOException("Persistent error"));
        when(mockDLQ.offer(any())).thenReturn(true);

        service.start();

        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("metadata_failed").longValue() > 0);

        // Verify all retry attempts made (maxRetries=2 means 3 total attempts)
        verify(mockStorage, times(3)).openWriter("sim-123/metadata.pb");

        // Verify failure recorded
        assertEquals(0, service.getMetrics().get("metadata_written").longValue());
        assertEquals(1, service.getMetrics().get("metadata_failed").longValue());

        // Verify DLQ was called
        verify(mockDLQ).offer(any(SystemContracts.DeadLetterMessage.class));
    }

    // ========== DLQ Tests ==========

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*MetadataPersistenceService.*")
    @AllowLog(level = LogLevel.WARN, loggerPattern = ".*MetadataPersistenceService.*")
    @ExpectLog(level = LogLevel.ERROR, loggerPattern = ".*MetadataPersistenceService.*",
               messagePattern = ".*Failed to write metadata .* after .* retries.*")
    void testDLQWithConfiguredQueue() throws Exception {
        resources.put("dlq", Collections.singletonList(mockDLQ));
        service = new MetadataPersistenceService("test-metadata-persistence", config, resources);

        SimulationMetadata metadata = createTestMetadata("sim-456");

        when(mockInputQueue.take()).thenReturn(metadata);
        when(mockInputQueue.getResourceName()).thenReturn("context-data");
        when(mockStorage.openWriter(anyString())).thenThrow(new IOException("Storage failure"));
        when(mockDLQ.offer(any())).thenReturn(true);

        service.start();

        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("metadata_failed").longValue() > 0);

        // Verify DLQ message structure
        verify(mockDLQ).offer(argThat(dlqMsg -> {
            assertEquals("SimulationMetadata", dlqMsg.getMessageType());
            assertEquals("sim-456", dlqMsg.getMetadataOrDefault("simulationRunId", ""));
            assertEquals("sim-456/metadata.pb", dlqMsg.getMetadataOrDefault("storageKey", ""));
            assertTrue(dlqMsg.getFailureReason().contains("Storage failure"));
            return true;
        }));
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*MetadataPersistenceService.*")
    @AllowLog(level = LogLevel.WARN, loggerPattern = ".*MetadataPersistenceService.*")
    @ExpectLog(level = LogLevel.ERROR, loggerPattern = ".*MetadataPersistenceService.*",
               messagePattern = ".*Failed metadata has no DLQ configured.*")
    void testDLQNotConfigured() throws Exception {
        // No DLQ in resources
        service = new MetadataPersistenceService("test-metadata-persistence", config, resources);

        SimulationMetadata metadata = createTestMetadata("sim-789");

        when(mockInputQueue.take()).thenReturn(metadata);
        when(mockStorage.openWriter(anyString())).thenThrow(new IOException("Storage failure"));

        service.start();

        await().atMost(5, java.util.concurrent.TimeUnit.SECONDS)
            .until(() -> service.getMetrics().get("metadata_failed").longValue() > 0);

        // Verify failure recorded but no DLQ call
        assertEquals(1, service.getMetrics().get("metadata_failed").longValue());
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*MetadataPersistenceService.*")
    @AllowLog(level = LogLevel.WARN, loggerPattern = ".*MetadataPersistenceService.*")
    @ExpectLog(level = LogLevel.ERROR, loggerPattern = ".*MetadataPersistenceService.*",
               messagePattern = ".*DLQ is full.*")
    void testDLQFull() throws Exception {
        resources.put("dlq", Collections.singletonList(mockDLQ));
        service = new MetadataPersistenceService("test-metadata-persistence", config, resources);

        SimulationMetadata metadata = createTestMetadata("sim-full");

        when(mockInputQueue.take()).thenReturn(metadata);
        when(mockInputQueue.getResourceName()).thenReturn("context-data");
        when(mockStorage.openWriter(anyString())).thenThrow(new IOException("Storage failure"));
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

        // Mock queue.take() to block indefinitely (simulating waiting for message)
        when(mockInputQueue.take()).thenAnswer(invocation -> {
            Thread.sleep(Long.MAX_VALUE);
            return null;
        });

        service.start();

        // Wait a bit to ensure service is running and blocking on take()
        Thread.sleep(100);

        // Stop service while it's waiting
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

        when(mockInputQueue.take()).thenReturn(metadata);
        when(mockStorage.openWriter(anyString())).thenReturn(mockMessageWriter);

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
}
