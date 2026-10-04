package org.evochora.datapipeline.resume;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.resources.storage.FileSystemStorageResource;
import org.evochora.datapipeline.resume.RecordingHarness.CapturingQueue;
import org.evochora.datapipeline.resume.RecordingHarness.Recording;
import org.evochora.datapipeline.services.SimulationEngine;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * Holds what a resumed run records to the requirement that it is what the run would have recorded
 * without the interruption.
 * <p>
 * A resume rebuilds the simulation from the snapshot of the last chunk in storage and records that
 * chunk again, then carries on. The simulation is deterministic, so every tick the resumed run
 * records has to equal the tick the uninterrupted run recorded — not only in the state that drives
 * the trajectory, which {@link ResumeNeutralityTest} compares on the simulation, but in everything
 * the persisted format carries. The difference matters for the bookkeeping that lives between two
 * recordings: a dead organism stays in the run's list until a recording has written it, and a
 * snapshot is such a recording. A resume that restored it would write the death a second time.
 * <p>
 * <strong>Why the comparison is complete.</strong> Both runs are decoded back into one
 * {@link TickData} per recorded tick and compared whole, with the dead organisms and the birth
 * mutation records left in. Only {@code capture_time_ms} is cleared: it is the wall clock at
 * capture and says nothing about the simulation. The run ID needs no clearing, since a resume
 * records under the ID of the run it continues.
 * <p>
 * <strong>Where the run is resumed.</strong> The checkpoint is the first chunk whose snapshot holds
 * a dead organism, so that the case the comparison exists for — a death the snapshot has already
 * written — is the one the resume starts from.
 */
@Tag("integration")
class ResumeRecordingNeutralityTest {

    /** The last tick both runs are required to have published in complete chunks. */
    private static final long COMPARED_UNTIL = 196;

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
        programFile = RecordingHarness.writeProgram(tempDir);
        Path storageDir = Files.createDirectories(tempDir.resolve("storage"));
        storage = new FileSystemStorageResource("resume-storage",
                ConfigFactory.parseMap(Map.of("rootDirectory", storageDir.toString())));
    }

    /**
     * The realistic case: the chunks hold a snapshot followed by incremental and accumulated
     * deltas, so the resumed run primes its encoder with the checkpoint's snapshot and writes its
     * first ticks as deltas against it.
     * <p>
     * Thirteen samples per accumulated delta and two accumulated deltas per snapshot make a chunk
     * span 26 ticks, which puts a chunk boundary on tick 26 — the tick one of the run's two deaths
     * falls on, so that the snapshot there holds the dead organism.
     */
    @Test
    void resumedRun_recordsWhatTheUninterruptedRunRecorded() throws Exception {
        assertResumeNeutral(1, 13, 2, 1);
    }

    /**
     * Runs the configuration uninterrupted, then again up to a checkpoint, resumes it from storage
     * and requires every tick the resumed run recorded to equal the uninterrupted run's.
     *
     * @param samplingInterval ticks between captures
     * @param accumulatedDeltaInterval samples between accumulated deltas
     * @param snapshotInterval accumulated deltas between snapshots
     * @param chunkInterval snapshots per chunk
     */
    private void assertResumeNeutral(int samplingInterval, int accumulatedDeltaInterval,
                                     int snapshotInterval, int chunkInterval) throws Exception {
        Config config = RecordingHarness.engineConfig(programFile, samplingInterval,
                accumulatedDeltaInterval, snapshotInterval, chunkInterval);

        CapturingQueue<TickDataChunk> referenceChunks = new CapturingQueue<>();
        CapturingQueue<SimulationMetadata> referenceMetadata = new CapturingQueue<>();
        RecordingHarness.runUntil(new SimulationEngine("uninterrupted", config,
                resources(referenceChunks, referenceMetadata, false)), referenceChunks, COMPARED_UNTIL);
        Recording reference = RecordingHarness.decode(referenceChunks.getCaptured());

        List<TickDataChunk> persisted = upToFirstSnapshotWithADeath(referenceChunks.getCaptured());
        SimulationMetadata metadata = referenceMetadata.getCaptured().get(0);
        String runId = metadata.getSimulationRunId();
        storage.writeMessage(runId + "/raw/metadata.pb", metadata);
        for (TickDataChunk chunk : persisted) {
            storage.writeChunkBatchStreaming(List.of(chunk).iterator());
        }

        CapturingQueue<TickDataChunk> resumedChunks = new CapturingQueue<>();
        Config resumeConfig = ConfigFactory.parseString("""
                resume {
                    enabled = true
                    runId = "%s"
                }
                """.formatted(runId)).withFallback(config);
        RecordingHarness.runUntil(new SimulationEngine("resumed", resumeConfig,
                resources(resumedChunks, new CapturingQueue<>(), true)), resumedChunks, COMPARED_UNTIL);
        Recording resumed = RecordingHarness.decode(resumedChunks.getCaptured());

        long checkpointTick = persisted.get(persisted.size() - 1).getFirstTick();
        assertThat(resumed.ticks().keySet().iterator().next())
                .as("the resumed run must start by recording the checkpoint's chunk again")
                .isEqualTo(checkpointTick);

        List<String> differences = new ArrayList<>();
        int compared = 0;
        for (Map.Entry<Long, TickData> entry : resumed.ticks().entrySet()) {
            long tick = entry.getKey();
            if (tick > COMPARED_UNTIL) {
                break;
            }
            compared++;
            TickData expected = reference.ticks().get(tick);
            if (expected == null) {
                differences.add("tick " + tick + ": recorded by the resumed run only");
                continue;
            }
            String difference = RecordingHarness.describeDifference(
                    expected.toBuilder().clearCaptureTimeMs().build(),
                    entry.getValue().toBuilder().clearCaptureTimeMs().build());
            if (!difference.isEmpty()) {
                differences.add("tick " + tick + ": " + difference);
            }
        }

        assertThat(compared)
                .as("the resumed run must record every tick from the checkpoint up to tick %d",
                        COMPARED_UNTIL)
                .isEqualTo(reference.ticks().keySet().stream()
                        .filter(tick -> tick >= checkpointTick && tick <= COMPARED_UNTIL).count());
        assertThat(RecordingHarness.reported(differences))
                .as("a resumed run must record what the uninterrupted run recorded; %d of the %d"
                        + " compared ticks differ", differences.size(), compared)
                .isEmpty();
    }

    /**
     * The chunks a run has to have persisted for its resume to start from a snapshot that holds a
     * dead organism: every chunk up to and including the first such one after the run's first.
     *
     * @param chunks the uninterrupted run's chunks, in order
     * @return the chunks to put into storage, the checkpoint last
     */
    private static List<TickDataChunk> upToFirstSnapshotWithADeath(List<TickDataChunk> chunks) {
        for (int i = 1; i < chunks.size(); i++) {
            if (chunks.get(i).getSnapshot().getTickNumber() > COMPARED_UNTIL) {
                break;
            }
            if (chunks.get(i).getSnapshot().getOrganismsList().stream().anyMatch(OrganismState::getIsDead)) {
                return chunks.subList(0, i + 1);
            }
        }
        throw new AssertionError("no chunk snapshot up to tick " + COMPARED_UNTIL + " holds a dead"
                + " organism, so a resume could not start from one; the run's deaths fall on ticks "
                + RecordingHarness.finalAppearances(decodeQuietly(chunks)).values().stream()
                        .map(appearances -> appearances.get(0).tick()).toList());
    }

    private static Recording decodeQuietly(List<TickDataChunk> chunks) {
        try {
            return RecordingHarness.decode(chunks);
        } catch (Exception e) {
            throw new AssertionError("the uninterrupted run's chunks cannot be decoded", e);
        }
    }

    private Map<String, List<IResource>> resources(CapturingQueue<TickDataChunk> tickData,
                                                   CapturingQueue<SimulationMetadata> metadata,
                                                   boolean resume) {
        Map<String, List<IResource>> resources = new HashMap<>();
        resources.put("tickData", List.of(tickData));
        resources.put("metadataOutput", List.of(metadata));
        if (resume) {
            resources.put("resumeStorage", List.of(storage));
        }
        return resources;
    }
}
