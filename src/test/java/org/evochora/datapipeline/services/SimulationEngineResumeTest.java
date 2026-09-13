package org.evochora.datapipeline.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.evochora.datapipeline.TestMetadataHelper;
import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.queues.IOutputQueueResource;
import org.evochora.datapipeline.api.resources.storage.BatchFileListResult;
import org.evochora.datapipeline.api.resources.storage.CheckedConsumer;
import org.evochora.datapipeline.api.resources.storage.ChunkFieldFilter;
import org.evochora.datapipeline.api.resources.storage.RawChunk;
import org.evochora.datapipeline.api.resources.storage.IBatchStorageRead;
import org.evochora.datapipeline.api.resources.storage.StoragePath;
import org.evochora.datapipeline.resume.ResumeException;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.evochora.test.utils.ProtoTestUtils;

/**
 * Unit tests for SimulationEngine resume mode (config-driven).
 * <p>
 * Tests verify that SimulationEngine correctly initializes from a checkpoint
 * when resume mode is enabled via configuration.
 * <p>
 * Resume always happens from a snapshot (chunk start), which simplifies
 * the storage interface (read-only access is sufficient).
 */
@Tag("unit")
@ExtendWith(LogWatchExtension.class)
@AllowLog(level = LogLevel.WARN, loggerPattern = ".*SimulationEngine.*", messagePattern = "Run .* was written by build .* and is read by build .*")
class SimulationEngineResumeTest {

    private static final String TEST_RUN_ID = "20250127-123456-test-run";

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    private IOutputQueueResource<TickDataChunk> mockTickDataResource;
    private IOutputQueueResource<SimulationMetadata> mockMetadataResource;
    private StorageResourceMock mockStorageResource;
    private Map<String, List<IResource>> resources;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        mockTickDataResource = mock(IOutputQueueResource.class);
        mockMetadataResource = mock(IOutputQueueResource.class);
        mockStorageResource = new StorageResourceMock();

        resources = new HashMap<>();
        resources.put("tickData", List.of(mockTickDataResource));
        resources.put("metadataOutput", List.of(mockMetadataResource));
        resources.put("resumeStorage", List.of(mockStorageResource));
    }

    @Test
    void resumeMode_InitializesFromCheckpoint() throws IOException {
        // Setup mock storage with checkpoint data (snapshot at tick 1000)
        setupValidCheckpoint(1000);

        Config options = createResumeOptions(TEST_RUN_ID);

        // Create engine in resume mode
        SimulationEngine engine = new SimulationEngine("test-engine", options, resources);

        // Verify engine is in STOPPED state (not started yet)
        assertThat(engine.getCurrentState()).isEqualTo(AbstractService.State.STOPPED);
    }

    @Test
    void resumeMode_WithoutRunId_ThrowsException() {
        // Options with resume.enabled=true but no runId
        Config options = ConfigFactory.parseString("""
            resume {
                enabled = true
            }
            samplingInterval = 1
            accumulatedDeltaInterval = 40
            snapshotInterval = 1000
            chunkInterval = 100
            metricsWindowSeconds = 1
            pauseTicks = []
            """);

        assertThatThrownBy(() -> new SimulationEngine("test-engine", options, resources))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("resume.runId");
    }

    @Test
    void resumeMode_WithMissingCheckpoint_ThrowsException() throws IOException {
        // Setup mock storage with no checkpoint data
        mockStorageResource.setMetadataPath(Optional.empty());

        Config options = createResumeOptions(TEST_RUN_ID);

        // ResumeException is thrown by SnapshotLoader when metadata is not found
        assertThatThrownBy(() -> new SimulationEngine("test-engine", options, resources))
            .isInstanceOf(ResumeException.class)
            .hasMessageContaining("Metadata not found");
    }

    @Test
    void forkMode_TickInsideCheckpointChunk_InitializesFromThatCheckpoint() throws IOException {
        setupValidCheckpoint(1000);

        Config options = createForkOptions(TEST_RUN_ID, 1050, 1100, 100);

        SimulationEngine engine = new SimulationEngine("test-engine", options, resources);

        assertThat(engine.getCurrentState()).isEqualTo(AbstractService.State.STOPPED);
    }

    @Test
    void forkMode_TickBeyondRecordedData_NamesWhereTheDataEnds() throws IOException {
        setupValidCheckpoint(1000);

        Config options = createForkOptions(TEST_RUN_ID, 5000, 6000, 100);

        assertThatThrownBy(() -> new SimulationEngine("test-engine", options, resources))
            .isInstanceOf(ResumeException.class)
            .hasMessageContaining("tick 5000")
            .hasMessageContaining("covers ticks 1000 to 1099");
    }

    @Test
    void forkMode_WindowEndBeforeStart_ThrowsException() throws IOException {
        setupValidCheckpoint(1000);

        Config options = createForkOptions(TEST_RUN_ID, 1100, 1050, 100);

        assertThatThrownBy(() -> new SimulationEngine("test-engine", options, resources))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("toTick");
    }

    @Test
    void forkMode_WithoutWindowEnd_ThrowsException() throws IOException {
        setupValidCheckpoint(1000);

        Config options = ConfigFactory.parseString("""
            resume {
                enabled = true
                runId = "%s"
                fork { fromTick = 1050 }
            }
            samplingInterval = 1
            accumulatedDeltaInterval = 40
            snapshotInterval = 1000
            chunkInterval = 100
            metricsWindowSeconds = 1
            pauseTicks = []
            """.formatted(TEST_RUN_ID));

        assertThatThrownBy(() -> new SimulationEngine("test-engine", options, resources))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("fromTick and toTick");
    }

    @Test
    void forkMode_ChunksNotDividingTheParents_ThrowsException() throws IOException {
        setupValidCheckpoint(1000);

        // The checkpoint's run records 1 × 40 × 1000 × 100 ticks per chunk; 1 × 40 × 1000 × 3 does not divide that
        Config options = createForkOptions(TEST_RUN_ID, 1050, 1100, 3);

        assertThatThrownBy(() -> new SimulationEngine("test-engine", options, resources))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must divide the parent's")
            .hasMessageContaining("120000")
            .hasMessageContaining("4000000");
    }

    // ==================== Helper Methods ====================

    private void setupValidCheckpoint(long snapshotTick) throws IOException {
        // Setup metadata path
        StoragePath metadataPath = StoragePath.of(TEST_RUN_ID + "/raw/metadata.pb");
        mockStorageResource.setMetadataPath(Optional.of(metadataPath));
        mockStorageResource.setMetadata(createTestMetadata());

        // Setup batch file
        StoragePath batchPath = StoragePath.of(TEST_RUN_ID + "/raw/000/000/batch.pb");
        mockStorageResource.setBatchPath(batchPath);
        mockStorageResource.setChunk(createTestChunk(snapshotTick));
    }

    private SimulationMetadata createTestMetadata() {
        String configJson = """
            {
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

        // Build the full resolvedConfigJson with environment and runtime
        String fullConfigJson = TestMetadataHelper.builder()
            .shape(64, 64)
            .toroidal(true)
            .samplingInterval(1)
            .accumulatedDeltaInterval(40)
            .snapshotInterval(1000)
            .chunkInterval(100)
            .build();

        // Parse and merge with runtime config
        com.typesafe.config.Config parsedConfig = com.typesafe.config.ConfigFactory.parseString(fullConfigJson);
        com.typesafe.config.Config runtimeConfig = com.typesafe.config.ConfigFactory.parseString(configJson);
        String mergedJson = parsedConfig.withFallback(runtimeConfig)
            .root().render(com.typesafe.config.ConfigRenderOptions.concise());

        return SimulationMetadata.newBuilder()
            .setSimulationRunId(TEST_RUN_ID)
            .setStartTimeMs(System.currentTimeMillis())
            .setInitialSeed(42)
            .setResolvedConfigJson(mergedJson)
            .build();
    }

    private TickDataChunk createTestChunk(long snapshotTick) {
        // Resume always uses the snapshot at chunk start
        TickData snapshot = TickData.newBuilder()
            .setSimulationRunId(TEST_RUN_ID)
            .setTickNumber(snapshotTick)
            .setCaptureTimeMs(System.currentTimeMillis())
            .setTotalOrganismsCreated(100)
            .setCellColumns(CellDataColumns.newBuilder().build())
            .addOrganisms(createOrganismState(1, 500))
            .setRngState(ProtoTestUtils.rngState(42))
            .build();

        return TickDataChunk.newBuilder()
            .setSimulationRunId(TEST_RUN_ID)
            .setFirstTick(snapshotTick)
            .setLastTick(snapshotTick + 99)
            .setTickCount(100)
            .setSnapshot(snapshot)
            .build();
    }

    private OrganismState createOrganismState(int id, int energy) {
        return ProtoTestUtils.wellFormedOrganism(id, energy, 10, 10).build();
    }

    private Config createResumeOptions(String runId) {
        return ConfigFactory.parseString("""
            resume {
                enabled = true
                runId = "%s"
            }
            samplingInterval = 1
            accumulatedDeltaInterval = 40
            snapshotInterval = 1000
            chunkInterval = 100
            metricsWindowSeconds = 1
            pauseTicks = []
            """.formatted(runId));
    }

    private Config createForkOptions(String runId, long fromTick, long toTick, int chunkInterval) {
        return ConfigFactory.parseString("""
            resume {
                enabled = true
                runId = "%s"
                fork {
                    fromTick = %d
                    toTick = %d
                }
            }
            samplingInterval = 1
            accumulatedDeltaInterval = 40
            snapshotInterval = 1000
            chunkInterval = %d
            metricsWindowSeconds = 1
            pauseTicks = []
            """.formatted(runId, fromTick, toTick, chunkInterval));
    }

    /**
     * Simple mock storage resource for resume mode (read-only).
     */
    private static class StorageResourceMock implements IBatchStorageRead {
        private Optional<StoragePath> metadataPath = Optional.empty();
        private SimulationMetadata metadata;
        private StoragePath batchPath;
        private TickDataChunk chunk;

        void setMetadataPath(Optional<StoragePath> path) {
            this.metadataPath = path;
        }

        void setMetadata(SimulationMetadata metadata) {
            this.metadata = metadata;
        }

        void setBatchPath(StoragePath path) {
            this.batchPath = path;
        }

        void setChunk(TickDataChunk chunk) {
            this.chunk = chunk;
        }

        @Override
        public String getResourceName() {
            return "mock-storage";
        }

        @Override
        public UsageState getUsageState(String usageType) {
            return UsageState.ACTIVE;
        }

        @Override
        public Optional<StoragePath> findMetadataPath(String runId) {
            return metadataPath;
        }

        @Override
        public Optional<StoragePath> findLastBatchFile(String runIdPrefix) {
            return Optional.ofNullable(batchPath);
        }

        @Override
        public Optional<StoragePath> findBatchFileContaining(String runIdPrefix, long tick) {
            if (chunk == null || tick < chunk.getFirstTick() || tick > chunk.getLastTick()) {
                return Optional.empty();
            }
            return Optional.ofNullable(batchPath);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends com.google.protobuf.MessageLite> T readMessage(StoragePath path,
                com.google.protobuf.Parser<T> parser) {
            return (T) metadata;
        }

        @Override
        public BatchFileListResult listBatchFiles(String prefix, String continuationToken, int maxResults,
                long startTick, long endTick, SortOrder sortOrder) {
            return new BatchFileListResult(batchPath != null ? List.of(batchPath) : List.of(), null, false);
        }

        @Override
        public void forEachRawChunk(StoragePath path,
                                    CheckedConsumer<RawChunk> consumer) throws Exception {
            if (chunk != null) {
                consumer.accept(new RawChunk(
                    chunk.getFirstTick(), chunk.getLastTick(),
                    chunk.getTickCount(), chunk.getSamplingInterval(), chunk.toByteArray()));
            }
        }

        @Override
        public void forEachChunk(StoragePath path, ChunkFieldFilter filter,
                                 CheckedConsumer<TickDataChunk> consumer) throws Exception {
            if (chunk != null) {
                consumer.accept(chunk);
            }
        }

        @Override
        public List<String> listRunIds(java.time.Instant afterTimestamp) {
            return List.of(TEST_RUN_ID);
        }
    }
}
