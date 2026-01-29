package org.evochora.datapipeline.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.queues.IOutputQueueResource;
import org.evochora.datapipeline.resources.storage.FileSystemStorageResource;
import org.evochora.datapipeline.services.AbstractService;
import org.evochora.datapipeline.services.SimulationEngine;
import org.evochora.junit.extensions.logging.AllowLog;
import org.evochora.junit.extensions.logging.LogLevel;
import org.evochora.junit.extensions.logging.LogWatchExtension;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * End-to-end test for the resume-from-snapshot functionality.
 * <p>
 * This test verifies the complete resume workflow:
 * <ol>
 *   <li>Run a simulation for several ticks</li>
 *   <li>Stop the simulation</li>
 *   <li>Resume from checkpoint</li>
 *   <li>Verify the simulation continues from the correct tick</li>
 * </ol>
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
@AllowLog(level = LogLevel.INFO, loggerPattern = ".*SimulationEngine.*")
@AllowLog(level = LogLevel.INFO, loggerPattern = ".*SnapshotLoader.*")
@AllowLog(level = LogLevel.INFO, loggerPattern = ".*SimulationRestorer.*")
@AllowLog(level = LogLevel.INFO, loggerPattern = ".*FileSystemStorageResource.*")
@AllowLog(level = LogLevel.INFO, loggerPattern = ".*AbstractBatchStorageResource.*")
class ResumeEndToEndTest {

    // Test-friendly intervals - much smaller than production values
    // samplesPerSnapshot = ACCUMULATED_DELTA_INTERVAL * SNAPSHOT_INTERVAL = 10 * 5 = 50
    // samplesPerChunk = samplesPerSnapshot * CHUNK_INTERVAL = 50 * 1 = 50
    // NOTE: CHUNK_INTERVAL must be 1 due to DeltaCodec logic (only creates 1 snapshot per chunk)
    private static final int ACCUMULATED_DELTA_INTERVAL = 10;
    private static final int SNAPSHOT_INTERVAL = 5;
    private static final int CHUNK_INTERVAL = 1;

    @TempDir
    Path tempDir;

    private Path storageDir;
    private Path programFile;
    private FileSystemStorageResource storage;

    @BeforeAll
    static void initInstructions() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() throws IOException {
        storageDir = tempDir.resolve("storage");
        Files.createDirectories(storageDir);

        // Copy test program
        Path sourceProgram = Path.of("src/test/resources/org/evochora/datapipeline/services/simple.evo");
        programFile = tempDir.resolve("simple.evo");
        Files.copy(sourceProgram, programFile, StandardCopyOption.REPLACE_EXISTING);

        // Create storage resource
        Config storageConfig = ConfigFactory.parseMap(Map.of(
            "rootDirectory", storageDir.toString()
        ));
        storage = new FileSystemStorageResource("test-storage", storageConfig);
    }

    /**
     * Tests the complete resume workflow: run → stop → resume → verify.
     */
    @Test
    void resumeWorkflow_ContinuesFromLastCheckpoint() throws Exception {
        // Capture queues
        CapturingQueue<TickDataChunk> tickQueue = new CapturingQueue<>();
        CapturingQueue<SimulationMetadata> metadataQueue = new CapturingQueue<>();

        // Phase 1: Run initial simulation
        String runId = runSimulationAndCapture(tickQueue, metadataQueue, 100);

        // Verify we captured data
        assertThat(metadataQueue.getCaptured()).hasSize(1);
        assertThat(tickQueue.getCaptured()).isNotEmpty();

        // Phase 2: Persist captured data to storage
        SimulationMetadata metadata = metadataQueue.getCaptured().get(0);
        storage.writeMessage(runId + "/raw/metadata.pb", metadata);

        for (TickDataChunk chunk : tickQueue.getCaptured()) {
            storage.writeChunkBatch(List.of(chunk), chunk.getFirstTick(), chunk.getLastTick());
        }

        // Load checkpoint the same way SnapshotLoader does to get the expected resume tick
        SnapshotLoader loader = new SnapshotLoader(storage);
        ResumeCheckpoint checkpoint = loader.loadLatestCheckpoint(runId);

        // The first chunk after resume starts at the checkpoint snapshot tick.
        // The encoder is initialized with the checkpoint snapshot, so the chunk's firstTick
        // equals the snapshot's tick number.
        long expectedFirstTick = checkpoint.getCheckpointTick();

        // Phase 3: Create resume simulation
        CapturingQueue<TickDataChunk> resumeTickQueue = new CapturingQueue<>();
        CapturingQueue<SimulationMetadata> resumeMetadataQueue = new CapturingQueue<>();

        SimulationEngine resumeEngine = createResumeEngine(
            runId, resumeTickQueue, resumeMetadataQueue);

        // Verify initial state
        assertThat(resumeEngine.getCurrentState()).isEqualTo(AbstractService.State.STOPPED);

        // Phase 4: Run resumed simulation for a few more ticks
        Thread resumeThread = new Thread(() -> {
            try {
                resumeEngine.start();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        resumeThread.start();

        // Wait for some ticks to be produced
        await().atMost(Duration.ofSeconds(10))
            .until(() -> !resumeTickQueue.getCaptured().isEmpty());

        // Stop
        resumeEngine.stop();
        resumeThread.join(5000);

        // Phase 5: Verify resume behavior
        // - No new metadata should be sent (resume mode)
        assertThat(resumeMetadataQueue.getCaptured()).isEmpty();

        // - First tick of resumed simulation should be at chunk boundary containing resumeFromTick
        // (recapture overwrites any orphaned batch and ensures notification is sent)
        TickDataChunk firstResumedChunk = resumeTickQueue.getCaptured().get(0);
        assertThat(firstResumedChunk.getFirstTick()).isEqualTo(expectedFirstTick);

        // Phase 6: Verify NO GAPS or unexpected OVERLAPS in combined chunk sequence
        // Combine initial chunks with resumed chunks and verify continuity
        List<TickDataChunk> allChunks = new ArrayList<>();
        allChunks.addAll(tickQueue.getCaptured());
        allChunks.addAll(resumeTickQueue.getCaptured());
        assertNoGapsOrUnexpectedOverlaps(allChunks, expectedFirstTick);
    }

    /**
     * Verifies that a list of chunks has no gaps and no unexpected overlaps.
     * <p>
     * Expected behavior after resume:
     * <ul>
     *   <li>Chunks should be contiguous (no gaps)</li>
     *   <li>The recaptured chunk may duplicate the last chunk from initial run (expected overlap)</li>
     *   <li>All other chunks should not overlap</li>
     * </ul>
     *
     * @param chunks all chunks in order of production
     * @param recapturedFirstTick the firstTick of the recaptured chunk (expected duplicate)
     */
    private void assertNoGapsOrUnexpectedOverlaps(List<TickDataChunk> chunks, long recapturedFirstTick) {
        if (chunks.size() < 2) {
            return; // Nothing to verify with 0 or 1 chunks
        }

        // Sort by firstTick to handle any ordering issues
        List<TickDataChunk> sorted = chunks.stream()
            .sorted((a, b) -> Long.compare(a.getFirstTick(), b.getFirstTick()))
            .toList();

        for (int i = 1; i < sorted.size(); i++) {
            TickDataChunk prev = sorted.get(i - 1);
            TickDataChunk curr = sorted.get(i);

            long expectedNextStart = prev.getLastTick() + 1;
            long actualStart = curr.getFirstTick();

            if (actualStart == prev.getFirstTick()) {
                // Duplicate chunk (recapture case) - verify it's the expected recaptured chunk
                assertThat(actualStart)
                    .as("Duplicate chunk at tick %d should be the recaptured chunk at tick %d",
                        actualStart, recapturedFirstTick)
                    .isEqualTo(recapturedFirstTick);
            } else if (actualStart < expectedNextStart) {
                // Unexpected overlap
                fail("Unexpected overlap: chunk [%d-%d] overlaps with chunk [%d-%d]",
                    prev.getFirstTick(), prev.getLastTick(),
                    curr.getFirstTick(), curr.getLastTick());
            } else if (actualStart > expectedNextStart) {
                // Gap detected
                fail("Gap detected: missing ticks %d-%d between chunk [%d-%d] and chunk [%d-%d]",
                    expectedNextStart, actualStart - 1,
                    prev.getFirstTick(), prev.getLastTick(),
                    curr.getFirstTick(), curr.getLastTick());
            }
            // else: actualStart == expectedNextStart → perfect continuity ✓
        }
    }

    /**
     * Runs a simulation for the specified number of ticks and captures output.
     */
    private String runSimulationAndCapture(
            CapturingQueue<TickDataChunk> tickQueue,
            CapturingQueue<SimulationMetadata> metadataQueue,
            int targetTicks) throws Exception {

        SimulationEngine engine = createNormalEngine(tickQueue, metadataQueue);

        // Start engine (AbstractService.start() creates its own thread)
        engine.start();

        // Wait until we have at least one chunk with accumulated deltas
        await().atMost(Duration.ofSeconds(30))
            .pollInterval(Duration.ofMillis(100))
            .until(() -> {
                long lastAccDelta = findLastAccumulatedDeltaTick(tickQueue.getCaptured());
                return lastAccDelta >= ACCUMULATED_DELTA_INTERVAL;
            });

        // Stop engine
        engine.stop();

        // Return the run ID from metadata
        assertThat(metadataQueue.getCaptured()).isNotEmpty();
        return metadataQueue.getCaptured().get(0).getSimulationRunId();
    }

    private SimulationEngine createNormalEngine(
            CapturingQueue<TickDataChunk> tickQueue,
            CapturingQueue<SimulationMetadata> metadataQueue) {

        Map<String, List<IResource>> resources = new HashMap<>();
        resources.put("tickData", List.of(tickQueue));
        resources.put("metadataOutput", List.of(metadataQueue));

        Config options = createEngineConfig(false, null);
        return new SimulationEngine("test-engine", options, resources);
    }

    private SimulationEngine createResumeEngine(
            String runId,
            CapturingQueue<TickDataChunk> tickQueue,
            CapturingQueue<SimulationMetadata> metadataQueue) {

        Map<String, List<IResource>> resources = new HashMap<>();
        resources.put("tickData", List.of(tickQueue));
        resources.put("metadataOutput", List.of(metadataQueue));
        resources.put("resumeStorage", List.of(storage));

        Config options = createEngineConfig(true, runId);
        return new SimulationEngine("resume-engine", options, resources);
    }

    private Config createEngineConfig(boolean isResume, String runId) {
        String configStr = """
            samplingInterval = 1
            accumulatedDeltaInterval = %d
            snapshotInterval = %d
            chunkInterval = %d
            metricsWindowSeconds = 1
            pauseTicks = []
            seed = 42
            environment {
                shape = [30, 30]
                topology = "TORUS"
            }
            organisms = [{
                program = "%s"
                initialEnergy = 10000
                placement { positions = [10, 10] }
            }]
            tickPlugins = []
            runtime {
                organism {
                    max-energy = 32767
                    max-entropy = 8191
                    error-penalty-cost = 10
                }
                thermodynamics {
                    default {
                        className = "org.evochora.runtime.thermodynamics.impl.UniversalThermodynamicPolicy"
                        options {
                            base-energy = 1
                            base-entropy = 1
                        }
                    }
                    overrides {
                        instructions = {}
                        families = {}
                    }
                }
            }
            """.formatted(
                ACCUMULATED_DELTA_INTERVAL,
                SNAPSHOT_INTERVAL,
                CHUNK_INTERVAL,
                programFile.toString().replace("\\", "/")
            );

        if (isResume) {
            configStr += """
                resume {
                    enabled = true
                    runId = "%s"
                }
                """.formatted(runId);
        }

        return ConfigFactory.parseString(configStr);
    }

    private long findLastAccumulatedDeltaTick(List<TickDataChunk> chunks) {
        long lastTick = 0;
        for (TickDataChunk chunk : chunks) {
            for (var delta : chunk.getDeltasList()) {
                if (delta.getDeltaType() == org.evochora.datapipeline.api.contracts.DeltaType.ACCUMULATED) {
                    lastTick = Math.max(lastTick, delta.getTickNumber());
                }
            }
        }
        return lastTick;
    }

    /**
     * A capturing queue that stores all messages for verification.
     */
    private static class CapturingQueue<T> implements IOutputQueueResource<T> {
        private final BlockingQueue<T> queue = new LinkedBlockingQueue<>();
        private final List<T> captured = new ArrayList<>();

        @Override
        public boolean offer(T message) {
            synchronized (captured) {
                captured.add(message);
            }
            return queue.offer(message);
        }

        @Override
        public void put(T message) throws InterruptedException {
            synchronized (captured) {
                captured.add(message);
            }
            queue.put(message);
        }

        @Override
        public boolean offer(T message, long timeout, TimeUnit unit) throws InterruptedException {
            synchronized (captured) {
                captured.add(message);
            }
            return queue.offer(message, timeout, unit);
        }

        @Override
        public void putAll(Collection<T> elements) throws InterruptedException {
            synchronized (captured) {
                captured.addAll(elements);
            }
            for (T element : elements) {
                queue.put(element);
            }
        }

        @Override
        public int offerAll(Collection<T> elements) {
            synchronized (captured) {
                captured.addAll(elements);
            }
            queue.addAll(elements);
            return elements.size();
        }

        public List<T> getCaptured() {
            synchronized (captured) {
                return new ArrayList<>(captured);
            }
        }

        @Override
        public String getResourceName() {
            return "capturing-queue";
        }

        @Override
        public UsageState getUsageState(String usageType) {
            return UsageState.ACTIVE;
        }
    }
}
