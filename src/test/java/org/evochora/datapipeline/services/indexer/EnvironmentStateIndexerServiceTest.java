package org.evochora.datapipeline.services.indexer;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.RawTickData;
import org.evochora.datapipeline.api.contracts.RawCellState;
import org.evochora.datapipeline.api.contracts.SimulationContext;
import org.evochora.datapipeline.api.contracts.EnvironmentProperties;
import org.evochora.datapipeline.api.contracts.WorldTopology;
import org.evochora.datapipeline.api.channels.IInputChannel;
import org.evochora.datapipeline.core.InputChannelBinding;
import org.evochora.datapipeline.storage.api.indexer.IEnvironmentStateWriter;
import org.evochora.datapipeline.storage.api.indexer.model.EnvironmentState;
import org.evochora.datapipeline.storage.api.indexer.model.Position;
import org.evochora.testutils.LogMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Integration tests for EnvironmentStateIndexerService.
 * 
 * These tests verify the complete functionality of the service including
 * proper channel handling, context processing, tick data processing,
 * batch processing, and error handling using the public API.
 */
@Tag("integration")
class EnvironmentStateIndexerServiceTest {

    @Mock
    private IEnvironmentStateWriter mockStorageWriter;

    private EnvironmentStateIndexerService service;
    private Config config;
    private LogMonitor logMonitor;
    
    // Mock channels for testing
    private TestInputChannel<SimulationContext> mockContextChannel;
    private TestInputChannel<RawTickData> mockTickDataChannel;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        
        // Set logger levels to ERROR for services - only ERROR should be shown, WARN should cause test failure
        System.setProperty("org.evochora.datapipeline.services.indexer.EnvironmentStateIndexerService", "ERROR");
        System.setProperty("org.evochora.datapipeline.storage.impl.h2.H2SimulationRepository", "ERROR");
        System.setProperty("org.evochora.datapipeline.core.ServiceManager", "ERROR");
        
        // Initialize log monitor to capture all warnings and errors
        logMonitor = new LogMonitor();
        logMonitor.startMonitoring();
        
        // Create test configuration - using mock storage to avoid filesystem dependencies
        config = ConfigFactory.parseMap(Map.of(
            "batchSize", 2,
            "batchTimeoutMs", 100L,
            "storage", "mock-storage",
            "inputs", Map.of(
                "tickData", "raw-tick-data",
                "contextData", "context-data"
            ),
            "storageConfig", Map.of(
                "mock-storage", Map.of(
                    "className", "org.evochora.datapipeline.storage.api.indexer.IEnvironmentStateWriter",
                    "options", Map.of(
                        "inMemory", true,
                        "noFileSystem", true
                    )
                )
            )
        ));
        
        // Create mock channels
        mockContextChannel = new TestInputChannel<>();
        mockTickDataChannel = new TestInputChannel<>();
        
        service = new EnvironmentStateIndexerService(config) {
            protected IEnvironmentStateWriter createStorageWriter() {
                return mockStorageWriter;
            }
        };
        
        // Add channels to service
        service.addInputChannel("tickData", new InputChannelBinding<>("test-service", "tickData", "raw-tick-data", mockTickDataChannel));
        service.addInputChannel("contextData", new InputChannelBinding<>("test-service", "contextData", "context-data", mockContextChannel));
    }
    
    @AfterEach
    void tearDown() {
        // Stop monitoring and check for unexpected warnings/errors
        logMonitor.stopMonitoring();
        
        // Fail test if any unexpected warnings or errors were logged
        if (logMonitor.hasUnexpectedWarningsOrErrors()) {
            fail(logMonitor.getUnexpectedWarningsAndErrorsSummary());
        }
        
        // Cleanup service
        if (service.getServiceStatus().state() != org.evochora.datapipeline.api.services.State.STOPPED) {
            service.stop();
        }
    }

    @Test
    void shouldInitializeWithCorrectConfiguration() {
        // Test that service initializes without errors
        assertNotNull(service);
        assertEquals(org.evochora.datapipeline.api.services.State.STOPPED, 
            service.getServiceStatus().state());
    }

    @Test
    void shouldRejectInvalidConfiguration() {
        // Given - expect the configuration error (partial match)
        logMonitor.expectError("Invalid batchSize: -1. Must be greater than 0.");
        
        // Test negative batch size
        Config invalidConfig = ConfigFactory.parseMap(Map.of(
            "batchSize", -1,
            "batchTimeoutMs", 100L,
            "storage", "test-storage"
        ));
        
        assertThrows(IllegalArgumentException.class, () -> 
            new EnvironmentStateIndexerService(invalidConfig));
    }

    @Test
    void shouldProcessSimulationContextAndInitializeStorage() throws Exception {
        // Given
        SimulationContext context = createTestSimulationContext("test-run-123", 2);
        
        // When - start service
        service.start();
        
        // After starting the service, add the message to the channel
        mockContextChannel.addMessage(context);
        
        // Wait for processing (polling instead of sleep)
        waitForCondition(() -> service.isStorageInitialized(), 1000);
        
        // Then
        verify(mockStorageWriter).initialize(2, "test-run-123");
        assertTrue(service.isStorageInitialized());
        
        // Cleanup - close channels to stop the service gracefully
        mockContextChannel.close();
        mockTickDataChannel.close();
        service.stop();
    }

    @Test
    void shouldIgnoreDuplicateSimulationContexts() throws Exception {
        // Given
        SimulationContext context1 = createTestSimulationContext("test-run-1", 2);
        SimulationContext context2 = createTestSimulationContext("test-run-2", 2);
        
        // When - start service
        service.start();
        
        // After starting the service, add the messages to the channel
        mockContextChannel.addMessage(context1);
        mockContextChannel.addMessage(context2);
        
        // Then - should have processed only the first context
        verify(mockStorageWriter, timeout(1000)).initialize(2, "test-run-1");
        
        // Let the service run for a bit to ensure the second context is not processed
        // because the service switches to the tickData channel after initialization.
        Thread.sleep(200);
        verify(mockStorageWriter, never()).initialize(anyInt(), eq("test-run-2"));
        verifyNoMoreInteractions(mockStorageWriter);
        
        // Cleanup - close channels to stop the service gracefully
        mockContextChannel.close();
        mockTickDataChannel.close();
        service.stop();
    }

    @Test
    void shouldProcessTickDataAfterContextInitialization() throws Exception {
        // Given
        SimulationContext context = createTestSimulationContext("test-run-tick", 2);
        RawTickData tickData = createTestRawTickData(1L);
        
        // When - start service
        service.start();
        
        // After starting the service, add the messages to the channel
        mockContextChannel.addMessage(context);
        mockTickDataChannel.addMessage(tickData);
        
        // Wait for context processing
        waitForCondition(() -> service.isStorageInitialized(), 1000);
        
        // Then
        verify(mockStorageWriter, timeout(1000)).initialize(2, "test-run-tick");
        verify(mockStorageWriter, timeout(1000)).writeEnvironmentStates(any());
        
        // Cleanup - close channels to stop the service gracefully
        mockContextChannel.close();
        mockTickDataChannel.close();
        service.stop();
    }

    @Test
    void shouldBatchEnvironmentStatesCorrectly() throws Exception {
        // Given
        SimulationContext context = createTestSimulationContext("test-run-batch", 2);
        RawTickData tickData1 = createTestRawTickData("test-run-batch", 1L);
        RawTickData tickData2 = createTestRawTickData("test-run-batch", 2L);
        
        // When - start service
        service.start();
        
        // After starting the service, add the messages to the channel
        mockContextChannel.addMessage(context);
        mockTickDataChannel.addMessage(tickData1);
        mockTickDataChannel.addMessage(tickData2);
        
        // Wait for context processing
        waitForCondition(() -> service.isStorageInitialized(), 1000);
        
        // Then - should flush when batch size is reached
        verify(mockStorageWriter, timeout(1000)).initialize(2, "test-run-batch");
        verify(mockStorageWriter, timeout(1000).atLeastOnce()).writeEnvironmentStates(any());
        
        // Cleanup - close channels to stop the service gracefully
        mockContextChannel.close();
        mockTickDataChannel.close();
        service.stop();
    }

    @Test
    void shouldHandleStorageWriterErrors() throws Exception {
        // Given - expect the storage error
        logMonitor.expectError("Failed to flush batch of 1 environment states");
        
        doThrow(new RuntimeException("Storage error")).when(mockStorageWriter).writeEnvironmentStates(any());
        
        SimulationContext context = createTestSimulationContext("test-run-error", 2);
        RawTickData tickData = createTestRawTickData("test-run-error", 1L);
        
        // When - start service
        service.start();
        
        // After starting the service, add the messages to the channel
        mockContextChannel.addMessage(context);
        mockTickDataChannel.addMessage(tickData);
        
        // Wait for context processing
        waitForCondition(() -> service.isStorageInitialized(), 1000);
        
        // Wait for tick data processing to trigger the error
        // Then - should handle initialization errors gracefully
        verify(mockStorageWriter, timeout(1000)).initialize(2, "test-run-error");
        verify(mockStorageWriter, timeout(1000)).writeEnvironmentStates(any());
        
        // Cleanup - close channels to stop the service gracefully
        mockContextChannel.close();
        mockTickDataChannel.close();
        service.stop();
    }

    @Test
    void shouldHandleEmptyTickData() throws Exception {
        // Given
        SimulationContext context = createTestSimulationContext("test-run-empty", 2);
        RawTickData emptyTickData = createEmptyRawTickData(1L);
        
        // When - start service
        service.start();
        
        // After starting the service, add the messages to the channel
        mockContextChannel.addMessage(context);
        mockTickDataChannel.addMessage(emptyTickData);
        
        // Wait for context processing
        waitForCondition(() -> service.isStorageInitialized(), 1000);
        
        // Wait until the service has processed the empty tick
        waitForCondition(() -> "Last tick: 1".equals(service.getActivityInfo()), 1000);
        
        // Then
        verify(mockStorageWriter).initialize(2, "test-run-empty");
        verify(mockStorageWriter, never()).writeEnvironmentStates(any());
        
        // Cleanup - close channels to stop the service gracefully
        mockContextChannel.close();
        mockTickDataChannel.close();
        service.stop();
    }

    @Test
    void shouldExtractCorrectEnvironmentStates() throws Exception {
        // Given
        SimulationContext context = createTestSimulationContext("test-run-extract", 2);
        RawTickData tickData = createTestRawTickData("test-run-extract", 1L);
        
        // When - start service
        service.start();
        
        // After starting the service, add the messages to the channel
        mockContextChannel.addMessage(context);
        mockTickDataChannel.addMessage(tickData);
        
        // Wait for context processing
        waitForCondition(() -> service.isStorageInitialized(), 1000);
        
        // Then - verify that states contain correct data
        verify(mockStorageWriter, timeout(1000)).writeEnvironmentStates(argThat(states -> {
            return states.stream().anyMatch(state -> 
                state.tick() == 1L && 
                state.position().getCoordinate(0) == 1 && 
                state.position().getCoordinate(1) == 1 &&
                state.owner() == 1 &&
                state.moleculeValue() == 100
            );
        }));
        
        // Cleanup - close channels to stop the service gracefully
        mockContextChannel.close();
        mockTickDataChannel.close();
        service.stop();
    }

    @Test
    void shouldHandlePauseAndResume() throws Exception {
        // Given
        SimulationContext context = createTestSimulationContext("test-run-pause", 2);
        RawTickData tickData = createTestRawTickData("test-run-pause", 1L);
        
        // When - start service
        service.start();
        
        // After starting the service, add the messages to the channel
        mockContextChannel.addMessage(context);
        mockTickDataChannel.addMessage(tickData);
        
        // Wait for context processing
        waitForCondition(() -> service.isStorageInitialized(), 1000);
        
        // Pause service and temporarily ignore warnings that might occur from scheduler shutdown
        logMonitor.ignoreWarnings(true);
        service.pause();
        logMonitor.ignoreWarnings(false);
        
        // Verify that no data is processed while paused
        verify(mockStorageWriter, never()).writeEnvironmentStates(any());
        
        // Resume service
        service.resume();
        
        // Then
        verify(mockStorageWriter, timeout(1000)).writeEnvironmentStates(any());
        
        // Cleanup - close channels to stop the service gracefully
        mockContextChannel.close();
        mockTickDataChannel.close();
        service.stop();
    }

    // Helper methods

    private SimulationContext createTestSimulationContext(String simulationRunId, int dimensions) {
        EnvironmentProperties envProps = new EnvironmentProperties(
            new int[dimensions], 
            WorldTopology.TORUS
        );
        SimulationContext context = new SimulationContext();
        context.setSimulationRunId(simulationRunId);
        context.setEnvironment(envProps);
        return context;
    }

    private RawTickData createTestRawTickData(long tickNumber) {
        return createTestRawTickData("test-run-tick", tickNumber);
    }
    
    private RawTickData createTestRawTickData(String simulationRunId, long tickNumber) {
        RawCellState cell = new RawCellState();
        cell.setPosition(new int[]{1, 1});
        cell.setOwnerId(1);
        cell.setType(org.evochora.runtime.Config.TYPE_ENERGY);
        cell.setValue(100);
        
        RawTickData tickData = new RawTickData();
        tickData.setSimulationRunId(simulationRunId);
        tickData.setTickNumber(tickNumber);
        tickData.setCells(List.of(cell));
        return tickData;
    }

    private RawTickData createEmptyRawTickData(long tickNumber) {
        RawTickData tickData = new RawTickData();
        tickData.setSimulationRunId("test-run-empty");
        tickData.setTickNumber(tickNumber);
        tickData.setCells(List.of());
        return tickData;
    }

    private void waitForCondition(java.util.function.BooleanSupplier condition, long timeoutMs) {
        long startTime = System.currentTimeMillis();
        while (!condition.getAsBoolean() && (System.currentTimeMillis() - startTime) < timeoutMs) {
            try {
                // Poll every 10ms to avoid busy-waiting and high CPU usage
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("waitForCondition was interrupted");
            }
        }
        assertTrue(condition.getAsBoolean(), "Condition not met within timeout");
    }

    /**
     * Test implementation of IInputChannel for testing purposes.
     */
    private static class TestInputChannel<T> implements IInputChannel<T> {
        private final BlockingQueue<T> messages = new LinkedBlockingQueue<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicBoolean shouldBlock = new AtomicBoolean(true);

        public void addMessage(T message) {
            if (!closed.get()) {
                messages.offer(message);
            }
        }

        public boolean isEmpty() {
            return messages.isEmpty();
        }

        public T read() throws InterruptedException {
            if (closed.get()) {
                throw new InterruptedException("Channel closed");
            }
            
            // If shouldBlock is true, block until message is available
            if (shouldBlock.get()) {
                return messages.take(); // This blocks until a message is available
            } else {
                // For non-blocking behavior, poll with timeout
                T message = messages.poll(100, TimeUnit.MILLISECONDS);
                if (message == null) {
                    throw new InterruptedException("No more messages available");
                }
                return message;
            }
        }

        public void close() {
            closed.set(true);
            // Wake up any waiting threads by interrupting them
            // The take() method will throw InterruptedException when closed
        }

        public boolean isClosed() {
            return closed.get();
        }
        
        public void setBlocking(boolean blocking) {
            shouldBlock.set(blocking);
        }
    }
}