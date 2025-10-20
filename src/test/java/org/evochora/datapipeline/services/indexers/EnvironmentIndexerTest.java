package org.evochora.datapipeline.services.indexers;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.datapipeline.api.contracts.CellState;
import org.evochora.datapipeline.api.contracts.EnvironmentConfig;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.database.IEnvironmentDataWriter;
import org.evochora.datapipeline.api.resources.database.IMetadataReader;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.resources.topics.ITopicReader;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for EnvironmentIndexer using mocked dependencies.
 * <p>
 * Tests individual methods (prepareSchema, flushTicks, extractEnvironmentProperties) in isolation.
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class EnvironmentIndexerTest {
    
    private IEnvironmentDataWriter mockDatabase;
    private IBatchStorageRead mockStorage;
    private ITopicReader mockTopic;
    private IMetadataReader mockMetadata;
    private Config config;
    private Map<String, List<IResource>> resources;
    
    @BeforeEach
    void setUp() {
        mockDatabase = mock(IEnvironmentDataWriter.class);
        mockStorage = mock(IBatchStorageRead.class);
        mockTopic = mock(ITopicReader.class);
        mockMetadata = mock(IMetadataReader.class);
        config = ConfigFactory.parseString("""
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            topicPollTimeoutMs = 1000
            insertBatchSize = 100
            flushTimeoutMs = 1000
            """);
        resources = Map.of(
            "database", List.of(mockDatabase),
            "storage", List.of(mockStorage),
            "topic", List.of(mockTopic),
            "metadata", List.of(mockMetadata)
        );
    }
    
    @Test
    void testConstructor_GetsDatabaseResource() {
        // When: Create indexer
        EnvironmentIndexer<Object> indexer = new EnvironmentIndexer<>("test-indexer", config, resources);
        
        // Then: Should succeed (no exception)
        assertThat(indexer).isNotNull();
    }
    
    @Test
    void testGetMetadata_NotLoadedYet() {
        // Given: Indexer with metadata component but metadata not yet loaded
        EnvironmentIndexer<Object> indexer = new EnvironmentIndexer<>("test-indexer", config, resources);
        
        // When/Then: Calling getMetadata before loadMetadata should throw IllegalStateException
        assertThatThrownBy(() -> indexer.getMetadata())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Metadata not loaded");
    }
    
    @Test
    void testFlushTicks_EmptyList() throws Exception {
        // Given: Indexer with empty tick list
        EnvironmentIndexer<Object> indexer = new EnvironmentIndexer<>("test-indexer", config, resources);
        
        // When: Flush empty list
        indexer.flushTicks(List.of());
        
        // Then: Should not call database
        verifyNoInteractions(mockDatabase);
    }
    
    @Test
    void testFlushTicks_CallsDatabase() throws Exception {
        // Given: Indexer with ticks
        EnvironmentIndexer<Object> indexer = new EnvironmentIndexer<>("test-indexer", config, resources);
        
        // Set envProps manually (normally set by prepareSchema)
        java.lang.reflect.Field envPropsField = EnvironmentIndexer.class.getDeclaredField("envProps");
        envPropsField.setAccessible(true);
        envPropsField.set(indexer, new EnvironmentProperties(new int[]{10, 10}, false));
        
        TickData tick = TickData.newBuilder()
            .setTickNumber(1L)
            .addCells(CellState.newBuilder()
                .setFlatIndex(0)
                .setOwnerId(100)
                .setMoleculeType(1)
                .setMoleculeValue(50)
                .build())
            .build();
        
        // When: Flush ticks
        indexer.flushTicks(List.of(tick));
        
        // Then: Should call database.writeEnvironmentCells
        ArgumentCaptor<List<TickData>> ticksCaptor = ArgumentCaptor.forClass(List.class);
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
        EnvironmentIndexer<Object> indexer = new EnvironmentIndexer<>("test-indexer", config, resources);
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
}

