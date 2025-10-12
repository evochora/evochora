package org.evochora.datapipeline.services.indexers;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.OperationalError;
import org.evochora.datapipeline.api.resources.database.IMetadataWriter;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.services.IService;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
@ExtendWith(LogWatchExtension.class)
class MetadataIndexerTest {

    @Mock
    private IBatchStorageRead mockStorage;

    @Mock
    private IMetadataWriter mockDatabase;

    private Map<String, List<IResource>> resources;
    private final String testRunId = "test-run-123";

    @BeforeEach
    void setUp() {
        resources = Map.of("storage", List.of(mockStorage), "database", List.of(mockDatabase));
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*") // Allow info logs for success case
    void successfulPath_processesMessageAndStops() throws Exception {
        // Arrange
        Config config = ConfigFactory.parseString("runId = \"" + testRunId + "\"");
        MetadataIndexer indexer = new MetadataIndexer("test-indexer", config, resources);
        SimulationMetadata metadata = SimulationMetadata.newBuilder().setSimulationRunId(testRunId).build();
        when(mockStorage.readMessage(eq(testRunId + "/metadata.pb"), any())).thenReturn(metadata);

        // Act
        indexer.start();

        // Assert
        await().atMost(5, TimeUnit.SECONDS).until(() -> indexer.getCurrentState() == IService.State.STOPPED);

        // Schema creation now handled transparently by AbstractDatabaseWrapper.setSimulationRun()
        verify(mockDatabase).setSimulationRun(testRunId);
        verify(mockDatabase).insertMetadata(metadata);
        assertEquals(1L, indexer.getMetrics().get("metadata_indexed"));
        assertEquals(0L, indexer.getMetrics().get("metadata_failed"));
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*")
    @ExpectLog(level = LogLevel.ERROR, messagePattern = ".*Indexing timeout for run:.*")
    void pollingTimeout_entersErrorState() throws Exception {
        // Arrange
        Config config = ConfigFactory.parseString("runId = \"" + testRunId + "\", metadataFileMaxPollDurationMs = 50");
        MetadataIndexer indexer = new MetadataIndexer("test-indexer", config, resources);
        when(mockStorage.readMessage(anyString(), any())).thenThrow(new IOException("File not found"));

        // Act
        indexer.start();

        // Assert
        await().atMost(5, TimeUnit.SECONDS).until(() -> indexer.getCurrentState() == IService.State.ERROR);

        assertEquals(0L, indexer.getMetrics().get("metadata_indexed"));
        assertEquals(1L, indexer.getMetrics().get("metadata_failed"));
        verify(mockDatabase, never()).insertMetadata(any());
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*")
    @ExpectLog(level = LogLevel.ERROR, messagePattern = ".*Indexing timeout for run:.*")
    void errorTracking_recordsErrorsOnTimeout() throws Exception {
        // Setup: Storage that always throws IOException (simulates file not found)
        when(mockStorage.readMessage(anyString(), any())).thenThrow(new IOException("File not found"));
        
        Config indexerConfig = ConfigFactory.parseString("""
                runId = "test-run-123"
                metadataFileMaxPollDurationMs = 100
                """);
        
        MetadataIndexer indexer = new MetadataIndexer("test-indexer", indexerConfig, resources);
        
        // Start indexer - should timeout and enter ERROR state
        indexer.start();
        await().atMost(5, TimeUnit.SECONDS).until(() -> indexer.getCurrentState() == IService.State.ERROR);
        
        // Verify service is in ERROR state
        assertEquals(IService.State.ERROR, indexer.getCurrentState());
        
        // Verify metrics (fatal errors increment failed counter)
        assertEquals(0L, indexer.getMetrics().get("metadata_indexed"));
        assertEquals(1L, indexer.getMetrics().get("metadata_failed"));
        
        // Fatal errors do NOT use recordError() - error collection should be empty
        assertTrue(indexer.getErrors().isEmpty(), "Fatal errors should not be in error collection");
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*")
    @ExpectLog(level = LogLevel.ERROR, messagePattern = ".*Indexing failed.*")
    void errorTracking_recordsErrorsOnDatabaseFailure() throws Exception {
        // Setup: Storage returns metadata, but database throws exception on insertMetadata
        Config config = ConfigFactory.parseString("runId = \"" + testRunId + "\"");
        MetadataIndexer indexer = new MetadataIndexer("test-indexer", config, resources);
        SimulationMetadata metadata = SimulationMetadata.newBuilder()
                .setSimulationRunId(testRunId)
                .setSamplingInterval(1)
                .build();
        when(mockStorage.readMessage(eq(testRunId + "/metadata.pb"), any())).thenReturn(metadata);
        doThrow(new RuntimeException("Database write failed"))
                .when(mockDatabase).insertMetadata(any());
        
        // Start indexer - should fail on insertMetadata
        indexer.start();
        await().atMost(5, TimeUnit.SECONDS).until(() -> indexer.getCurrentState() == IService.State.ERROR);
        
        // Verify service is in ERROR state
        assertEquals(IService.State.ERROR, indexer.getCurrentState());
        
        // Verify metrics (fatal errors increment failed counter)
        assertEquals(0L, indexer.getMetrics().get("metadata_indexed"));
        assertEquals(1L, indexer.getMetrics().get("metadata_failed"));
        
        // Fatal errors do NOT use recordError() - error collection should be empty
        assertTrue(indexer.getErrors().isEmpty(), "Fatal errors should not be in error collection");
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*")
    @ExpectLog(level = LogLevel.ERROR, messagePattern = ".*Indexing failed.*")
    void databaseFailure_entersErrorState() throws Exception {
        // Arrange
        Config config = ConfigFactory.parseString("runId = \"" + testRunId + "\"");
        MetadataIndexer indexer = new MetadataIndexer("test-indexer", config, resources);
        SimulationMetadata metadata = SimulationMetadata.newBuilder().setSimulationRunId(testRunId).build();
        when(mockStorage.readMessage(eq(testRunId + "/metadata.pb"), any())).thenReturn(metadata);
        doThrow(new RuntimeException("DB error")).when(mockDatabase).insertMetadata(any(SimulationMetadata.class));

        // Act
        indexer.start();

        // Assert
        await().atMost(5, TimeUnit.SECONDS).until(() -> indexer.getCurrentState() == IService.State.ERROR);

        assertEquals(0L, indexer.getMetrics().get("metadata_indexed"));
        assertEquals(1L, indexer.getMetrics().get("metadata_failed"));
        // Schema creation now handled transparently by AbstractDatabaseWrapper.setSimulationRun()
        verify(mockDatabase).setSimulationRun(testRunId);
    }
}