package org.evochora.datapipeline.services.indexers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.evochora.datapipeline.TestMetadataHelper;
import org.evochora.datapipeline.api.contracts.BatchInfo;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.database.IResourceSchemaAwareEnvironmentDataWriter;
import org.evochora.datapipeline.api.resources.database.IResourceSchemaAwareMetadataReader;
import org.evochora.datapipeline.api.resources.storage.CheckedConsumer;
import org.evochora.datapipeline.api.resources.storage.IResourceBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.RawChunk;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.api.resources.topics.IResourceTopicReader;
import org.evochora.datapipeline.api.resources.topics.TopicMessage;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Unit tests for EnvironmentIndexer using mocked dependencies.
 * <p>
 * Tests streaming configuration, raw-byte delegation, and metadata extraction.
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class EnvironmentIndexerTest {

    private IResourceSchemaAwareEnvironmentDataWriter mockDatabase;
    private IResourceBatchStorageRead mockStorage;
    private IResourceTopicReader<BatchInfo, Object> mockTopic;
    private IResourceSchemaAwareMetadataReader mockMetadata;
    private Config config;
    private Map<String, List<IResource>> resources;

    @BeforeEach
    void setUp() {
        mockDatabase = mock(IResourceSchemaAwareEnvironmentDataWriter.class);
        mockStorage = mock(IResourceBatchStorageRead.class);
        @SuppressWarnings("unchecked")
        IResourceTopicReader<BatchInfo, Object> topicMock = mock(IResourceTopicReader.class);
        mockTopic = topicMock;
        mockMetadata = mock(IResourceSchemaAwareMetadataReader.class);

        config = ConfigFactory.parseString("""
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            topicPollTimeoutMs = 1000
            insertBatchSize = 100
            flushTimeoutMs = 1000
            """);

        resources = Map.of(
            "database", List.of((IResource) mockDatabase),
            "storage", List.of((IResource) mockStorage),
            "topic", List.of((IResource) mockTopic),
            "metadata", List.of((IResource) mockMetadata)
        );
    }

    // ==================== Constructor & Configuration ====================

    @Test
    void testConstructor_GetsDatabaseResource() {
        EnvironmentIndexer<?> indexer = new EnvironmentIndexer<>("test-indexer", config, resources);
        assertThat(indexer).isNotNull();
    }

    @Test
    void testUseStreamingProcessing_ReturnsTrue() throws Exception {
        EnvironmentIndexer<?> indexer = new EnvironmentIndexer<>("test-indexer", config, resources);

        Method method = AbstractBatchIndexer.class.getDeclaredMethod("useStreamingProcessing");
        method.setAccessible(true);
        assertThat((boolean) method.invoke(indexer)).isTrue();
    }

    @Test
    void testGetRequiredComponents_ExcludesBuffering() throws Exception {
        EnvironmentIndexer<?> indexer = new EnvironmentIndexer<>("test-indexer", config, resources);

        Method method = AbstractBatchIndexer.class.getDeclaredMethod("getRequiredComponents");
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<AbstractBatchIndexer.ComponentType> components =
            (Set<AbstractBatchIndexer.ComponentType>) method.invoke(indexer);

        assertThat(components).containsExactly(AbstractBatchIndexer.ComponentType.METADATA);
        assertThat(components).doesNotContain(AbstractBatchIndexer.ComponentType.BUFFERING);
    }

    @Test
    void testGetMetadata_NotLoadedYet() {
        EnvironmentIndexer<?> indexer = new EnvironmentIndexer<>("test-indexer", config, resources);

        assertThatThrownBy(() -> indexer.getMetadata())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Metadata not loaded");
    }

    @Test
    void testFlushChunks_ThrowsUnsupportedOperationException() {
        EnvironmentIndexer<?> indexer = new EnvironmentIndexer<>("test-indexer", config, resources);

        assertThatThrownBy(() -> indexer.flushChunks(List.of()))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    // ==================== Streaming Raw-Byte Delegation ====================

    @Test
    void readAndProcessChunks_noChunks_doesNotCallDatabase() throws Exception {
        EnvironmentIndexer<?> indexer = new EnvironmentIndexer<>("test-indexer", config, resources);
        registerBatchInTracker(indexer, "batch-1");

        // Storage yields no chunks (consumer never called)
        doAnswer(invocation -> null).when(mockStorage).forEachRawChunk(any(), any());

        invokeReadAndProcessChunks(indexer, "test/batch.pb", "batch-1");

        verify(mockStorage).forEachRawChunk(any(), any());
        verifyNoInteractions(mockDatabase);
    }

    @Test
    void readAndProcessChunks_singleChunk_callsWriteRawChunk() throws Exception {
        EnvironmentIndexer<?> indexer = new EnvironmentIndexer<>("test-indexer", config, resources);
        registerBatchInTracker(indexer, "batch-1");

        byte[] chunkData = new byte[]{10, 20, 30};
        doAnswer(invocation -> {
            CheckedConsumer<RawChunk> consumer = invocation.getArgument(1);
            consumer.accept(new RawChunk(0, 99, 100, chunkData));
            return null;
        }).when(mockStorage).forEachRawChunk(any(), any());

        invokeReadAndProcessChunks(indexer, "test/batch.pb", "batch-1");

        verify(mockDatabase).writeRawChunk(0, 99, 100, chunkData);
    }

    @Test
    void readAndProcessChunks_multipleChunks_callsWriteRawChunkForEach() throws Exception {
        EnvironmentIndexer<?> indexer = new EnvironmentIndexer<>("test-indexer", config, resources);
        registerBatchInTracker(indexer, "batch-1");

        byte[] data1 = new byte[]{1};
        byte[] data2 = new byte[]{2};
        byte[] data3 = new byte[]{3};

        doAnswer(invocation -> {
            CheckedConsumer<RawChunk> consumer = invocation.getArgument(1);
            consumer.accept(new RawChunk(0, 99, 100, data1));
            consumer.accept(new RawChunk(100, 199, 100, data2));
            consumer.accept(new RawChunk(200, 299, 100, data3));
            return null;
        }).when(mockStorage).forEachRawChunk(any(), any());

        invokeReadAndProcessChunks(indexer, "test/batch.pb", "batch-1");

        verify(mockDatabase, times(3)).writeRawChunk(
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyInt(),
            any(byte[].class));
        verify(mockDatabase).writeRawChunk(0, 99, 100, data1);
        verify(mockDatabase).writeRawChunk(100, 199, 100, data2);
        verify(mockDatabase).writeRawChunk(200, 299, 100, data3);
    }

    @Test
    void commitProcessedChunks_delegatesToCommitRawChunks() throws Exception {
        EnvironmentIndexer<?> indexer = new EnvironmentIndexer<>("test-indexer", config, resources);

        Method method = AbstractBatchIndexer.class.getDeclaredMethod("commitProcessedChunks");
        method.setAccessible(true);
        method.invoke(indexer);

        verify(mockDatabase).commitRawChunks();
    }

    // ==================== Environment Properties Extraction ====================

    @Test
    void testExtractEnvironmentProperties_ToroidalTopology() throws Exception {
        SimulationMetadata metadata = SimulationMetadata.newBuilder()
            .setSimulationRunId("test-run")
            .setResolvedConfigJson(TestMetadataHelper.builder()
                .shape(100, 100)
                .toroidal(true)
                .build())
            .build();

        EnvironmentIndexer<?> indexer = new EnvironmentIndexer<>("test-indexer", config, resources);
        java.lang.reflect.Method extractMethod = EnvironmentIndexer.class.getDeclaredMethod(
            "extractEnvironmentProperties", SimulationMetadata.class);
        extractMethod.setAccessible(true);
        EnvironmentProperties props = (EnvironmentProperties) extractMethod.invoke(indexer, metadata);

        assertThat(props.getWorldShape()).containsExactly(100, 100);
        assertThat(props.isToroidal()).isTrue();
    }

    @Test
    void testExtractEnvironmentProperties_EuclideanTopology() throws Exception {
        String customJson = """
            {
                "environment": {
                    "shape": [10, 10, 10],
                    "topology": "BOUNDED"
                },
                "samplingInterval": 1,
                "accumulatedDeltaInterval": 100,
                "snapshotInterval": 10,
                "chunkInterval": 1,
                "plugins": [],
                "organisms": [],
                "runtime": {
                    "organism": {
                        "max-energy": 32767,
                        "max-entropy": 8191,
                        "error-penalty-cost": 10
                    },
                    "thermodynamics": {
                        "default": {
                            "className": "org.evochora.runtime.thermodynamics.impl.UniversalThermodynamicPolicy",
                            "options": {
                                "base-energy": 1,
                                "base-entropy": 1
                            }
                        },
                        "overrides": {
                            "instructions": {},
                            "families": {}
                        }
                    }
                }
            }
            """;
        SimulationMetadata metadata = SimulationMetadata.newBuilder()
            .setSimulationRunId("test-run")
            .setResolvedConfigJson(customJson)
            .build();

        EnvironmentIndexer<Object> indexer = new EnvironmentIndexer<>("test-indexer", config, resources);
        java.lang.reflect.Method extractMethod = EnvironmentIndexer.class.getDeclaredMethod(
            "extractEnvironmentProperties", SimulationMetadata.class);
        extractMethod.setAccessible(true);
        EnvironmentProperties props = (EnvironmentProperties) extractMethod.invoke(indexer, metadata);

        assertThat(props.getWorldShape()).containsExactly(10, 10, 10);
        assertThat(props.isToroidal()).isFalse();
    }

    @Test
    void testExtractEnvironmentProperties_1D() throws Exception {
        String customJson = """
            {
                "environment": {
                    "shape": [1000],
                    "topology": "TORUS"
                },
                "samplingInterval": 1,
                "accumulatedDeltaInterval": 100,
                "snapshotInterval": 10,
                "chunkInterval": 1,
                "plugins": [],
                "organisms": [],
                "runtime": {
                    "organism": {
                        "max-energy": 32767,
                        "max-entropy": 8191,
                        "error-penalty-cost": 10
                    },
                    "thermodynamics": {
                        "default": {
                            "className": "org.evochora.runtime.thermodynamics.impl.UniversalThermodynamicPolicy",
                            "options": {
                                "base-energy": 1,
                                "base-entropy": 1
                            }
                        },
                        "overrides": {
                            "instructions": {},
                            "families": {}
                        }
                    }
                }
            }
            """;
        SimulationMetadata metadata = SimulationMetadata.newBuilder()
            .setSimulationRunId("test-run")
            .setResolvedConfigJson(customJson)
            .build();

        EnvironmentIndexer<Object> indexer = new EnvironmentIndexer<>("test-indexer", config, resources);
        java.lang.reflect.Method extractMethod = EnvironmentIndexer.class.getDeclaredMethod(
            "extractEnvironmentProperties", SimulationMetadata.class);
        extractMethod.setAccessible(true);
        EnvironmentProperties props = (EnvironmentProperties) extractMethod.invoke(indexer, metadata);

        assertThat(props.getWorldShape()).containsExactly(1000);
        assertThat(props.isToroidal()).isTrue();
    }

    // ==================== Helper Methods ====================

    /**
     * Registers a batch in the streaming tracker via reflection.
     * Required because {@code onChunkStreamed} (called by {@code readAndProcessChunks})
     * expects the batch to be registered.
     */
    private void registerBatchInTracker(EnvironmentIndexer<?> indexer, String batchId) throws Exception {
        Field trackerField = AbstractBatchIndexer.class.getDeclaredField("streamingTracker");
        trackerField.setAccessible(true);
        Object tracker = trackerField.get(indexer);

        Method registerMethod = tracker.getClass().getDeclaredMethod("registerBatch", String.class, TopicMessage.class);
        registerMethod.setAccessible(true);
        registerMethod.invoke(tracker, batchId, mock(TopicMessage.class));
    }

    /**
     * Invokes the protected {@code readAndProcessChunks} method via reflection.
     */
    private void invokeReadAndProcessChunks(EnvironmentIndexer<?> indexer,
                                            String path, String batchId) throws Exception {
        Method method = AbstractBatchIndexer.class.getDeclaredMethod(
            "readAndProcessChunks", StoragePath.class, String.class);
        method.setAccessible(true);
        try {
            method.invoke(indexer, StoragePath.of(path), batchId);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof Exception) {
                throw (Exception) e.getCause();
            }
            throw e;
        }
    }
}
