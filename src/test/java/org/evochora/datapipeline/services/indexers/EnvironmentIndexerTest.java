package org.evochora.datapipeline.services.indexers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.contracts.BatchInfo;
import org.evochora.datapipeline.CellStateTestHelper;
import org.evochora.datapipeline.api.contracts.EnvironmentConfig;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
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
 * Tests individual methods (prepareTables, flushTicks, extractEnvironmentProperties) in isolation.
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
    void testFlushTicks_EmptyList() throws Exception {
        // Given: Indexer with empty tick list
        EnvironmentIndexer<?> indexer = new EnvironmentIndexer<>("test-indexer", config, resources);
        
        // When: Flush empty list
        indexer.flushTicks(List.of());
        
        // Then: Should not call database
        verifyNoInteractions(mockDatabase);
    }
    
    @Test
    void testFlushTicks_CallsDatabase() throws Exception {
        // Given: Indexer with ticks
        EnvironmentIndexer<?> indexer = new EnvironmentIndexer<>("test-indexer", config, resources);
        
        // Set envProps manually (normally set by prepareTables)
        java.lang.reflect.Field envPropsField = EnvironmentIndexer.class.getDeclaredField("envProps");
        envPropsField.setAccessible(true);
        envPropsField.set(indexer, new EnvironmentProperties(new int[]{10, 10}, false));
        
        TickData tick = TickData.newBuilder()
            .setTickNumber(1L)
            .addCells(CellStateTestHelper.createCellStateBuilder(0, 100, 1, 50, 0))
            .build();
        
        // When: Flush ticks
        indexer.flushTicks(List.of(tick));
        
        // Then: Should call database.writeEnvironmentCells
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TickData>> ticksCaptor = (ArgumentCaptor<List<TickData>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<EnvironmentProperties> envPropsCaptor = ArgumentCaptor.forClass(EnvironmentProperties.class);
        verify(mockDatabase).writeEnvironmentCells(ticksCaptor.capture(), envPropsCaptor.capture());
        
        assertThat(ticksCaptor.getValue()).hasSize(1);
        assertThat(ticksCaptor.getValue().get(0).getTickNumber()).isEqualTo(1L);
        assertThat(envPropsCaptor.getValue().getWorldShape()).containsExactly(10, 10);
    }
    
    @Test
    void testExtractEnvironmentProperties_ToroidalTopology() throws Exception {
        // Given: Metadata with toroidal topology
        SimulationMetadata metadata = SimulationMetadata.newBuilder()
            .setSimulationRunId("test-run")
            .setEnvironment(EnvironmentConfig.newBuilder()
                .setDimensions(2)
                .addShape(100)
                .addShape(100)
                .addToroidal(true)
                .addToroidal(true)
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
        // Given: Metadata with euclidean (non-toroidal) topology
        SimulationMetadata metadata = SimulationMetadata.newBuilder()
            .setSimulationRunId("test-run")
            .setEnvironment(EnvironmentConfig.newBuilder()
                .setDimensions(3)
                .addShape(10)
                .addShape(10)
                .addShape(10)
                .addToroidal(false)
                .addToroidal(false)
                .addToroidal(false)
                .build())
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
        // Given: Metadata with 1D environment
        SimulationMetadata metadata = SimulationMetadata.newBuilder()
            .setSimulationRunId("test-run")
            .setEnvironment(EnvironmentConfig.newBuilder()
                .setDimensions(1)
                .addShape(1000)
                .addToroidal(true)
                .build())
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
    void testFlushTicks_MultipleTicks() throws Exception {
        // Given: Indexer with multiple ticks
        EnvironmentIndexer<?> indexer = new EnvironmentIndexer<>("test-indexer", config, resources);
        
        // Set envProps manually (normally set by prepareSchema)
        java.lang.reflect.Field envPropsField = EnvironmentIndexer.class.getDeclaredField("envProps");
        envPropsField.setAccessible(true);
        envPropsField.set(indexer, new EnvironmentProperties(new int[]{10, 10}, false));
        
        // Create 3 ticks with different cell counts
        TickData tick1 = TickData.newBuilder()
            .setTickNumber(1L)
            .addCells(CellStateTestHelper.createCellStateBuilder(0, 100, 1, 10, 0))
            .addCells(CellStateTestHelper.createCellStateBuilder(1, 101, 2, 20, 0))
            .build();
        
        TickData tick2 = TickData.newBuilder()
            .setTickNumber(2L)
            .addCells(CellStateTestHelper.createCellStateBuilder(2, 102, 1, 30, 0))
            .build();
        
        TickData tick3 = TickData.newBuilder()
            .setTickNumber(3L)
            .addCells(CellStateTestHelper.createCellStateBuilder(3, 103, 3, 40, 0))
            .addCells(CellStateTestHelper.createCellStateBuilder(4, 104, 1, 50, 0))
            .addCells(CellStateTestHelper.createCellStateBuilder(5, 105, 2, 60, 0))
            .build();
        
        // When: Flush all ticks in one call
        indexer.flushTicks(List.of(tick1, tick2, tick3));
        
        // Then: Should call database.writeEnvironmentCells ONCE with all 3 ticks
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TickData>> ticksCaptor = (ArgumentCaptor<List<TickData>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(mockDatabase, times(1)).writeEnvironmentCells(ticksCaptor.capture(), any(EnvironmentProperties.class));
        
        // Verify all ticks passed in one call
        assertThat(ticksCaptor.getValue()).hasSize(3);
        assertThat(ticksCaptor.getValue().get(0).getTickNumber()).isEqualTo(1L);
        assertThat(ticksCaptor.getValue().get(1).getTickNumber()).isEqualTo(2L);
        assertThat(ticksCaptor.getValue().get(2).getTickNumber()).isEqualTo(3L);
    }
    
    @Test
    void testFlushTicks_EmptyTicks() throws Exception {
        // Given: Indexer with tick containing NO cells
        EnvironmentIndexer<?> indexer = new EnvironmentIndexer<>("test-indexer", config, resources);
        
        // Set envProps manually
        java.lang.reflect.Field envPropsField = EnvironmentIndexer.class.getDeclaredField("envProps");
        envPropsField.setAccessible(true);
        envPropsField.set(indexer, new EnvironmentProperties(new int[]{10, 10}, false));
        
        // Create tick with NO cells
        TickData emptyTick = TickData.newBuilder()
            .setTickNumber(1L)
            .build();  // No cells added
        
        // When: Flush empty tick
        indexer.flushTicks(List.of(emptyTick));
        
        // Then: Should still call database (database handles empty efficiently via filtering)
        verify(mockDatabase, times(1)).writeEnvironmentCells(anyList(), any(EnvironmentProperties.class));
    }
    
    @Test
    void testFlushTicks_MixedEmptyAndNonEmpty() throws Exception {
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
            .addCells(CellStateTestHelper.createCellStateBuilder(0, 100, 1, 10, 0))
            .build();
        TickData tick3 = TickData.newBuilder().setTickNumber(3L).build(); // Empty
        TickData tick4 = TickData.newBuilder()
            .setTickNumber(4L)
            .addCells(CellStateTestHelper.createCellStateBuilder(1, 101, 2, 20, 0))
            .build();
        
        // When: Flush all ticks
        indexer.flushTicks(List.of(tick1, tick2, tick3, tick4));
        
        // Then: Should call database ONCE with all 4 ticks (including empty ones)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TickData>> ticksCaptor = (ArgumentCaptor<List<TickData>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(mockDatabase, times(1)).writeEnvironmentCells(ticksCaptor.capture(), any(EnvironmentProperties.class));
        
        // Verify all ticks passed (database layer will filter empty ones)
        assertThat(ticksCaptor.getValue()).hasSize(4);
    }
}

