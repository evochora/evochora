package org.evochora.datapipeline.services.indexers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.ResourceContext;
import org.evochora.datapipeline.api.resources.database.IDatabaseReader;
import org.evochora.datapipeline.api.resources.database.IResourceSchemaAwareOrganismDataWriter;
import org.evochora.datapipeline.api.resources.database.IResourceSchemaAwareMetadataReader;
import org.evochora.datapipeline.api.resources.database.dto.OrganismTickSummary;
import org.evochora.datapipeline.api.resources.storage.ChunkFieldFilter;
import org.evochora.datapipeline.api.resources.storage.IResourceBatchStorageRead;
import org.evochora.datapipeline.api.resources.topics.IResourceTopicReader;
import org.evochora.datapipeline.resources.database.H2Database;
import org.evochora.datapipeline.resources.database.OrganismDataWriterWrapper;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import com.typesafe.config.ConfigFactory;

/**
 * Unit test for OrganismIndexer streaming wiring.
 * <p>
 * Verifies that the streaming lifecycle (processChunk → writeOrganismTick,
 * commitProcessedChunks → commitOrganismWrites) delegates correctly and that
 * the indexer configuration is set up for streaming.
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
class OrganismIndexerTest {

    @TempDir
    Path tempDir;

    private H2Database database;
    private OrganismDataWriterWrapper wrapper;
    private TestOrganismIndexer<?> indexer;

    @BeforeEach
    void setUp() throws Exception {
        String dbPath = tempDir.toString().replace("\\", "/");
        var config = ConfigFactory.parseString("""
            jdbcUrl = "jdbc:h2:file:%s/test-organism-indexer;MODE=PostgreSQL"
            """.formatted(dbPath));

        database = new H2Database("test-db", config);

        ResourceContext context = new ResourceContext("test-service", "port", "db-organism-write", "test-db", Map.of());
        wrapper = (OrganismDataWriterWrapper) database.getWrappedResource(context);

        // Mock storage, topic, and metadata — not used by the tests, but required by AbstractBatchIndexer constructor
        IResource mockStorage = (IResource) mock(IResourceBatchStorageRead.class);
        IResource mockTopic = (IResource) mock(IResourceTopicReader.class);
        IResource mockMetadata = (IResource) mock(IResourceSchemaAwareMetadataReader.class);

        Map<String, List<IResource>> resources = Map.of(
                "database", List.of((IResource) wrapper),
                "storage", List.of(mockStorage),
                "topic", List.of(mockTopic),
                "metadata", List.of(mockMetadata)
        );

        var indexerConfig = ConfigFactory.parseString("""
            metadataPollIntervalMs = 100
            metadataMaxPollDurationMs = 5000
            insertBatchSize = 5
            flushTimeoutMs = 5000
            """);
        indexer = new TestOrganismIndexer<>("organism-indexer-test", indexerConfig, resources);

        wrapper.setSimulationRun("test-run");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (wrapper != null) {
            wrapper.close();
        }
        if (database != null) {
            database.close();
        }
    }

    @Test
    void testUseStreamingProcessing_ReturnsTrue() {
        assertThat(indexer.callUseStreamingProcessing()).isTrue();
    }

    @Test
    void testGetRequiredComponents_ExcludesBuffering() {
        Set<AbstractBatchIndexer.ComponentType> components = indexer.callGetRequiredComponents();
        assertThat(components).containsExactly(AbstractBatchIndexer.ComponentType.METADATA);
    }

    @Test
    void testGetChunkFieldFilter_ReturnsSkipCells() {
        assertThat(indexer.callGetChunkFieldFilter()).isEqualTo(ChunkFieldFilter.SKIP_CELLS);
    }

    @Test
    void testFlushChunks_ThrowsUnsupportedOperation() {
        assertThatThrownBy(() -> indexer.callFlushChunks(List.of()))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void testProcessChunk_WritesSnapshotAndDeltas() throws Exception {
        // Given: tables created
        wrapper.createOrganismTables();

        TickDataChunk chunk = buildChunkWithOrganisms(0L, 100L, 2);

        // When: process + commit
        indexer.callProcessChunk(chunk);
        indexer.callCommitProcessedChunks();

        // Then: read back and verify persisted data
        try (IDatabaseReader reader = database.createReader("test-run")) {
            // Snapshot tick (tick 0): 2 organisms
            List<OrganismTickSummary> snapshotOrganisms = reader.readOrganismsAtTick(0L);
            assertThat(snapshotOrganisms).hasSize(2);
            assertThat(snapshotOrganisms).extracting(o -> o.organismId)
                .containsExactlyInAnyOrder(0, 1);
            assertThat(snapshotOrganisms).extracting(o -> o.energy)
                .allMatch(e -> e == 100);

            // Delta tick (tick 100): 1 organism with updated energy
            List<OrganismTickSummary> deltaOrganisms = reader.readOrganismsAtTick(100L);
            assertThat(deltaOrganisms).hasSize(1);
            assertThat(deltaOrganisms.get(0).organismId).isEqualTo(0);
            assertThat(deltaOrganisms.get(0).energy).isEqualTo(80);
        }
    }

    @Test
    void testCommitProcessedChunks_Idempotent() throws Exception {
        // Given: tables created, no data written
        wrapper.createOrganismTables();

        // When: commit without any processChunk calls
        indexer.callCommitProcessedChunks();

        // Then: no exception thrown
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private TickDataChunk buildChunkWithOrganisms(long firstTick, long lastTick, int numOrganisms) {
        TickData.Builder snapshotBuilder = TickData.newBuilder()
            .setTickNumber(firstTick);

        for (int i = 0; i < numOrganisms; i++) {
            snapshotBuilder.addOrganisms(OrganismState.newBuilder()
                .setOrganismId(i)
                .setBirthTick(0)
                .setProgramId("test")
                .setEnergy(100)
                .setIp(Vector.newBuilder().addComponents(0).addComponents(0).build())
                .setDv(Vector.newBuilder().addComponents(1).addComponents(0).build())
                .setInitialPosition(Vector.newBuilder().addComponents(0).addComponents(0).build())
                .build());
        }

        TickDelta delta = TickDelta.newBuilder()
            .setTickNumber(lastTick)
            .addOrganisms(OrganismState.newBuilder()
                .setOrganismId(0)
                .setBirthTick(0)
                .setProgramId("test")
                .setEnergy(80)
                .setIp(Vector.newBuilder().addComponents(1).addComponents(1).build())
                .setDv(Vector.newBuilder().addComponents(0).addComponents(1).build())
                .setInitialPosition(Vector.newBuilder().addComponents(0).addComponents(0).build())
                .build())
            .build();

        return TickDataChunk.newBuilder()
            .setFirstTick(firstTick)
            .setLastTick(lastTick)
            .setTickCount(2)
            .setSnapshot(snapshotBuilder.build())
            .addDeltas(delta)
            .build();
    }

    /**
     * Test subclass exposing protected methods for direct invocation.
     */
    private static class TestOrganismIndexer<ACK> extends OrganismIndexer<ACK> {

        TestOrganismIndexer(String name, com.typesafe.config.Config options, Map<String, List<IResource>> resources) {
            super(name, options, resources);
        }

        boolean callUseStreamingProcessing() {
            return useStreamingProcessing();
        }

        java.util.Set<ComponentType> callGetRequiredComponents() {
            return getRequiredComponents();
        }

        ChunkFieldFilter callGetChunkFieldFilter() {
            return getChunkFieldFilter();
        }

        void callFlushChunks(List<TickDataChunk> chunks) throws Exception {
            flushChunks(chunks);
        }

        void callProcessChunk(TickDataChunk chunk) throws Exception {
            processChunk(chunk);
        }

        void callCommitProcessedChunks() throws Exception {
            commitProcessedChunks();
        }
    }
}
