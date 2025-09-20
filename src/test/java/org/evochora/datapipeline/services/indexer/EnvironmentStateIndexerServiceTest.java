package org.evochora.datapipeline.services.indexer;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.RawTickData;
import org.evochora.datapipeline.api.contracts.RawCellState;
import org.evochora.datapipeline.storage.api.indexer.IEnvironmentStateWriter;
import org.evochora.datapipeline.storage.api.indexer.model.EnvironmentState;
import org.evochora.datapipeline.storage.api.indexer.model.Position;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
@DisplayName("EnvironmentStateIndexerService Unit Tests")
class EnvironmentStateIndexerServiceTest {

    @Mock
    private IEnvironmentStateWriter mockStorageWriter;

    private EnvironmentStateIndexerService service;
    private Config testConfig;

    @BeforeEach
    void setUp() {
        // Create test configuration
        testConfig = ConfigFactory.parseString("""
            batchSize: 3
            batchTimeoutMs: 100
            jdbcUrl: "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1"
            user: "sa"
            password: ""
            initializeSchema: true
            """);

        // Create service with mocked storage writer
        service = new EnvironmentStateIndexerService(testConfig) {
            @Override
            protected IEnvironmentStateWriter createStorageWriter(Config config) {
                return mockStorageWriter;
            }
        };
    }

    @Test
    @DisplayName("Should initialize storage writer with correct dimensions")
    void shouldInitializeStorageWriterWithCorrectDimensions() {
        // Given
        RawTickData tickData = createRawTickData(1);

        // When
        service.processRawTickData(tickData);

        // Then
        verify(mockStorageWriter).initialize(2);
    }

    @Test
    @DisplayName("Should extract environment states from raw tick data")
    void shouldExtractEnvironmentStatesFromRawTickData() {
        // Given
        RawTickData tickData = createRawTickData(1);
        service.processRawTickData(tickData); // Initialize first

        // When
        service.processRawTickData(tickData);

        // Then
        verify(mockStorageWriter, atLeastOnce()).writeEnvironmentStates(any());
    }

    @Test
    @DisplayName("Should batch environment states according to batch size")
    void shouldBatchEnvironmentStatesAccordingToBatchSize() throws InterruptedException {
        // Given
        RawTickData tickData = createRawTickData(1);
        service.processRawTickData(tickData); // Initialize first

        // When - process multiple tick data to fill batch
        for (int i = 0; i < 5; i++) {
            service.processRawTickData(tickData);
        }

        // Wait for batch processing
        Thread.sleep(200);

        // Then - should have called writeEnvironmentStates multiple times due to batch size of 3
        verify(mockStorageWriter, atLeast(2)).writeEnvironmentStates(any());
    }

    @Test
    @DisplayName("Should flush batch on timeout even if not full")
    void shouldFlushBatchOnTimeoutEvenIfNotFull() throws InterruptedException {
        // Given
        RawTickData tickData = createRawTickData(1);
        service.processRawTickData(tickData); // Initialize first

        // When - process one tick data and wait for timeout
        service.processRawTickData(tickData);
        Thread.sleep(150); // Wait longer than batchTimeoutMs (100ms)

        // Then - should have flushed due to timeout
        verify(mockStorageWriter, atLeastOnce()).writeEnvironmentStates(any());
    }

    @Test
    @DisplayName("Should only process cells with molecules or owners")
    void shouldOnlyProcessCellsWithMoleculesOrOwners() {
        // Given
        RawTickData tickData = createRawTickData(1);
        service.processRawTickData(tickData); // Initialize first

        // When
        service.processRawTickData(tickData);

        // Then
        verify(mockStorageWriter).writeEnvironmentStates(argThat(states -> {
            // Should have at least 2 states: one with molecule, one with owner
            return states.size() >= 2;
        }));
    }

    @Test
    @DisplayName("Should handle empty tick data gracefully")
    void shouldHandleEmptyTickDataGracefully() {
        // Given
        RawTickData tickData = createEmptyRawTickData();

        // When
        service.processRawTickData(tickData);

        // Then - should not call storage writer for empty tick data
        verify(mockStorageWriter, never()).writeEnvironmentStates(any());
    }

    @Test
    @DisplayName("Should handle null cells gracefully")
    void shouldHandleNullCellsGracefully() {
        // Given
        RawTickData tickData = createNullCellsRawTickData();

        // When
        service.processRawTickData(tickData);

        // Then - should not call storage writer for null cells
        verify(mockStorageWriter, never()).writeEnvironmentStates(any());
    }

    @Test
    @DisplayName("Should provide correct activity info")
    void shouldProvideCorrectActivityInfo() {
        // Given
        RawTickData tickData = createRawTickData(1);
        service.processRawTickData(tickData); // Initialize first

        // When
        service.processRawTickData(tickData);
        String activityInfo = service.getActivityInfo();

        // Then
        assertThat(activityInfo).contains("Batch:");
    }

    @Test
    @DisplayName("Should handle storage writer exceptions gracefully")
    void shouldHandleStorageWriterExceptionsGracefully() {
        // Given
        RawTickData tickData = createRawTickData(1);
        service.processRawTickData(tickData); // Initialize first
        
        doThrow(new RuntimeException("Storage error")).when(mockStorageWriter)
            .writeEnvironmentStates(any());

        // When/Then - should not throw exception
        assertDoesNotThrow(() -> service.processRawTickData(tickData));
    }

    @Test
    @DisplayName("Should flush remaining batch on shutdown")
    void shouldFlushRemainingBatchOnShutdown() throws InterruptedException {
        // Given
        RawTickData tickData = createRawTickData(1);
        service.processRawTickData(tickData); // Initialize first
        service.processRawTickData(tickData); // Add to batch

        // When
        service.stop();
        Thread.sleep(100); // Wait for shutdown

        // Then - should have flushed remaining batch
        verify(mockStorageWriter, atLeastOnce()).writeEnvironmentStates(any());
    }

    // Helper methods to create test data

    private RawTickData createRawTickData(int tick) {
        RawTickData tickData = new RawTickData();
        tickData.setTickNumber(tick);
        tickData.setSimulationRunId("test-run");
        
        List<RawCellState> cells = new ArrayList<>();
        
        // Add cell with molecule
        RawCellState cellWithMolecule = new RawCellState();
        cellWithMolecule.setPosition(new int[]{0, 0});
        cellWithMolecule.setType(1);
        cellWithMolecule.setValue(100);
        cellWithMolecule.setOwnerId(0);
        cells.add(cellWithMolecule);
        
        // Add cell with owner
        RawCellState cellWithOwner = new RawCellState();
        cellWithOwner.setPosition(new int[]{1, 1});
        cellWithOwner.setType(0);
        cellWithOwner.setValue(0);
        cellWithOwner.setOwnerId(123);
        cells.add(cellWithOwner);
        
        tickData.setCells(cells);
        return tickData;
    }

    private RawTickData createEmptyRawTickData() {
        RawTickData tickData = new RawTickData();
        tickData.setTickNumber(1L);
        tickData.setSimulationRunId("test-run");
        tickData.setCells(new ArrayList<>());
        return tickData;
    }

    private RawTickData createNullCellsRawTickData() {
        RawTickData tickData = new RawTickData();
        tickData.setTickNumber(1L);
        tickData.setSimulationRunId("test-run");
        tickData.setCells(null);
        return tickData;
    }
}
