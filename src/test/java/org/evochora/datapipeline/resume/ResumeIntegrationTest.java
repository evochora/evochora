package org.evochora.datapipeline.resume;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.DeltaType;
import org.evochora.datapipeline.api.contracts.EnvironmentConfig;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.datapipeline.resources.storage.FileSystemStorageResource;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.runtime.internal.services.SeededRandomProvider;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.spi.IRandomProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Integration tests for the resume-from-snapshot functionality.
 * <p>
 * These tests verify that:
 * <ul>
 *   <li>Checkpoints can be loaded from storage and restored to a simulation</li>
 *   <li>Organism state is correctly restored</li>
 *   <li>Metadata is preserved through checkpoint</li>
 * </ul>
 * <p>
 * Note: These tests use programmatically created test data rather than running a
 * full simulation, which makes them faster and more reliable.
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
@AllowLog(level = LogLevel.INFO, loggerPattern = ".*SnapshotLoader.*")
@AllowLog(level = LogLevel.INFO, loggerPattern = ".*SimulationRestorer.*")
@AllowLog(level = LogLevel.INFO, loggerPattern = ".*FileSystemStorageResource.*")
@AllowLog(level = LogLevel.INFO, loggerPattern = ".*AbstractBatchStorageResource.*")
class ResumeIntegrationTest {

    private static final long TEST_SEED = 12345L;
    private static final String TEST_RUN_ID = "20250127-123456-test-run";
    private static final int ACCUMULATED_DELTA_INTERVAL = 20;

    @TempDir
    Path tempDir;

    private Path storageDir;
    private Path programFile;

    @BeforeAll
    static void initInstructions() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() throws IOException {
        // Create storage directory
        storageDir = tempDir.resolve("storage");
        Files.createDirectories(storageDir);

        // Copy test program
        Path sourceProgram = Path.of("src/test/resources/org/evochora/datapipeline/services/simple.evo");
        programFile = tempDir.resolve("simple.evo");
        Files.copy(sourceProgram, programFile, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Tests that a checkpoint can be loaded and restored from storage.
     */
    @Test
    void checkpoint_CanBeLoadedAndRestored() throws Exception {
        FileSystemStorageResource storage = createStorageResource();

        // Create and store test data
        SimulationMetadata metadata = createTestMetadata();
        storage.writeMessage(TEST_RUN_ID + "/raw/metadata.pb", metadata);

        TickDataChunk chunk = createTestChunk(1, 50);
        storage.writeChunkBatch(List.of(chunk), 1, 50);

        // Load checkpoint
        SnapshotLoader loader = new SnapshotLoader(storage, storage);
        ResumeCheckpoint checkpoint = loader.loadLatestCheckpoint(TEST_RUN_ID);

        assertThat(checkpoint.getCheckpointTick()).isGreaterThan(0);

        // Restore simulation
        IRandomProvider randomProvider = new SeededRandomProvider(TEST_SEED);
        SimulationRestorer.RestoredState resumeState = SimulationRestorer.restore(checkpoint, randomProvider);

        assertThat(resumeState.simulation()).isNotNull();
        assertThat(resumeState.runId()).isEqualTo(TEST_RUN_ID);
        assertThat(resumeState.resumeFromTick()).isGreaterThan(0);
    }

    /**
     * Tests that checkpoint loading finds the last accumulated delta.
     */
    @Test
    void loadCheckpoint_FindsLastAccumulatedDelta() throws Exception {
        FileSystemStorageResource storage = createStorageResource();

        // Create metadata
        SimulationMetadata metadata = createTestMetadata();
        storage.writeMessage(TEST_RUN_ID + "/raw/metadata.pb", metadata);

        // Create chunk with accumulated deltas at tick 20 and 40
        TickDataChunk chunk = createTestChunkWithAccumulatedDeltas(1, 50);
        storage.writeChunkBatch(List.of(chunk), 1, 50);

        // Load checkpoint
        SnapshotLoader loader = new SnapshotLoader(storage, storage);
        ResumeCheckpoint checkpoint = loader.loadLatestCheckpoint(TEST_RUN_ID);

        // The checkpoint should be at an accumulated delta (tick 40)
        assertThat(checkpoint.getCheckpointTick()).isEqualTo(40);
        assertThat(checkpoint.hasAccumulatedDelta()).isTrue();
    }

    /**
     * Tests that simulation metadata is preserved through checkpoint.
     */
    @Test
    void resume_PreservesMetadata() throws Exception {
        FileSystemStorageResource storage = createStorageResource();

        // Create and store test data
        SimulationMetadata metadata = createTestMetadata();
        storage.writeMessage(TEST_RUN_ID + "/raw/metadata.pb", metadata);

        TickDataChunk chunk = createTestChunk(1, 50);
        storage.writeChunkBatch(List.of(chunk), 1, 50);

        // Load checkpoint
        SnapshotLoader loader = new SnapshotLoader(storage, storage);
        ResumeCheckpoint checkpoint = loader.loadLatestCheckpoint(TEST_RUN_ID);

        // Verify metadata
        SimulationMetadata loadedMetadata = checkpoint.metadata();
        assertThat(loadedMetadata.getSimulationRunId()).isEqualTo(TEST_RUN_ID);
        assertThat(loadedMetadata.getInitialSeed()).isEqualTo(TEST_SEED);
        assertThat(loadedMetadata.getEnvironment().getDimensions()).isEqualTo(2);
    }

    /**
     * Tests that organism state is correctly restored.
     */
    @Test
    void resume_RestoresOrganismState() throws Exception {
        FileSystemStorageResource storage = createStorageResource();

        // Create and store test data
        SimulationMetadata metadata = createTestMetadata();
        storage.writeMessage(TEST_RUN_ID + "/raw/metadata.pb", metadata);

        TickDataChunk chunk = createTestChunk(1, 50);
        storage.writeChunkBatch(List.of(chunk), 1, 50);

        // Load checkpoint and restore
        SnapshotLoader loader = new SnapshotLoader(storage, storage);
        ResumeCheckpoint checkpoint = loader.loadLatestCheckpoint(TEST_RUN_ID);

        IRandomProvider randomProvider = new SeededRandomProvider(TEST_SEED);
        SimulationRestorer.RestoredState resumeState = SimulationRestorer.restore(checkpoint, randomProvider);

        // Verify organisms are restored
        assertThat(resumeState.simulation().getOrganisms()).isNotEmpty();

        var organism = resumeState.simulation().getOrganisms().get(0);
        assertThat(organism.getId()).isEqualTo(1);
        assertThat(organism.getEr()).isGreaterThanOrEqualTo(0);
    }

    /**
     * Tests that multiple batch files are handled correctly.
     */
    @Test
    void resume_WithMultipleBatches_UsesLatest() throws Exception {
        FileSystemStorageResource storage = createStorageResource();

        // Create metadata
        SimulationMetadata metadata = createTestMetadata();
        storage.writeMessage(TEST_RUN_ID + "/raw/metadata.pb", metadata);

        // Create multiple chunks
        TickDataChunk chunk1 = createTestChunk(1, 50);
        TickDataChunk chunk2 = createTestChunkWithAccumulatedDeltas(51, 100);

        storage.writeChunkBatch(List.of(chunk1), 1, 50);
        storage.writeChunkBatch(List.of(chunk2), 51, 100);

        // Load checkpoint
        SnapshotLoader loader = new SnapshotLoader(storage, storage);
        ResumeCheckpoint checkpoint = loader.loadLatestCheckpoint(TEST_RUN_ID);

        // Should resume from the latest accumulated delta (tick 100 in chunk2)
        // With ACCUMULATED_DELTA_INTERVAL=20, deltas at 60, 80, 100 in chunk2
        assertThat(checkpoint.getCheckpointTick()).isEqualTo(100);
    }

    // ==================== Helper Methods ====================

    private FileSystemStorageResource createStorageResource() {
        Config storageConfig = ConfigFactory.parseMap(Map.of(
            "rootDirectory", storageDir.toString()
        ));
        return new FileSystemStorageResource("test-storage", storageConfig);
    }

    private SimulationMetadata createTestMetadata() throws IOException {
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

        return SimulationMetadata.newBuilder()
            .setSimulationRunId(TEST_RUN_ID)
            .setInitialSeed(TEST_SEED)
            .setStartTimeMs(System.currentTimeMillis())
            .setEnvironment(EnvironmentConfig.newBuilder()
                .setDimensions(2)
                .addShape(50)
                .addShape(50)
                .addToroidal(true)
                .build())
            .setResolvedConfigJson(configJson)
            .build();
    }

    private TickDataChunk createTestChunk(long firstTick, long lastTick) {
        // First tick has a snapshot (full TickData)
        OrganismState organism = createTestOrganism(1, 5000);
        TickData snapshot = TickData.newBuilder()
            .setTickNumber(firstTick)
            .setSimulationRunId(TEST_RUN_ID)
            .setCaptureTimeMs(System.currentTimeMillis())
            .addOrganisms(organism)
            .setCellColumns(CellDataColumns.newBuilder().build())
            .setTotalOrganismsCreated(1)
            .build();

        TickDataChunk.Builder chunkBuilder = TickDataChunk.newBuilder()
            .setSimulationRunId(TEST_RUN_ID)
            .setFirstTick(firstTick)
            .setLastTick(lastTick)
            .setTickCount((int)(lastTick - firstTick + 1))
            .setSnapshot(snapshot);

        // Subsequent ticks have incremental deltas
        for (long tick = firstTick + 1; tick <= lastTick; tick++) {
            TickDelta delta = TickDelta.newBuilder()
                .setTickNumber(tick)
                .setCaptureTimeMs(System.currentTimeMillis())
                .setDeltaType(DeltaType.INCREMENTAL)
                .addOrganisms(createTestOrganism(1, 5000 - (int)(tick - firstTick) * 10))
                .setChangedCells(CellDataColumns.newBuilder().build())
                .setTotalOrganismsCreated(1)
                .build();
            chunkBuilder.addDeltas(delta);
        }

        return chunkBuilder.build();
    }

    private TickDataChunk createTestChunkWithAccumulatedDeltas(long firstTick, long lastTick) {
        // First tick has a snapshot
        OrganismState organism = createTestOrganism(1, 5000);
        TickData snapshot = TickData.newBuilder()
            .setTickNumber(firstTick)
            .setSimulationRunId(TEST_RUN_ID)
            .setCaptureTimeMs(System.currentTimeMillis())
            .addOrganisms(organism)
            .setCellColumns(CellDataColumns.newBuilder().build())
            .setTotalOrganismsCreated(1)
            .build();

        TickDataChunk.Builder chunkBuilder = TickDataChunk.newBuilder()
            .setSimulationRunId(TEST_RUN_ID)
            .setFirstTick(firstTick)
            .setLastTick(lastTick)
            .setTickCount((int)(lastTick - firstTick + 1))
            .setSnapshot(snapshot);

        // Subsequent ticks - some with accumulated deltas
        for (long tick = firstTick + 1; tick <= lastTick; tick++) {
            boolean isAccumulated = (tick % ACCUMULATED_DELTA_INTERVAL == 0);

            TickDelta delta = TickDelta.newBuilder()
                .setTickNumber(tick)
                .setCaptureTimeMs(System.currentTimeMillis())
                .setDeltaType(isAccumulated ? DeltaType.ACCUMULATED : DeltaType.INCREMENTAL)
                .addOrganisms(createTestOrganism(1, 5000 - (int)(tick - firstTick) * 10))
                .setChangedCells(CellDataColumns.newBuilder().build())
                .setTotalOrganismsCreated(1)
                .build();
            chunkBuilder.addDeltas(delta);
        }

        return chunkBuilder.build();
    }

    private OrganismState createTestOrganism(int id, int energy) {
        return OrganismState.newBuilder()
            .setOrganismId(id)
            .setEnergy(energy)
            .setEntropyRegister(0)
            .setBirthTick(1)
            .setIp(createVector(10, 10))
            .setDv(createVector(1, 0))
            .build();
    }

    private Vector createVector(int... components) {
        Vector.Builder builder = Vector.newBuilder();
        for (int c : components) {
            builder.addComponents(c);
        }
        return builder.build();
    }
}
