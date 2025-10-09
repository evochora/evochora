package org.evochora.datapipeline.services.indexers;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.database.IMetadataDatabase;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class MetadataIndexerTest {

    @Mock
    private IBatchStorageRead mockStorage;

    @Mock
    private IMetadataDatabase mockDatabase;

    private Map<String, List<IResource>> resources;
    private Config config;
    private MetadataIndexer indexer;
    private final String testRunId = "test-run-123";

    @BeforeEach
    void setUp() {
        resources = Map.of("storage", List.of(mockStorage), "database", List.of(mockDatabase));
        config = ConfigFactory.parseString("runId = \"" + testRunId + "\""); // Use post-mortem mode for simplicity
        indexer = new MetadataIndexer("test-indexer", config, resources);
    }

    @Test
    void indexRun_successfulPath_createsSchemaAndInsertsMetadata() throws Exception {
        SimulationMetadata metadata = SimulationMetadata.newBuilder().setSimulationRunId(testRunId).build();
        when(mockStorage.readMessage(anyString(), any())).thenReturn(metadata);

        indexer.indexRun(testRunId);

        verify(mockDatabase).createSimulationRun(testRunId);
        verify(mockDatabase).setSimulationRun(testRunId);
        verify(mockDatabase).insertMetadata(metadata);
        assertEquals(1, indexer.getMetrics().get("metadata_indexed"));
        assertEquals(0, indexer.getMetrics().get("metadata_failed"));
    }

    @Test
    void indexRun_whenPollingForFileTimesOut_throwsException() throws IOException {
        Config timeoutConfig = ConfigFactory.parseString("runId = \"" + testRunId + "\", metadataFileMaxPollDurationMs = 50");
        indexer = new MetadataIndexer("test-indexer", timeoutConfig, resources);

        when(mockStorage.readMessage(anyString(), any())).thenThrow(new IOException("File not found"));

        Exception exception = assertThrows(TimeoutException.class, () -> indexer.indexRun(testRunId));
        assertTrue(exception.getMessage().contains("Metadata file did not appear"));
        assertEquals(0, indexer.getMetrics().get("metadata_indexed"));
        assertEquals(1, indexer.getMetrics().get("metadata_failed"));
    }

    @Test
    void indexRun_whenDatabaseCallFails_propagatesException() throws IOException {
        SimulationMetadata metadata = SimulationMetadata.newBuilder().setSimulationRunId(testRunId).build();
        when(mockStorage.readMessage(anyString(), any())).thenReturn(metadata);
        doThrow(new RuntimeException("DB error")).when(mockDatabase).insertMetadata(any());

        assertThrows(RuntimeException.class, () -> indexer.indexRun(testRunId));

        verify(mockDatabase).createSimulationRun(testRunId);
        verify(mockDatabase).setSimulationRun(testRunId);
        assertEquals(0, indexer.getMetrics().get("metadata_indexed"));
    }
}