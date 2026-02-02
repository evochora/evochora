package org.evochora.datapipeline.services.indexers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.CellStateTestHelper;
import org.evochora.datapipeline.TestMetadataHelper;
import org.evochora.datapipeline.api.contracts.BatchInfo;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.database.IResourceSchemaAwareEnvironmentDataWriter;
import org.evochora.datapipeline.api.resources.database.IResourceSchemaAwareMetadataReader;
import org.evochora.datapipeline.api.resources.storage.IResourceBatchStorageRead;
import org.evochora.datapipeline.api.resources.topics.IResourceTopicReader;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Unit tests for EnvironmentIndexer using mocked dependencies.
 * <p>
 * Tests individual methods (prepareTables, flushChunks, extractEnvironmentProperties) in isolation.
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
        // Create mocks that implement both capability interfaces AND IResource
        // This simulates production where wrappers implement IResource via AbstractResource
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
    
    @Test
    void testConstructor_GetsDatabaseResource() {
        // When: Create indexer
        EnvironmentIndexer<?> indexer = new EnvironmentIndexer<>("test-indexer", config, resources);
        
        // Then: Should succeed (no exception)
        assertThat(indexer).isNotNull();
    }
    
    @Test
    void testGetMetadata_NotLoadedYet() {
        // Given: Indexer with metadata component but metadata not yet loaded
        EnvironmentIndexer<?> indexer = new EnvironmentIndexer<>("test-indexer", config, resources);
        
        // When/Then: Calling getMetadata before loadMetadata should throw IllegalStateException
        assertThatThrownBy(() -> indexer.getMetadata())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Metadata not loaded");
    }
    
    @Test
    void testFlushChunks_EmptyList() throws Exception {
        // Given: Indexer with empty chunk list
        EnvironmentIndexer<?> indexer = new EnvironmentIndexer<>("test-indexer", config, resources);
        
        // When: Flush empty list
        indexer.flushChunks(List.of());
        
        // Then: Should not call database
        verifyNoInteractions(mockDatabase);
    }
    
    @Test
    void testFlushChunks_CallsDatabase() throws Exception {
        // Given: Indexer with chunks
        EnvironmentIndexer<?> indexer = new EnvironmentIndexer<>("test-indexer", config, resources);
        
        // Set envProps manually (normally set by prepareTables)
        java.lang.reflect.Field envPropsField = EnvironmentIndexer.class.getDeclaredField("envProps");
        envPropsField.setAccessible(true);
        envPropsField.set(indexer, new EnvironmentProperties(new int[]{10, 10}, false));
        
        TickData snapshot = TickData.newBuilder()
            .setTickNumber(1L)
            .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                CellStateTestHelper.createCellStateBuilder(0, 100, 1, 50, 0).build()
            )))
            .build();
        
        TickDataChunk chunk = TickDataChunk.newBuilder()
            .setSimulationRunId("test-run")
            .setFirstTick(1L)
            .setLastTick(1L)
            .setSnapshot(snapshot)
            .build();
        
        // When: Flush chunks
        indexer.flushChunks(List.of(chunk));
        
        // Then: Should call database.writeEnvironmentChunks with chunks
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TickDataChunk>> chunksCaptor = (ArgumentCaptor<List<TickDataChunk>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(mockDatabase).writeEnvironmentChunks(chunksCaptor.capture());
        
        assertThat(chunksCaptor.getValue()).hasSize(1);
        assertThat(chunksCaptor.getValue().get(0).getSnapshot().getTickNumber()).isEqualTo(1L);
    }
    
    @Test
    void testExtractEnvironmentProperties_ToroidalTopology() throws Exception {
        // Given: Metadata with toroidal topology
        SimulationMetadata metadata = SimulationMetadata.newBuilder()
            .setSimulationRunId("test-run")
            .setResolvedConfigJson(TestMetadataHelper.builder()
                .shape(100, 100)
                .toroidal(true)
                .build())
            .build();
        
        // When: Extract environment properties (via reflection to test private method)
        EnvironmentIndexer<?> indexer = new EnvironmentIndexer<>("test-indexer", config, resources);
        java.lang.reflect.Method extractMethod = EnvironmentIndexer.class.getDeclaredMethod(
            "extractEnvironmentProperties", SimulationMetadata.class);
        extractMethod.setAccessible(true);
        EnvironmentProperties props = (EnvironmentProperties) extractMethod.invoke(indexer, metadata);
        
        // Then: Should extract correct values
        assertThat(props.getWorldShape()).containsExactly(100, 100);
        assertThat(props.isToroidal()).isTrue();
    }
    
    @Test
    void testExtractEnvironmentProperties_EuclideanTopology() throws Exception {
        // Given: Metadata with euclidean (non-toroidal) topology - using 3D custom JSON
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
        
        // When: Extract environment properties
        EnvironmentIndexer<Object> indexer = new EnvironmentIndexer<>("test-indexer", config, resources);
        java.lang.reflect.Method extractMethod = EnvironmentIndexer.class.getDeclaredMethod(
            "extractEnvironmentProperties", SimulationMetadata.class);
        extractMethod.setAccessible(true);
        EnvironmentProperties props = (EnvironmentProperties) extractMethod.invoke(indexer, metadata);
        
        // Then: Should extract correct values
        assertThat(props.getWorldShape()).containsExactly(10, 10, 10);
        assertThat(props.isToroidal()).isFalse();
    }
    
    @Test
    void testExtractEnvironmentProperties_1D() throws Exception {
        // Given: Metadata with 1D environment - using custom JSON for 1D
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
        
        // When: Extract environment properties
        EnvironmentIndexer<Object> indexer = new EnvironmentIndexer<>("test-indexer", config, resources);
        java.lang.reflect.Method extractMethod = EnvironmentIndexer.class.getDeclaredMethod(
            "extractEnvironmentProperties", SimulationMetadata.class);
        extractMethod.setAccessible(true);
        EnvironmentProperties props = (EnvironmentProperties) extractMethod.invoke(indexer, metadata);
        
        // Then: Should extract correct values
        assertThat(props.getWorldShape()).containsExactly(1000);
        assertThat(props.isToroidal()).isTrue();
    }
    
    @Test
    void testFlushChunks_MultipleChunks() throws Exception {
        // Given: Indexer with multiple ticks
        EnvironmentIndexer<?> indexer = new EnvironmentIndexer<>("test-indexer", config, resources);
        
        // Set envProps manually (normally set by prepareSchema)
        java.lang.reflect.Field envPropsField = EnvironmentIndexer.class.getDeclaredField("envProps");
        envPropsField.setAccessible(true);
        envPropsField.set(indexer, new EnvironmentProperties(new int[]{10, 10}, false));
        
        // Create 3 ticks with different cell counts
        TickData tick1 = TickData.newBuilder()
            .setTickNumber(1L)
            .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                CellStateTestHelper.createCellStateBuilder(0, 100, 1, 10, 0).build(),
                CellStateTestHelper.createCellStateBuilder(1, 101, 2, 20, 0).build()
            )))
            .build();
        
        TickData tick2 = TickData.newBuilder()
            .setTickNumber(2L)
            .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                CellStateTestHelper.createCellStateBuilder(2, 102, 1, 30, 0).build()
            )))
            .build();
        
        TickData tick3 = TickData.newBuilder()
            .setTickNumber(3L)
            .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                CellStateTestHelper.createCellStateBuilder(3, 103, 3, 40, 0).build(),
                CellStateTestHelper.createCellStateBuilder(4, 104, 1, 50, 0).build(),
                CellStateTestHelper.createCellStateBuilder(5, 105, 2, 60, 0).build()
            )))
            .build();
        
        // When: Flush all ticks in one call (wrapped as chunks)
        indexer.flushChunks(wrapAsChunks(tick1, tick2, tick3));
        
        // Then: Should call database.writeEnvironmentChunks ONCE with all 3 chunks
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TickDataChunk>> chunksCaptor = (ArgumentCaptor<List<TickDataChunk>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(mockDatabase, times(1)).writeEnvironmentChunks(chunksCaptor.capture());
        
        // Verify all chunks passed in one call
        assertThat(chunksCaptor.getValue()).hasSize(3);
        assertThat(chunksCaptor.getValue().get(0).getSnapshot().getTickNumber()).isEqualTo(1L);
        assertThat(chunksCaptor.getValue().get(1).getSnapshot().getTickNumber()).isEqualTo(2L);
        assertThat(chunksCaptor.getValue().get(2).getSnapshot().getTickNumber()).isEqualTo(3L);
    }
    
    @Test
    void testFlushChunks_EmptySnapshot() throws Exception {
        // Given: Indexer with chunk containing snapshot with NO cells
        EnvironmentIndexer<?> indexer = new EnvironmentIndexer<>("test-indexer", config, resources);
        
        // Set envProps manually
        java.lang.reflect.Field envPropsField = EnvironmentIndexer.class.getDeclaredField("envProps");
        envPropsField.setAccessible(true);
        envPropsField.set(indexer, new EnvironmentProperties(new int[]{10, 10}, false));
        
        // Create tick with NO cells
        TickData emptyTick = TickData.newBuilder()
            .setTickNumber(1L)
            .build();  // No cells added
        
        // When: Flush chunk with empty snapshot
        indexer.flushChunks(wrapAsChunks(emptyTick));
        
        // Then: Should still call database (database handles empty efficiently via filtering)
        verify(mockDatabase, times(1)).writeEnvironmentChunks(anyList());
    }
    
    @Test
    void testFlushChunks_MixedEmptyAndNonEmpty() throws Exception {
        // Given: Indexer with mix of empty and non-empty ticks
        EnvironmentIndexer<?> indexer = new EnvironmentIndexer<>("test-indexer", config, resources);
        
        // Set envProps manually
        java.lang.reflect.Field envPropsField = EnvironmentIndexer.class.getDeclaredField("envProps");
        envPropsField.setAccessible(true);
        envPropsField.set(indexer, new EnvironmentProperties(new int[]{10, 10}, false));
        
        // Create mix: empty tick, non-empty, empty, non-empty
        TickData tick1 = TickData.newBuilder().setTickNumber(1L).build(); // Empty
        TickData tick2 = TickData.newBuilder()
            .setTickNumber(2L)
            .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                CellStateTestHelper.createCellStateBuilder(0, 100, 1, 10, 0).build()
            )))
            .build();
        TickData tick3 = TickData.newBuilder().setTickNumber(3L).build(); // Empty
        TickData tick4 = TickData.newBuilder()
            .setTickNumber(4L)
            .setCellColumns(CellStateTestHelper.createColumnsFromCells(List.of(
                CellStateTestHelper.createCellStateBuilder(1, 101, 2, 20, 0).build()
            )))
            .build();
        
        // When: Flush all ticks
        indexer.flushChunks(wrapAsChunks(tick1, tick2, tick3, tick4));
        
        // Then: Should call database ONCE with all 4 chunks
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TickDataChunk>> chunksCaptor = (ArgumentCaptor<List<TickDataChunk>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(mockDatabase, times(1)).writeEnvironmentChunks(chunksCaptor.capture());
        
        // Verify all chunks passed
        assertThat(chunksCaptor.getValue()).hasSize(4);
    }
    
    // ========== Helper Methods ==========
    
    /**
     * Wraps a TickData as a TickDataChunk with the tick as the snapshot.
     */
    private TickDataChunk wrapAsChunk(TickData tick) {
        return TickDataChunk.newBuilder()
            .setSimulationRunId("test-run")
            .setFirstTick(tick.getTickNumber())
            .setLastTick(tick.getTickNumber())
            .setSnapshot(tick)
            .build();
    }
    
    /**
     * Wraps multiple TickData objects as individual TickDataChunks.
     */
    private List<TickDataChunk> wrapAsChunks(TickData... ticks) {
        return java.util.stream.Stream.of(ticks)
            .map(this::wrapAsChunk)
            .toList();
    }
}

