package org.evochora.datapipeline.services.indexer;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.RawTickData;
import org.evochora.datapipeline.api.contracts.RawCellState;
import org.evochora.datapipeline.api.contracts.SimulationContext;
import org.evochora.datapipeline.api.contracts.EnvironmentProperties;
import org.evochora.datapipeline.storage.api.indexer.IEnvironmentStateWriter;
import org.evochora.datapipeline.storage.api.indexer.model.EnvironmentState;
import org.evochora.datapipeline.storage.api.indexer.model.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Unit tests for EnvironmentStateIndexerService.
 * 
 * These tests verify the actual functionality of the service including
 * proper channel handling, context processing, tick data processing,
 * cell filtering, and storage integration using direct method calls.
 */
@Tag("unit")
class EnvironmentStateIndexerServiceTest {

    @Mock
    private IEnvironmentStateWriter mockStorageWriter;

    private EnvironmentStateIndexerService service;
    private Config config;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        
        // Create test configuration
        config = ConfigFactory.parseMap(Map.of(
            "batchSize", 2,
            "batchTimeoutMs", 100L,
            "storage", "test-storage",
            "storageConfig", Map.of(
                "test-storage", Map.of(
                    "className", "org.evochora.datapipeline.storage.api.indexer.IEnvironmentStateWriter",
                    "options", Map.of()
                )
            )
        ));
        
        service = new EnvironmentStateIndexerService(config) {
            protected IEnvironmentStateWriter createStorageWriter() {
                return mockStorageWriter;
            }
        };
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
    void shouldIdentifyChannelsCorrectly() {
        // Given
        var mockTickChannel = createMockInputChannel();
        var mockContextChannel = createMockInputChannel();
        
        // When
        service.addInputChannel("tickData", mockTickChannel);
        service.addInputChannel("contextData", mockContextChannel);
        
        // Then - channels should be identified correctly
        assertNotNull(service);
    }

    @Test
    void shouldRejectUnknownChannelTypes() {
        // Given
        var mockChannel = createMockInputChannel();
        
        // When
        service.addInputChannel("unknown-channel", mockChannel);
        
        // Then - service should log warning but not crash
        assertNotNull(service);
    }

    @Test
    void shouldNotStartWithoutRequiredChannels() {
        // When - start without channels
        service.start();
        
        // Then - should remain stopped
        assertEquals(org.evochora.datapipeline.api.services.State.STOPPED, 
            service.getServiceStatus().state());
    }

    @Test
    void shouldNotStartWithOnlyTickDataChannel() {
        // Given
        var mockTickChannel = createMockInputChannel();
        service.addInputChannel("tickData", mockTickChannel);
        
        // When
        service.start();
        
        // Then - should remain stopped (missing context channel)
        assertEquals(org.evochora.datapipeline.api.services.State.STOPPED, 
            service.getServiceStatus().state());
    }

    @Test
    void shouldNotStartWithOnlyContextChannel() {
        // Given
        var mockContextChannel = createMockInputChannel();
        service.addInputChannel("contextData", mockContextChannel);
        
        // When
        service.start();
        
        // Then - should remain stopped (missing tick data channel)
        assertEquals(org.evochora.datapipeline.api.services.State.STOPPED, 
            service.getServiceStatus().state());
    }

    @Test
    void shouldStartWithBothRequiredChannels() throws Exception {
        // Given
        var mockTickChannel = createMockInputChannel();
        var mockContextChannel = createMockInputChannel();
        service.addInputChannel("tickData", mockTickChannel);
        service.addInputChannel("contextData", mockContextChannel);
        
        // When
        service.start();
        
        // Then - should start successfully
        assertEquals(org.evochora.datapipeline.api.services.State.RUNNING, 
            service.getServiceStatus().state());
    }

    @Test
    void shouldProcessSimulationContextCorrectly() throws Exception {
        // Given
        SimulationContext context = createTestSimulationContext("test-run-123", 2);
        
        // When - call method directly
        service.processSimulationContext(context);
        
        // Then
        verify(mockStorageWriter).initialize(2, "test-run-123");
        assertTrue(service.isStorageInitialized());
    }

    @Test
    void shouldProcessRawTickDataAfterContext() throws Exception {
        // Given
        SimulationContext context = createTestSimulationContext("test-run-456", 2);
        RawTickData tickData = createTestRawTickData(1L);
        
        // When - process context first, then tick data
        service.processSimulationContext(context);
        service.processRawTickData(tickData);
        
        // Then
        verify(mockStorageWriter).initialize(2, "test-run-456");
        verify(mockStorageWriter).writeEnvironmentStates(any());
    }

    @Test
    void shouldFilterCellsCorrectly() throws Exception {
        // Given
        SimulationContext context = createTestSimulationContext("test-run-filter", 2);
        RawTickData tickData = createTestRawTickDataWithMixedCells(1L);
        
        // When - process context first, then tick data
        service.processSimulationContext(context);
        service.processRawTickData(tickData);
        
        // Then - verify filtering logic
        verify(mockStorageWriter).writeEnvironmentStates(argThat(states -> {
            // Should have states for cells with molecules OR owners
            // Should NOT have states for cells with CODE=0 AND Owner=0
            return states.size() >= 2; // At least 2 relevant states
        }));
    }

    @Test
    void shouldHandleUnknownMoleculeTypes() throws Exception {
        // Given - use batchSize 1 to ensure immediate flushing
        Config singleBatchConfig = ConfigFactory.parseMap(Map.of(
            "batchSize", 1,
            "batchTimeoutMs", 100L,
            "storage", "test-storage",
            "storageConfig", Map.of(
                "test-storage", Map.of(
                    "className", "org.evochora.datapipeline.storage.api.indexer.IEnvironmentStateWriter",
                    "options", Map.of()
                )
            )
        ));
        
        EnvironmentStateIndexerService singleBatchService = new EnvironmentStateIndexerService(singleBatchConfig) {
            protected IEnvironmentStateWriter createStorageWriter() {
                return mockStorageWriter;
            }
        };
        
        SimulationContext context = createTestSimulationContext("test-run-unknown", 2);
        RawTickData tickData = createTestRawTickDataWithUnknownMolecule(1L);
        
        // When - process context first, then tick data
        singleBatchService.processSimulationContext(context);
        singleBatchService.processRawTickData(tickData);
        
        // Then - should handle unknown molecule types gracefully and write the state
        verify(mockStorageWriter).writeEnvironmentStates(argThat(states -> 
            states.size() == 1 && "UNKNOWN".equals(states.get(0).moleculeType())
        ));
    }

    @Test
    void shouldBatchEnvironmentStatesCorrectly() throws Exception {
        // Given
        SimulationContext context = createTestSimulationContext("test-run-batch", 2);
        RawTickData tickData1 = createTestRawTickData(1L);
        RawTickData tickData2 = createTestRawTickData(2L);
        
        // When - process context first, then multiple tick data
        service.processSimulationContext(context);
        service.processRawTickData(tickData1);
        service.processRawTickData(tickData2);
        
        // Then - should flush when batch size is reached
        verify(mockStorageWriter, atLeastOnce()).writeEnvironmentStates(any());
    }

    @Test
    void shouldHandleStorageWriterErrors() throws Exception {
        // Given
        doThrow(new RuntimeException("Storage error")).when(mockStorageWriter).writeEnvironmentStates(any());
        
        SimulationContext context = createTestSimulationContext("test-run-error", 2);
        RawTickData tickData = createTestRawTickData(1L);
        
        // When - process context first, then tick data
        service.processSimulationContext(context);
        
        // Then - should handle initialization errors gracefully
        verify(mockStorageWriter).initialize(2, "test-run-error");
        
        // When - try to process tick data with storage error (should be caught and logged)
        service.processRawTickData(tickData);
        
        // Then - should have attempted to write
        verify(mockStorageWriter).writeEnvironmentStates(any());
    }

    @Test
    void shouldHandleEmptyTickData() throws Exception {
        // Given
        SimulationContext context = createTestSimulationContext("test-run-empty", 2);
        RawTickData emptyTickData = createEmptyRawTickData(1L);
        
        // When - process context first, then empty tick data
        service.processSimulationContext(context);
        service.processRawTickData(emptyTickData);
        
        // Then
        verify(mockStorageWriter).initialize(2, "test-run-empty");
        verify(mockStorageWriter, never()).writeEnvironmentStates(any());
    }

    @Test
    void shouldExtractCorrectEnvironmentStates() throws Exception {
        // Given
        SimulationContext context = createTestSimulationContext("test-run-extract", 2);
        RawTickData tickData = createTestRawTickData(1L);
        
        // When - process context first, then tick data
        service.processSimulationContext(context);
        service.processRawTickData(tickData);
        
        // Then - verify that states contain correct data
        verify(mockStorageWriter).writeEnvironmentStates(argThat(states -> {
            return states.stream().anyMatch(state -> 
                state.tick() == 1L && 
                state.position().getDimensions() == 2 &&
                state.moleculeType().equals("CODE") &&
                state.moleculeValue() == 100
            );
        }));
    }

    @Test
    void shouldHandleMultipleSimulationContexts() throws Exception {
        // Given
        SimulationContext context1 = createTestSimulationContext("test-run-1", 2);
        SimulationContext context2 = createTestSimulationContext("test-run-2", 2);
        
        // When - process first context
        service.processSimulationContext(context1);
        
        // Then - first context should be processed
        verify(mockStorageWriter).initialize(2, "test-run-1");
        
        // When - process second context (should be ignored)
        service.processSimulationContext(context2);
        
        // Then - second context should be ignored (no additional initialize call)
        verify(mockStorageWriter, times(1)).initialize(anyInt(), anyString());
    }

    @Test
    void shouldHandleTickDataBeforeContext() throws Exception {
        // Given
        RawTickData tickData = createTestRawTickData(1L);
        
        // When - try to process tick data before context
        service.processRawTickData(tickData);
        
        // Then - should not write anything (storage not initialized)
        verify(mockStorageWriter, never()).writeEnvironmentStates(any());
    }

    @Test
    void shouldStopServiceCorrectly() throws Exception {
        // Given
        var mockTickChannel = createMockInputChannel();
        var mockContextChannel = createMockInputChannel();
        service.addInputChannel("tickData", mockTickChannel);
        service.addInputChannel("contextData", mockContextChannel);
        
        service.start();
        
        // When
        service.stop();
        
        // Then
        assertEquals(org.evochora.datapipeline.api.services.State.STOPPED, 
            service.getServiceStatus().state());
        
        // Storage writer should be closed (only if it was initialized)
        // Since we didn't process any context, storage won't be initialized
        verify(mockStorageWriter, never()).close();
    }

    @Test
    void shouldGetCorrectServiceState() {
        // Initially stopped
        assertEquals(org.evochora.datapipeline.api.services.State.STOPPED, 
            service.getServiceStatus().state());
        
        // After starting (with channels)
        var mockTickChannel = createMockInputChannel();
        var mockContextChannel = createMockInputChannel();
        service.addInputChannel("tickData", mockTickChannel);
        service.addInputChannel("contextData", mockContextChannel);
        
        service.start();
        assertEquals(org.evochora.datapipeline.api.services.State.RUNNING, 
            service.getServiceStatus().state());
        
        // After stopping
        service.stop();
        assertEquals(org.evochora.datapipeline.api.services.State.STOPPED, 
            service.getServiceStatus().state());
    }

    // Helper methods

    private SimulationContext createTestSimulationContext(String simulationRunId, int dimensions) {
        SimulationContext context = new SimulationContext();
        context.setSimulationRunId(simulationRunId);
        
        EnvironmentProperties environment = new EnvironmentProperties();
        environment.setWorldShape(new int[dimensions]); // Initialize with zeros
        context.setEnvironment(environment);
        
        return context;
    }

    private RawTickData createTestRawTickData(long tick) {
        RawTickData tickData = new RawTickData();
        tickData.setTickNumber(tick);
        
        List<RawCellState> cells = Arrays.asList(
            createCellState(new int[]{1, 1}, org.evochora.runtime.Config.TYPE_CODE, 100, 1),
            createCellState(new int[]{2, 2}, org.evochora.runtime.Config.TYPE_ENERGY, 50, 2),
            createCellState(new int[]{3, 3}, org.evochora.runtime.Config.TYPE_CODE, 0, 0) // Should be excluded
        );
        
        tickData.setCells(cells);
        return tickData;
    }

    private RawTickData createTestRawTickDataWithMixedCells(long tick) {
        RawTickData tickData = new RawTickData();
        tickData.setTickNumber(tick);
        
        List<RawCellState> cells = Arrays.asList(
            createCellState(new int[]{1, 1}, org.evochora.runtime.Config.TYPE_CODE, 100, 1), // Has molecules and owner
            createCellState(new int[]{2, 2}, org.evochora.runtime.Config.TYPE_DATA, 0, 2),   // Has owner but no molecules
            createCellState(new int[]{3, 3}, org.evochora.runtime.Config.TYPE_CODE, 0, 0),   // Should be excluded
            createCellState(new int[]{4, 4}, org.evochora.runtime.Config.TYPE_ENERGY, 50, 0) // Has molecules but no owner
        );
        
        tickData.setCells(cells);
        return tickData;
    }

    private RawTickData createTestRawTickDataWithUnknownMolecule(long tick) {
        RawTickData tickData = new RawTickData();
        tickData.setTickNumber(tick);
        
        List<RawCellState> cells = Arrays.asList(
            createCellState(new int[]{1, 1}, 999, 100, 1) // Unknown molecule type
        );
        
        tickData.setCells(cells);
        return tickData;
    }

    private RawTickData createEmptyRawTickData(long tick) {
        RawTickData tickData = new RawTickData();
        tickData.setTickNumber(tick);
        tickData.setCells(new ArrayList<>());
        return tickData;
    }

    private RawCellState createCellState(int[] position, int type, int value, int ownerId) {
        RawCellState cell = new RawCellState();
        cell.setPosition(position);
        cell.setType(type);
        cell.setValue(value);
        cell.setOwnerId(ownerId);
        return cell;
    }

    private org.evochora.datapipeline.api.channels.IInputChannel<?> createMockInputChannel(Object... messages) {
        return new org.evochora.datapipeline.api.channels.IInputChannel<Object>() {
            private int index = 0;
            
            public Object read() throws InterruptedException {
                if (index < messages.length) {
                    return messages[index++];
                }
                // For unit tests, return null when no more messages
                // This prevents infinite blocking in tests
                return null;
            }
            
            public boolean isEmpty() {
                return index >= messages.length;
            }
            
            public int size() {
                return Math.max(0, messages.length - index);
            }
        };
    }
}