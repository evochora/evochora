package org.evochora.datapipeline.resume;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.evochora.BuildInfo;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.delta.ChunkCorruptedException;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.queues.IOutputQueueResource;
import org.evochora.datapipeline.resources.storage.FileSystemStorageResource;
import org.evochora.datapipeline.services.AbstractService;
import org.evochora.datapipeline.services.SimulationEngine;
import org.evochora.datapipeline.utils.delta.DeltaCodec;
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
 * A fork records a window of a coarsely sampled run again, densely, as a new run, and what it
 * records is what a dense run would have recorded there.
 * <p>
 * A parent run is recorded at every fourth tick and persisted. A fork of it is asked for the
 * ticks 45 to 100 at every tick. The fork must start at the parent's chunk boundary before 45,
 * end at the parent's chunk boundary after 100, pause there, announce itself with its own
 * metadata naming the parent and the window, and hold at every tick of the window exactly what
 * an uninterrupted run at every tick holds. Continuing the paused fork records the next chunk.
 * <p>
 * Asked for a first tick that lies further inside the parent's chunk, the fork begins its
 * recording on the boundary of its own chunks before that tick: it simulates its way there from
 * the checkpoint without recording, and what it records from there on is again what the run at
 * every tick recorded.
 */
@Tag("integration")
@ExtendWith(LogWatchExtension.class)
@AllowLog(level = LogLevel.WARN, loggerPattern = ".*SimulationEngine.*", messagePattern = "Run .* was written by build .* and is read by build .*")
class ForkEndToEndTest {

    private static final int WORLD_WIDTH = 64;
    private static final int WORLD_HEIGHT = 32;
    private static final int ACCUMULATED_DELTA_INTERVAL = 5;
    private static final int SNAPSHOT_INTERVAL = 2;
    private static final int CHUNK_INTERVAL = 1;
    /** Samples per chunk under the intervals above. */
    private static final int SAMPLES_PER_CHUNK = ACCUMULATED_DELTA_INTERVAL * SNAPSHOT_INTERVAL * CHUNK_INTERVAL;

    private static final int PARENT_SAMPLING = 4;
    private static final int FORK_SAMPLING = 1;
    /** Ticks per chunk of the parent: 4 × 10. */
    private static final long PARENT_TICKS_PER_CHUNK = (long) PARENT_SAMPLING * SAMPLES_PER_CHUNK;
    /** Ticks per chunk of the fork: 1 × 10. */
    private static final long FORK_TICKS_PER_CHUNK = (long) FORK_SAMPLING * SAMPLES_PER_CHUNK;

    private static final long REQUESTED_FROM = 45;
    private static final long REQUESTED_TO = 100;
    /** The parent's chunk boundary before the requested first tick. */
    private static final long WINDOW_FIRST = 40;
    /** The last tick before the parent's chunk boundary after the requested last tick. */
    private static final long WINDOW_LAST = 119;
    private static final int WINDOW_CHUNKS = 8;

    /** A requested first tick that lies inside the parent's chunk and off the fork's chunk grid. */
    private static final long OFF_GRID_FROM = 67;
    /** The boundary of the fork's chunks before that request, where its recording begins. */
    private static final long OFF_GRID_FIRST = 60;
    private static final int OFF_GRID_CHUNKS = 6;

    @TempDir
    Path tempDir;

    private Path programFile;
    private FileSystemStorageResource storage;

    @BeforeAll
    static void initInstructions() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() throws IOException {
        Path storageDir = tempDir.resolve("storage");
        Files.createDirectories(storageDir);

        Path sourceProgram = Path.of("src/test/resources/org/evochora/datapipeline/services/simple.evo");
        programFile = tempDir.resolve("simple.evo");
        Files.copy(sourceProgram, programFile, StandardCopyOption.REPLACE_EXISTING);

        storage = new FileSystemStorageResource("test-storage",
            ConfigFactory.parseMap(Map.of("rootDirectory", storageDir.toString())));
    }

    @Test
    void fork_RecordsTheWindowDenselyAsANewRunAndPausesAtItsEnd() throws Exception {
        SimulationMetadata parent = recordParentRun();
        String parentRunId = parent.getSimulationRunId();
        Map<Long, TickData> reference = recordReferenceRun();

        // The fork
        CapturingQueue<TickDataChunk> forkChunks = new CapturingQueue<>();
        CapturingQueue<SimulationMetadata> forkMetadata = new CapturingQueue<>();
        SimulationEngine fork = newEngine(FORK_SAMPLING, parentRunId, REQUESTED_FROM, forkChunks, forkMetadata);
        fork.start();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50))
            .until(() -> fork.getCurrentState() == AbstractService.State.PAUSED);

        // It pauses after the last tick of the window, with the window's chunks complete
        List<TickDataChunk> recorded = forkChunks.getCaptured();
        assertThat(recorded).hasSize(WINDOW_CHUNKS);
        for (int i = 0; i < WINDOW_CHUNKS; i++) {
            assertThat(recorded.get(i).getFirstTick()).isEqualTo(WINDOW_FIRST + i * FORK_TICKS_PER_CHUNK);
            assertThat(recorded.get(i).getTickCount()).isEqualTo(SAMPLES_PER_CHUNK);
        }
        assertThat(recorded.get(WINDOW_CHUNKS - 1).getLastTick()).isEqualTo(WINDOW_LAST);
        assertThat((WINDOW_LAST + 1 - WINDOW_FIRST) % PARENT_TICKS_PER_CHUNK).isZero();

        // It announces itself as a new run that names its parent and the rounded window
        assertThat(forkMetadata.getCaptured()).hasSize(1);
        SimulationMetadata metadata = forkMetadata.getCaptured().get(0);
        assertThat(metadata.getSimulationRunId()).isNotEqualTo(parentRunId);
        assertThat(metadata.getInitialSeed()).isEqualTo(parent.getInitialSeed());
        assertThat(metadata.getBuildRevision()).isEqualTo(BuildInfo.revision());
        assertThat(metadata.hasFork()).isTrue();
        assertThat(metadata.getFork().getParentRunId()).isEqualTo(parentRunId);
        assertThat(metadata.getFork().getFirstTick()).isEqualTo(WINDOW_FIRST);
        assertThat(metadata.getFork().getLastTick()).isEqualTo(WINDOW_LAST);
        assertThat(recorded.stream().allMatch(c -> c.getSimulationRunId().equals(metadata.getSimulationRunId()))).isTrue();
        assertThat(recorded.get(0).getSnapshot().getSimulationRunId())
            .as("the parent's snapshot becomes the fork's first tick and names the fork")
            .isEqualTo(metadata.getSimulationRunId());
        Config recordedConfig = ConfigFactory.parseString(metadata.getResolvedConfigJson());
        assertThat(recordedConfig.getInt("samplingInterval")).isEqualTo(FORK_SAMPLING);
        assertThat(recordedConfig.getConfigList("organisms")).hasSize(1);
        assertThat(parent.hasFork()).isFalse();

        // Every tick of the window is what the run at every tick recorded there
        Map<Long, TickData> window = decode(recorded);
        assertThat(window.keySet()).containsExactlyElementsOf(
            java.util.stream.LongStream.rangeClosed(WINDOW_FIRST, WINDOW_LAST).boxed().toList());
        List<Long> differing = new ArrayList<>();
        for (Map.Entry<Long, TickData> entry : window.entrySet()) {
            if (!comparable(entry.getValue()).equals(comparable(reference.get(entry.getKey())))) {
                differing.add(entry.getKey());
            }
        }
        assertThat(differing).as("ticks at which the fork differs from the run at every tick").isEmpty();

        // Continued, it records the chunk after the window
        fork.resume();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50))
            .until(() -> forkChunks.getCaptured().size() > WINDOW_CHUNKS);
        fork.stop();
        TickDataChunk next = forkChunks.getCaptured().get(WINDOW_CHUNKS);
        assertThat(next.getFirstTick()).isEqualTo(WINDOW_LAST + 1);
        assertThat(comparable(decode(List.of(next)).get(WINDOW_LAST + 1)))
            .isEqualTo(comparable(reference.get(WINDOW_LAST + 1)));
    }

    @Test
    void fork_AskedForATickOffItsChunkGrid_SimulatesToTheGridBeforeRecording() throws Exception {
        SimulationMetadata parent = recordParentRun();
        String parentRunId = parent.getSimulationRunId();
        Map<Long, TickData> reference = recordReferenceRun();

        CapturingQueue<TickDataChunk> forkChunks = new CapturingQueue<>();
        CapturingQueue<SimulationMetadata> forkMetadata = new CapturingQueue<>();
        SimulationEngine fork = newEngine(FORK_SAMPLING, parentRunId, OFF_GRID_FROM, forkChunks, forkMetadata);
        fork.start();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50))
            .until(() -> fork.getCurrentState() == AbstractService.State.PAUSED);
        fork.stop();

        // The recording begins on the fork's own chunk boundary before the requested tick, not at
        // the checkpoint: the ticks from the checkpoint to there were simulated without recording
        List<TickDataChunk> recorded = forkChunks.getCaptured();
        assertThat(recorded).hasSize(OFF_GRID_CHUNKS);
        assertThat(recorded.stream().map(TickDataChunk::getFirstTick))
            .as("no chunk before the fork's first recorded tick was published")
            .allMatch(first -> first >= OFF_GRID_FIRST);
        for (int i = 0; i < OFF_GRID_CHUNKS; i++) {
            assertThat(recorded.get(i).getFirstTick()).isEqualTo(OFF_GRID_FIRST + i * FORK_TICKS_PER_CHUNK);
            assertThat(recorded.get(i).getTickCount()).isEqualTo(SAMPLES_PER_CHUNK);
        }
        assertThat(recorded.get(0).getSnapshot().getTickNumber()).isEqualTo(OFF_GRID_FIRST);

        // It pauses at the same last tick as a fork asked for a tick on the grid
        assertThat(recorded.get(OFF_GRID_CHUNKS - 1).getLastTick()).isEqualTo(WINDOW_LAST);

        SimulationMetadata metadata = forkMetadata.getCaptured().get(0);
        assertThat(metadata.getFork().getParentRunId()).isEqualTo(parentRunId);
        assertThat(metadata.getFork().getFirstTick()).isEqualTo(OFF_GRID_FIRST);
        assertThat(metadata.getFork().getLastTick()).isEqualTo(WINDOW_LAST);

        // Every recorded tick is what the run at every tick recorded there
        Map<Long, TickData> window = decode(recorded);
        assertThat(window.keySet()).containsExactlyElementsOf(
            java.util.stream.LongStream.rangeClosed(OFF_GRID_FIRST, WINDOW_LAST).boxed().toList());
        List<Long> differing = new ArrayList<>();
        for (Map.Entry<Long, TickData> entry : window.entrySet()) {
            if (!comparable(entry.getValue()).equals(comparable(reference.get(entry.getKey())))) {
                differing.add(entry.getKey());
            }
        }
        assertThat(differing).as("ticks at which the fork differs from the run at every tick").isEmpty();
    }

    // ========================================================================
    // Running engines
    // ========================================================================

    /**
     * Records the parent run at every fourth tick and persists it as the pipeline would, so that a
     * fork can be restored from it.
     *
     * @return the parent's metadata
     */
    private SimulationMetadata recordParentRun() throws Exception {
        CapturingQueue<TickDataChunk> parentChunks = new CapturingQueue<>();
        CapturingQueue<SimulationMetadata> parentMetadata = new CapturingQueue<>();
        runUntil(newEngine(PARENT_SAMPLING, null, REQUESTED_FROM, parentChunks, parentMetadata), parentChunks, 4);
        SimulationMetadata parent = parentMetadata.getCaptured().get(0);
        storage.writeMessage(parent.getSimulationRunId() + "/raw/metadata.pb", parent);
        for (TickDataChunk chunk : parentChunks.getCaptured()) {
            storage.writeChunkBatchStreaming(List.of(chunk).iterator());
        }
        return parent;
    }

    /**
     * Records the same run at every tick, far enough to cover the window and the chunk after it.
     *
     * @return the recorded ticks by tick number
     */
    private Map<Long, TickData> recordReferenceRun() throws Exception {
        CapturingQueue<TickDataChunk> referenceChunks = new CapturingQueue<>();
        runUntil(newEngine(FORK_SAMPLING, null, REQUESTED_FROM, referenceChunks, new CapturingQueue<>()),
            referenceChunks, WINDOW_CHUNKS + 5);
        return decode(referenceChunks.getCaptured());
    }

    private void runUntil(SimulationEngine engine, CapturingQueue<TickDataChunk> chunks, int chunkCount) {
        engine.start();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50))
            .until(() -> chunks.getCaptured().size() >= chunkCount);
        engine.stop();
    }

    /**
     * Builds an engine for a new run, or for a fork of the given parent when a parent is named.
     */
    private SimulationEngine newEngine(int samplingInterval, String parentRunId, long fromTick,
                                       CapturingQueue<TickDataChunk> chunks,
                                       CapturingQueue<SimulationMetadata> metadata) {
        Map<String, List<IResource>> resources = new HashMap<>();
        resources.put("tickData", List.of(chunks));
        resources.put("metadataOutput", List.of(metadata));
        if (parentRunId != null) {
            resources.put("resumeStorage", List.of(storage));
        }
        return new SimulationEngine("engine", engineConfig(samplingInterval, parentRunId, fromTick), resources);
    }

    private Config engineConfig(int samplingInterval, String parentRunId, long fromTick) {
        String config = """
            samplingInterval = %d
            accumulatedDeltaInterval = %d
            snapshotInterval = %d
            chunkInterval = %d
            metricsWindowSeconds = 1
            pauseTicks = []
            seed = 42
            environment {
                shape = [%d, %d]
                topology = "TORUS"
            }
            organisms = [{
                program = "%s"
                initialEnergy = 10000
                placement { positions = [10, 10] }
            }]
            plugins = []
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
            """.formatted(samplingInterval, ACCUMULATED_DELTA_INTERVAL, SNAPSHOT_INTERVAL, CHUNK_INTERVAL,
                WORLD_WIDTH, WORLD_HEIGHT, programFile.toString().replace("\\", "/"));
        if (parentRunId != null) {
            config += """
                resume {
                    enabled = true
                    runId = "%s"
                    fork {
                        fromTick = %d
                        toTick = %d
                    }
                }
                """.formatted(parentRunId, fromTick, REQUESTED_TO);
        }
        return ConfigFactory.parseString(config);
    }

    // ========================================================================
    // Comparing recordings
    // ========================================================================

    private static Map<Long, TickData> decode(List<TickDataChunk> chunks) throws ChunkCorruptedException {
        DeltaCodec.Decoder decoder = new DeltaCodec.Decoder(WORLD_WIDTH * WORLD_HEIGHT);
        Map<Long, TickData> ticks = new TreeMap<>();
        for (TickDataChunk chunk : chunks) {
            for (TickData tick : decoder.decompressChunk(chunk)) {
                ticks.put(tick.getTickNumber(), tick);
            }
        }
        return ticks;
    }

    /**
     * The tick without the two fields that differ between any two runs by construction: the run
     * ID, drawn per run, and the capture time, the wall clock of the machine.
     */
    private static TickData comparable(TickData tick) {
        return tick.toBuilder().clearSimulationRunId().clearCaptureTimeMs().build();
    }

    /** Collects every message an engine puts on a queue. */
    private static final class CapturingQueue<T> implements IOutputQueueResource<T> {
        private final BlockingQueue<T> queue = new LinkedBlockingQueue<>();
        private final List<T> captured = new ArrayList<>();

        @Override
        public boolean offer(T message) {
            capture(List.of(message));
            return queue.offer(message);
        }

        @Override
        public void put(T message) throws InterruptedException {
            capture(List.of(message));
            queue.put(message);
        }

        @Override
        public boolean offer(T message, long timeout, TimeUnit unit) throws InterruptedException {
            capture(List.of(message));
            return queue.offer(message, timeout, unit);
        }

        @Override
        public void putAll(Collection<T> elements) throws InterruptedException {
            capture(elements);
            for (T element : elements) {
                queue.put(element);
            }
        }

        @Override
        public int offerAll(Collection<T> elements) {
            capture(elements);
            queue.addAll(elements);
            return elements.size();
        }

        private void capture(Collection<T> elements) {
            synchronized (captured) {
                captured.addAll(elements);
            }
        }

        List<T> getCaptured() {
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
