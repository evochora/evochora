package org.evochora.datapipeline.services.indexers;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.database.IMetadataDatabase;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
@ExtendWith(LogWatchExtension.class)
class MetadataIndexerTest {

    @Mock
    private IBatchStorageRead mockStorage;

    @Mock
    private IMetadataDatabase mockDatabase;

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

        verify(mockDatabase).createSimulationRun(testRunId);
        verify(mockDatabase).setSimulationRun(testRunId);
        verify(mockDatabase).insertMetadata(metadata);
        assertEquals(1L, indexer.getMetrics().get("metadata_indexed"));
        assertEquals(0L, indexer.getMetrics().get("metadata_failed"));
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*")
    @ExpectLog(level = LogLevel.ERROR, messagePattern = ".*Failed to discover run.*")
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
        verify(mockDatabase).createSimulationRun(testRunId);
        verify(mockDatabase).setSimulationRun(testRunId);
    }
}