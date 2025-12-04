package org.evochora.datapipeline.services.indexers;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.evochora.datapipeline.api.contracts.MetadataInfo;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.database.IResourceSchemaAwareMetadataWriter;
import org.evochora.datapipeline.api.resources.storage.IResourceBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.api.resources.topics.IResourceTopicReader;
import org.evochora.datapipeline.api.resources.topics.TopicMessage;
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

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
@ExtendWith(LogWatchExtension.class)
class MetadataIndexerTest {

    private IResourceBatchStorageRead mockStorage;
    private IResourceSchemaAwareMetadataWriter mockDatabase;
    private IResourceTopicReader<MetadataInfo, Object> mockTopic;

    @Mock
    private TopicMessage<MetadataInfo, Object> mockMessage;

    private Map<String, List<IResource>> resources;
    private final String testRunId = "test-run-123";

    @BeforeEach
    void setUp() {
        // Create mocks that implement both capability interfaces AND IResource
        // This simulates production where wrappers implement IResource via AbstractResource
        mockStorage = mock(IResourceBatchStorageRead.class);
        mockDatabase = mock(IResourceSchemaAwareMetadataWriter.class);
        @SuppressWarnings("unchecked")
        IResourceTopicReader<MetadataInfo, Object> topicMock = mock(IResourceTopicReader.class);
        mockTopic = topicMock;

        resources = Map.of(
            "storage", List.of((IResource) mockStorage),
            "database", List.of((IResource) mockDatabase),
            "topic", List.of((IResource) mockTopic)
        );
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*") // Allow info logs for success case
    void successfulPath_processesMessageAndStops() throws Exception {
        // Arrange
        Config config = ConfigFactory.parseString("runId = \"" + testRunId + "\"");
        MetadataIndexer<?> indexer = new MetadataIndexer<>("test-indexer", config, resources);
        
        // Mock topic message with MetadataInfo
        MetadataInfo info = MetadataInfo.newBuilder()
            .setSimulationRunId(testRunId)
            .setStoragePath(testRunId + "/raw/metadata.pb")
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
        when(mockMessage.payload()).thenReturn(info);
        when(mockTopic.poll(anyLong(), any(TimeUnit.class))).thenReturn(mockMessage);
        
        // Mock storage to return metadata
        SimulationMetadata metadata = SimulationMetadata.newBuilder().setSimulationRunId(testRunId).build();
        when(mockStorage.readMessage(any(StoragePath.class), any())).thenReturn(metadata);

        // Act
        indexer.start();

        // Assert
        await().atMost(5, TimeUnit.SECONDS).until(() -> indexer.getCurrentState() == IService.State.STOPPED);

        // Verify topic operations
        verify(mockTopic).setSimulationRun(testRunId);
        verify(mockTopic).poll(eq(30000L), eq(TimeUnit.MILLISECONDS));
        verify(mockTopic).ack(mockMessage);
        
        // Verify database operations
        verify(mockDatabase).setSimulationRun(testRunId);
        verify(mockDatabase).insertMetadata(metadata);
        
        // Verify metrics
        assertEquals(1L, indexer.getMetrics().get("metadata_indexed"));
        assertEquals(0L, indexer.getMetrics().get("metadata_failed"));
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*")
    @ExpectLog(level = LogLevel.ERROR, messagePattern = ".*Indexing failed.*")
    void databaseFailure_entersErrorState() throws Exception {
        // Arrange
        Config config = ConfigFactory.parseString("runId = \"" + testRunId + "\"");
        MetadataIndexer<?> indexer = new MetadataIndexer<>("test-indexer", config, resources);
        
        // Mock topic message with MetadataInfo
        MetadataInfo info = MetadataInfo.newBuilder()
            .setSimulationRunId(testRunId)
            .setStoragePath(testRunId + "/raw/metadata.pb")
            .setWrittenAtMs(System.currentTimeMillis())
            .build();
        when(mockMessage.payload()).thenReturn(info);
        when(mockTopic.poll(anyLong(), any(TimeUnit.class))).thenReturn(mockMessage);
        
        // Mock storage to return metadata
        SimulationMetadata metadata = SimulationMetadata.newBuilder().setSimulationRunId(testRunId).build();
        when(mockStorage.readMessage(any(StoragePath.class), any())).thenReturn(metadata);
        
        // Mock database to throw error on insert
        doThrow(new RuntimeException("DB error")).when(mockDatabase).insertMetadata(any(SimulationMetadata.class));

        // Act
        indexer.start();

        // Assert
        await().atMost(5, TimeUnit.SECONDS).until(() -> indexer.getCurrentState() == IService.State.ERROR);

        // Verify topic operations (ack should not be called on failure)
        verify(mockTopic).setSimulationRun(testRunId);
        verify(mockTopic).poll(eq(30000L), eq(TimeUnit.MILLISECONDS));
        verify(mockTopic, never()).ack(any());
        
        // Verify database operations
        verify(mockDatabase).setSimulationRun(testRunId);
        verify(mockDatabase).insertMetadata(metadata);
        
        // Verify metrics
        assertEquals(0L, indexer.getMetrics().get("metadata_indexed"));
        assertEquals(1L, indexer.getMetrics().get("metadata_failed"));
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*")
    @ExpectLog(level = LogLevel.ERROR, messagePattern = ".*Metadata notification did not arrive.*")
    void topicPollTimeout_entersErrorState() throws Exception {
        // Arrange
        Config config = ConfigFactory.parseString("runId = \"" + testRunId + "\", topicPollTimeoutMs = 100");
        MetadataIndexer<?> indexer = new MetadataIndexer<>("test-indexer", config, resources);
        
        // Mock topic.poll() to return null (timeout)
        when(mockTopic.poll(anyLong(), any(TimeUnit.class))).thenReturn(null);

        // Act
        indexer.start();

        // Assert - wait only slightly longer than the configured timeout
        await().atMost(500, TimeUnit.MILLISECONDS).until(() -> indexer.getCurrentState() == IService.State.ERROR);

        // Verify topic was polled with correct timeout
        verify(mockTopic).poll(eq(100L), eq(TimeUnit.MILLISECONDS));
        
        // Verify no storage or database operations (failed before receiving message)
        verify(mockStorage, never()).readMessage(any(StoragePath.class), any());
        verify(mockDatabase, never()).insertMetadata(any());
        
        // Verify metrics
        assertEquals(0L, indexer.getMetrics().get("metadata_indexed"));
        assertEquals(1L, indexer.getMetrics().get("metadata_failed"));
    }

    @Test
    @AllowLog(level = LogLevel.INFO, loggerPattern = ".*")
    @ExpectLog(level = LogLevel.ERROR, messagePattern = ".*Metadata notification did not arrive.*")
    void errorTracking_recordsErrorsOnTimeout() throws Exception {
        // Arrange: Topic poll returns null (timeout scenario)
        when(mockTopic.poll(anyLong(), any(TimeUnit.class))).thenReturn(null);
        
        Config indexerConfig = ConfigFactory.parseString("""
                runId = "test-run-123"
                topicPollTimeoutMs = 50
                """);
        
        MetadataIndexer<?> indexer = new MetadataIndexer<>("test-indexer", indexerConfig, resources);
        
        // Act: Start indexer - should timeout and enter ERROR state
        indexer.start();
        await().atMost(300, TimeUnit.MILLISECONDS).until(() -> indexer.getCurrentState() == IService.State.ERROR);
        
        // Assert: Verify service is in ERROR state
        assertEquals(IService.State.ERROR, indexer.getCurrentState());
        
        // Verify metrics (fatal errors increment failed counter)
        assertEquals(0L, indexer.getMetrics().get("metadata_indexed"));
        assertEquals(1L, indexer.getMetrics().get("metadata_failed"));
        
        // Fatal errors do NOT use recordError() - error collection should be empty
        assertTrue(indexer.getErrors().isEmpty(), "Fatal errors should not be in error collection");
    }
}