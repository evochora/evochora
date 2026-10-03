package org.evochora.datapipeline.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.evochora.datapipeline.resume.RecordingHarness.describeDifference;
import static org.evochora.datapipeline.resume.RecordingHarness.differingFields;
import static org.evochora.datapipeline.resume.RecordingHarness.finalAppearances;
import static org.evochora.datapipeline.resume.RecordingHarness.mutatedBirths;
import static org.evochora.datapipeline.resume.RecordingHarness.reported;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.delta.ChunkCorruptedException;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.resume.RecordingHarness.CapturingQueue;
import org.evochora.datapipeline.resume.RecordingHarness.FinalAppearance;
import org.evochora.datapipeline.resume.RecordingHarness.MutatedBirth;
import org.evochora.datapipeline.resume.RecordingHarness.Recording;
import org.evochora.datapipeline.services.SimulationEngine;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds what a run persists at a tick to the requirement that it does not depend on how densely
 * the run was sampled.
 * <p>
 * {@link SimulationEngine} advances the simulation tick by tick and hands every
 * {@code samplingInterval}-th tick to the chunk encoder. Only the capture hangs on that interval:
 * the tick loop itself, the random provider and the plugins run on every tick regardless. Two runs
 * from the same configuration and the same seed that differ only in {@code samplingInterval} must
 * therefore describe every tick they both captured identically — otherwise a window of a run cannot
 * be recorded again at a denser sampling and compared with what the original run wrote.
 * <p>
 * <strong>Why the comparison is complete.</strong> Both runs are decoded back into one
 * {@link TickData} per sampled tick, which is everything the persisted format carries: every
 * occupied cell, the whole state of every organism, the random provider's bytes, the plugin states
 * and the run counters. The two messages are compared whole. Two fields are cleared before the
 * comparison because they cannot
 * agree by construction and say nothing about the trajectory: {@code simulation_run_id}, which is
 * generated per run, and {@code capture_time_ms}, which is the wall clock at capture. Everything
 * else is compared, and a difference in any field is reported by name.
 * <p>
 * <strong>The two carried-over bookkeepings.</strong> A recording writes nothing between two
 * samples, so an event that falls between them can only reach the data at the first sample from
 * the event on. Two pieces of state work that way, and both are therefore taken out of the
 * tick-by-tick comparison and checked by an assertion of their own:
 * <ul>
 *   <li>the final appearance of a dead organism — the run keeps a dead organism in its list until
 *       a sample has written it, so the tick it appears at is the first sample at or after the
 *       death, not the death itself;</li>
 *   <li>{@code birth_mutations} — a newborn carries what the mutation plugins did to it until a
 *       sample has written it, so those records likewise sit on the first sample at or after the
 *       birth.</li>
 * </ul>
 * Which tick carries them is a property of the sampling grid, not of the simulation. What is
 * required of them is that each death and each birth reaches the data exactly once per run, at the
 * tick the grid predicts, and with the same content — this is what
 * {@link #assertDeathsRecordedOnce} and {@link #assertBirthMutationsRecordedOnce} state.
 * <p>
 * <strong>The snapshot-only fields.</strong> The encoder writes the random state, the plugin states
 * and the set of all genomes ever seen into a chunk's snapshot only, not into its deltas — a resume
 * always starts at a chunk boundary. A tick that is a snapshot in one run and a delta in the other
 * therefore carries these fields on one side only, which is a property of the capture schedule and
 * not of the state. {@link #persistedTicks_matchWhenEverySampleIsASnapshot()} removes that gap: with
 * every interval set to one, every sampled tick is its own chunk and its own snapshot in both runs,
 * so all three fields take part in the comparison at every compared tick.
 */
@Tag("integration")
class SamplingNeutralityTest {

    /** The dense run captures every tick. */
    private static final int DENSE_INTERVAL = 1;

    /** The sparse run captures every fourth tick; its samples are a subset of the dense run's. */
    private static final int SPARSE_INTERVAL = 4;

    /**
     * The last tick both runs are required to have captured in complete chunks.
     * <p>
     * It is a multiple of the sparse interval and of the ticks a sparse chunk spans under either
     * interval setting used here, so both runs end the compared range on a chunk boundary and no
     * comparison depends on a partial chunk — the engine never publishes one.
     */
    private static final int COMPARED_UNTIL = 196;

    /**
     * The last tick an event may happen on and still be recorded by both runs within the range both
     * of them cover. The sparse run writes an event at the first sample at or after it, which lies
     * at most {@link #SPARSE_INTERVAL} minus one tick later.
     */
    private static final long LAST_EVENT_BOTH_RUNS_RECORD = COMPARED_UNTIL - SPARSE_INTERVAL + 1L;

    /**
     * The fields of a dead organism's final appearance that the sampling grid cannot move.
     * <p>
     * The rest of the state may legitimately differ between the two runs: the runtime collects an
     * instruction's register values only on sampled ticks, and the preview of the next instruction
     * is read from the world at capture time, which for a dead organism has moved on by the time
     * the sparse run gets to it. What an organism <em>is</em> — who it is, where it came from, when
     * it was born, when it died, what it ran and what genome it carried — is fixed at its death and
     * has to agree.
     */
    private static final List<String> IDENTITY_AT_DEATH = List.of(
            "organism_id", "parent_id", "birth_tick", "death_tick", "is_dead",
            "program_id", "initial_position", "genome_hash", "parent_genome_hash", "generation");

    @TempDir
    Path tempDir;

    private Path programFile;

    @BeforeAll
    static void initInstructions() {
        Instruction.init();
    }

    @BeforeEach
    void writeProgram() throws IOException {
        programFile = RecordingHarness.writeProgram(tempDir);
    }

    /**
     * The realistic case: the run is persisted as a snapshot followed by incremental and
     * accumulated deltas, and the comparison sees the ticks as a reader reconstructs them.
     */
    @Test
    void persistedTicks_matchUnderDeltaEncoding() throws Exception {
        assertSamplingNeutral(5, 2, 1);
    }

    /**
     * Every sampled tick is its own chunk and therefore its own snapshot, which is the only
     * schedule under which the random state, the plugin states and the set of genomes ever seen
     * are written for every sampled tick. The comparison then covers every field of every tick.
     */
    @Test
    void persistedTicks_matchWhenEverySampleIsASnapshot() throws Exception {
        assertSamplingNeutral(1, 1, 1);
    }

    /**
     * Runs the same configuration twice, once sampling every tick and once every fourth, and
     * requires the two recordings to agree on every tick both of them captured.
     *
     * @param accumulatedDeltaInterval samples between accumulated deltas
     * @param snapshotInterval accumulated deltas between snapshots
     * @param chunkInterval snapshots per chunk
     */
    private void assertSamplingNeutral(int accumulatedDeltaInterval, int snapshotInterval, int chunkInterval)
            throws Exception {
        Recording dense = record(DENSE_INTERVAL, accumulatedDeltaInterval, snapshotInterval, chunkInterval);
        Recording sparse = record(SPARSE_INTERVAL, accumulatedDeltaInterval, snapshotInterval, chunkInterval);

        List<Long> compared = dense.ticks().keySet().stream()
                .filter(tick -> tick <= COMPARED_UNTIL && sparse.ticks().containsKey(tick))
                .sorted()
                .toList();

        assertThat(compared)
                .as("the runs must share the ticks the sparse one sampled, up to tick %d", COMPARED_UNTIL)
                .hasSize(COMPARED_UNTIL / SPARSE_INTERVAL + 1);
        assertWorthComparing(dense);

        // Every compared tick is examined rather than only the first that differs: a divergence
        // that shows up once around a birth and one that shows up at every tick of the run are
        // different findings, and the failure message has to tell them apart.
        List<String> differences = new ArrayList<>();
        for (long tick : compared) {
            boolean bothSnapshots = dense.snapshotTicks().contains(tick) && sparse.snapshotTicks().contains(tick);
            String difference = describeDifference(
                    comparable(dense.ticks().get(tick), bothSnapshots),
                    comparable(sparse.ticks().get(tick), bothSnapshots));
            if (!difference.isEmpty()) {
                differences.add("tick " + tick + ": " + difference);
            }
        }

        assertThat(reported(differences))
                .as("the state persisted for a tick must be the same whether the run sampled every"
                        + " tick or every %d ticks; %d of the %d compared ticks differ",
                        SPARSE_INTERVAL, differences.size(), compared.size())
                .isEmpty();

        assertDeathsRecordedOnce(dense, sparse);
        assertBirthMutationsRecordedOnce(dense, sparse);
    }

    /**
     * Requires every death to reach both recordings exactly once, at the tick the sampling grid
     * predicts, describing the same organism.
     * <p>
     * A dead organism stays in the run's organism list until a sample has written it and is dropped
     * right after, so each death produces exactly one entry with {@code is_dead} set — in the dense
     * run on the death tick itself, in the sparse run on the first of its samples at or after it. A
     * death that reached one recording twice, or not at all, or under a different death tick, would
     * make a window recorded again at a denser sampling disagree with the original run about who
     * died and when.
     *
     * @param dense the recording that sampled every tick
     * @param sparse the recording that sampled every {@link #SPARSE_INTERVAL} ticks
     */
    private static void assertDeathsRecordedOnce(Recording dense, Recording sparse) {
        Map<Integer, List<FinalAppearance>> denseDeaths = finalAppearances(dense);
        Map<Integer, List<FinalAppearance>> sparseDeaths = finalAppearances(sparse);

        Set<Integer> died = new TreeSet<>(denseDeaths.keySet());
        died.addAll(sparseDeaths.keySet());
        died.removeIf(id -> deathTick(denseDeaths, sparseDeaths, id) > LAST_EVENT_BOTH_RUNS_RECORD);

        assertThat(died)
                .as("the runs must contain a death, otherwise this assertion states nothing")
                .isNotEmpty();

        for (int id : died) {
            long deathTick = deathTick(denseDeaths, sparseDeaths, id);
            List<FinalAppearance> denseOnes = denseDeaths.getOrDefault(id, List.of());
            List<FinalAppearance> sparseOnes = sparseDeaths.getOrDefault(id, List.of());

            assertThat(ticksOf(denseOnes))
                    .as("organism %d died at tick %d and must appear dead exactly once in the dense"
                            + " recording", id, deathTick)
                    .containsExactly(firstSampleAtOrAfter(deathTick, DENSE_INTERVAL));
            assertThat(ticksOf(sparseOnes))
                    .as("organism %d died at tick %d and must appear dead exactly once in the sparse"
                            + " recording, at the first sample from the death on", id, deathTick)
                    .containsExactly(firstSampleAtOrAfter(deathTick, SPARSE_INTERVAL));

            OrganismState fromDense = denseOnes.get(0).state();
            OrganismState fromSparse = sparseOnes.get(0).state();
            assertThat(differingFields(fromDense, fromSparse, IDENTITY_AT_DEATH))
                    .as("the final appearance of organism %d must describe the same organism in both"
                            + " recordings", id)
                    .isEmpty();
        }
    }

    /**
     * Requires the mutation records of every birth to reach both recordings exactly once, at the
     * tick the sampling grid predicts, with the same events.
     * <p>
     * A newborn carries what the mutation plugins did to it until a sample has written it, and the
     * run drops the records right after. What the plugins did is the part of a birth a later
     * recording cannot reconstruct, so a window recorded again has to find the same events, in the
     * same order, at the one tick its own grid puts them on.
     *
     * @param dense the recording that sampled every tick
     * @param sparse the recording that sampled every {@link #SPARSE_INTERVAL} ticks
     */
    private static void assertBirthMutationsRecordedOnce(Recording dense, Recording sparse) {
        Map<Integer, List<MutatedBirth>> denseBirths = mutatedBirths(dense);
        Map<Integer, List<MutatedBirth>> sparseBirths = mutatedBirths(sparse);

        Set<Integer> born = new TreeSet<>(denseBirths.keySet());
        born.addAll(sparseBirths.keySet());
        born.removeIf(id -> birthTick(denseBirths, sparseBirths, id) > LAST_EVENT_BOTH_RUNS_RECORD);

        assertThat(born)
                .as("the runs must contain a birth the mutation plugins acted on, otherwise this"
                        + " assertion states nothing")
                .isNotEmpty();

        for (int id : born) {
            long birthTick = birthTick(denseBirths, sparseBirths, id);
            List<MutatedBirth> denseOnes = denseBirths.getOrDefault(id, List.of());
            List<MutatedBirth> sparseOnes = sparseBirths.getOrDefault(id, List.of());

            assertThat(denseOnes.stream().map(MutatedBirth::tick).toList())
                    .as("organism %d was born at tick %d and its mutation records must appear exactly"
                            + " once in the dense recording", id, birthTick)
                    .containsExactly(firstSampleAtOrAfter(birthTick, DENSE_INTERVAL));
            assertThat(sparseOnes.stream().map(MutatedBirth::tick).toList())
                    .as("organism %d was born at tick %d and its mutation records must appear exactly"
                            + " once in the sparse recording, at the first sample from the birth on",
                            id, birthTick)
                    .containsExactly(firstSampleAtOrAfter(birthTick, SPARSE_INTERVAL));

            assertThat(sparseOnes.get(0).events())
                    .as("the mutation records of organism %d must hold the same events in both"
                            + " recordings", id)
                    .isEqualTo(denseOnes.get(0).events());
        }
    }

    /** The first tick a run sampling every {@code interval} ticks captures at or after {@code tick}. */
    private static long firstSampleAtOrAfter(long tick, int interval) {
        return Math.ceilDiv(tick, interval) * (long) interval;
    }

    /**
     * The tick an organism died on, taken from whichever recording holds its final appearance. Both
     * are consulted, so that an organism only one of them wrote is still named in the assertion that
     * then fails, rather than disappearing from the checked set.
     */
    private static long deathTick(Map<Integer, List<FinalAppearance>> dense,
                                  Map<Integer, List<FinalAppearance>> sparse, int id) {
        List<FinalAppearance> appearances = dense.containsKey(id) ? dense.get(id) : sparse.get(id);
        return appearances.get(0).state().getDeathTick();
    }

    /** The tick an organism was born on, taken from whichever recording holds its mutation records. */
    private static long birthTick(Map<Integer, List<MutatedBirth>> dense,
                                  Map<Integer, List<MutatedBirth>> sparse, int id) {
        List<MutatedBirth> births = dense.containsKey(id) ? dense.get(id) : sparse.get(id);
        return births.get(0).birthTick();
    }

    private static List<Long> ticksOf(List<FinalAppearance> appearances) {
        return appearances.stream().map(FinalAppearance::tick).toList();
    }

    /**
     * Guards the comparison against passing on a run in which nothing happens. A run without a
     * birth leaves the mutation plugins idle, and a run without a death never puts an organism into
     * the recording in its final, dead appearance — the two cases in which the capture does more
     * than copy a state that was going to be identical anyway.
     */
    private void assertWorthComparing(Recording dense) {
        long founders = dense.ticks().get(0L).getTotalOrganismsCreated();
        long created = dense.ticks().get((long) COMPARED_UNTIL).getTotalOrganismsCreated();
        assertThat(created)
                .as("the run must reproduce, so that the birth handlers act within the compared range")
                .isGreaterThan(founders);

        boolean died = dense.ticks().entrySet().stream()
                .filter(entry -> entry.getKey() <= COMPARED_UNTIL)
                .flatMap(entry -> entry.getValue().getOrganismsList().stream())
                .anyMatch(OrganismState::getIsDead);
        assertThat(died)
                .as("an organism must die within the compared range, so that a dead organism's final"
                        + " appearance takes part in the comparison")
                .isTrue();
    }

    /**
     * The part of a tick two runs can be held against each other tick by tick.
     * <p>
     * The run ID is drawn per run and the capture time is the wall clock of the machine; neither
     * describes the simulation. The snapshot-only fields are cleared when the tick is a snapshot in
     * only one of the two runs, because the encoder writes them into snapshots alone.
     * <p>
     * The dead organisms are dropped and {@code birth_mutations} is cleared on the living ones. A
     * recording writes nothing between two samples, so a death and a newborn's mutation records
     * reach the data at the first sample from the event on and stay in the run's memory until then:
     * which tick carries them follows the sampling grid, not the simulation. That they reach each
     * recording exactly once, on the tick the grid predicts, and say the same thing is required by
     * {@link #assertDeathsRecordedOnce} and {@link #assertBirthMutationsRecordedOnce}.
     *
     * @param tick the reconstructed tick
     * @param bothSnapshots whether both runs captured this tick as a chunk snapshot
     * @return the tick with the parts that the sampling grid places cleared
     */
    private static TickData comparable(TickData tick, boolean bothSnapshots) {
        TickData.Builder builder = tick.toBuilder()
                .clearSimulationRunId()
                .clearCaptureTimeMs()
                .clearOrganisms();
        for (OrganismState organism : tick.getOrganismsList()) {
            if (!organism.getIsDead()) {
                builder.addOrganisms(organism.toBuilder().clearBirthMutations());
            }
        }
        if (!bothSnapshots) {
            builder.clearRngState().clearPluginStates().clearAllGenomeHashesEverSeen();
        }
        return builder.build();
    }

    // ========================================================================
    // Running an engine and decoding what it published
    // ========================================================================

    /**
     * Runs an engine until its published chunks cover the compared range, then decodes them.
     *
     * @param samplingInterval ticks between captures
     * @param accumulatedDeltaInterval samples between accumulated deltas
     * @param snapshotInterval accumulated deltas between snapshots
     * @param chunkInterval snapshots per chunk
     * @return the run's recording
     */
    private Recording record(int samplingInterval, int accumulatedDeltaInterval,
                             int snapshotInterval, int chunkInterval) throws ChunkCorruptedException {
        CapturingQueue<TickDataChunk> tickData = new CapturingQueue<>();
        CapturingQueue<SimulationMetadata> metadata = new CapturingQueue<>();
        Map<String, List<IResource>> resources = new HashMap<>();
        resources.put("tickData", List.of(tickData));
        resources.put("metadataOutput", List.of(metadata));

        SimulationEngine engine = new SimulationEngine("sampling-" + samplingInterval,
                RecordingHarness.engineConfig(programFile, samplingInterval, accumulatedDeltaInterval,
                        snapshotInterval, chunkInterval),
                resources);
        RecordingHarness.runUntil(engine, tickData, COMPARED_UNTIL);
        return RecordingHarness.decode(tickData.getCaptured());
    }
}
