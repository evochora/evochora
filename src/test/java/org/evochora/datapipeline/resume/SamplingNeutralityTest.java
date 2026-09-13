package org.evochora.datapipeline.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.MutationEvent;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.delta.ChunkCorruptedException;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.queues.IOutputQueueResource;
import org.evochora.datapipeline.services.SimulationEngine;
import org.evochora.datapipeline.utils.delta.DeltaCodec;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

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

    /** Edge length of the square world. Wide enough for the program to lie in a single row. */
    private static final int WORLD_SIZE = 96;

    private static final long SEED = 42L;

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

    /** Energy of the parent: enough to lay out its genome, fork and then idle for the whole run. */
    private static final int PARENT_ENERGY = 30_000;

    /** Energy of the second founder: too little to finish its program, so it dies during the run. */
    private static final int STARVING_ENERGY = 30;

    /** How many diverging ticks a failure names before it only counts the rest. */
    private static final int REPORTED_DIFFERENCES = 10;

    @TempDir
    Path tempDir;

    private Path programFile;

    @BeforeAll
    static void initInstructions() {
        Instruction.init();
    }

    @BeforeEach
    void writeProgram() throws IOException {
        programFile = tempDir.resolve("sampling-neutrality.evo");
        Files.writeString(programFile, PROGRAM);
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

    /** A dead organism as one recording wrote it, with the tick it was written at. */
    private record FinalAppearance(long tick, OrganismState state) {}

    /** The mutation records of one birth as one recording wrote them, with the tick and birth tick. */
    private record MutatedBirth(long tick, long birthTick, List<MutationEvent> events) {}

    /** Every appearance of a dead organism in a recording, by organism, in tick order. */
    private static Map<Integer, List<FinalAppearance>> finalAppearances(Recording run) {
        Map<Integer, List<FinalAppearance>> byId = new TreeMap<>();
        for (Map.Entry<Long, TickData> entry : run.ticks().entrySet()) {
            for (OrganismState organism : entry.getValue().getOrganismsList()) {
                if (organism.getIsDead()) {
                    byId.computeIfAbsent(organism.getOrganismId(), id -> new ArrayList<>())
                            .add(new FinalAppearance(entry.getKey(), organism));
                }
            }
        }
        return byId;
    }

    /** Every appearance of birth mutation records in a recording, by organism, in tick order. */
    private static Map<Integer, List<MutatedBirth>> mutatedBirths(Recording run) {
        Map<Integer, List<MutatedBirth>> byId = new TreeMap<>();
        for (Map.Entry<Long, TickData> entry : run.ticks().entrySet()) {
            for (OrganismState organism : entry.getValue().getOrganismsList()) {
                if (organism.getBirthMutationsCount() > 0) {
                    byId.computeIfAbsent(organism.getOrganismId(), id -> new ArrayList<>())
                            .add(new MutatedBirth(entry.getKey(), organism.getBirthTick(),
                                    organism.getBirthMutationsList()));
                }
            }
        }
        return byId;
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
     * The named fields in which two messages of the same type differ, each with both values. A
     * field one message sets and the other leaves absent counts as differing, so a difference in
     * presence is not lost behind two equal default values.
     */
    private static List<String> differingFields(Message expected, Message actual, List<String> names) {
        List<String> differences = new ArrayList<>();
        for (String name : names) {
            Descriptors.FieldDescriptor field = expected.getDescriptorForType().findFieldByName(name);
            assertThat(field).as("the comparison names the field %s, which the message must have", name)
                    .isNotNull();
            if (field.hasPresence() && expected.hasField(field) != actual.hasField(field)) {
                differences.add("%s: set in the %s recording only".formatted(
                        name, expected.hasField(field) ? "dense" : "sparse"));
            } else if (!expected.getField(field).equals(actual.getField(field))) {
                differences.add("%s: dense recording has %s, sparse recording has %s".formatted(
                        name, abbreviate(expected.getField(field)), abbreviate(actual.getField(field))));
            }
        }
        return differences;
    }

    /**
     * The differences a failure prints. A run that diverges at every tick would otherwise produce a
     * message of tens of thousands of characters, in which the first entries — the ones that say
     * where the divergence begins — are the only ones that carry information.
     */
    private static List<String> reported(List<String> differences) {
        if (differences.size() <= REPORTED_DIFFERENCES) {
            return differences;
        }
        List<String> reported = new ArrayList<>(differences.subList(0, REPORTED_DIFFERENCES));
        reported.add("… and " + (differences.size() - REPORTED_DIFFERENCES) + " further ticks");
        return reported;
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

    /** One run's recording: the reconstructed ticks and which of them were chunk snapshots. */
    private record Recording(Map<Long, TickData> ticks, Set<Long> snapshotTicks) {}

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
                engineConfig(samplingInterval, accumulatedDeltaInterval, snapshotInterval, chunkInterval),
                resources);
        engine.start();
        try {
            await().atMost(Duration.ofSeconds(120))
                    .pollInterval(Duration.ofMillis(50))
                    .until(() -> lastCoveredTick(tickData.getCaptured()) >= COMPARED_UNTIL);
        } finally {
            engine.stop();
        }
        return decode(tickData.getCaptured());
    }

    /** The last tick the published chunks cover without a gap. */
    private static long lastCoveredTick(List<TickDataChunk> chunks) {
        return chunks.isEmpty() ? -1 : chunks.get(chunks.size() - 1).getLastTick();
    }

    /**
     * Reconstructs every tick the chunks carry and records which ticks the chunks hold as
     * snapshots, since only those carry the random state and the plugin states.
     */
    private static Recording decode(List<TickDataChunk> chunks) throws ChunkCorruptedException {
        DeltaCodec.Decoder decoder = new DeltaCodec.Decoder(WORLD_SIZE * WORLD_SIZE);
        Map<Long, TickData> ticks = new TreeMap<>();
        Set<Long> snapshotTicks = new LinkedHashSet<>();
        for (TickDataChunk chunk : chunks) {
            snapshotTicks.add(chunk.getSnapshot().getTickNumber());
            for (TickData tick : decoder.decompressChunk(chunk)) {
                ticks.put(tick.getTickNumber(), tick);
            }
        }
        return new Recording(ticks, snapshotTicks);
    }

    // ========================================================================
    // Reporting a difference
    // ========================================================================

    /**
     * Names the first place two reconstructed ticks differ, or an empty string when they are equal.
     * <p>
     * A {@link TickData} of a populated world prints as thousands of lines, so an equality failure
     * on the whole message would hide the one field that moved. The check starts from whole-message
     * equality and only then narrows down, so nothing is left out: every field of the message is
     * either drilled into here or reported by name by {@link #firstDifferingField}.
     *
     * @param expected the tick as the dense run recorded it
     * @param actual the tick as the sparse run recorded it
     * @return a description of the first difference, empty when the messages are equal
     */
    private static String describeDifference(TickData expected, TickData actual) {
        if (expected.equals(actual)) {
            return "";
        }
        if (!expected.getOrganismsList().equals(actual.getOrganismsList())) {
            return describeOrganismDifference(expected.getOrganismsList(), actual.getOrganismsList());
        }
        if (!expected.getCellColumns().equals(actual.getCellColumns())) {
            return describeCellDifference(expected.getCellColumns(), actual.getCellColumns());
        }
        String field = firstDifferingField(expected, actual);
        return field.isEmpty() ? "the messages differ in a field this comparison does not name" : field;
    }

    /** Names the organism and the field of it that differs, or which organism only one run holds. */
    private static String describeOrganismDifference(List<OrganismState> expected, List<OrganismState> actual) {
        Map<Integer, OrganismState> byIdExpected = byId(expected);
        Map<Integer, OrganismState> byIdActual = byId(actual);
        for (Map.Entry<Integer, OrganismState> entry : byIdExpected.entrySet()) {
            OrganismState other = byIdActual.get(entry.getKey());
            if (other == null) {
                return "organism %d is in the dense recording only (dead=%s)"
                        .formatted(entry.getKey(), entry.getValue().getIsDead());
            }
            if (!entry.getValue().equals(other)) {
                return "organism %d differs in %s"
                        .formatted(entry.getKey(), firstDifferingField(entry.getValue(), other));
            }
        }
        for (Integer id : byIdActual.keySet()) {
            if (!byIdExpected.containsKey(id)) {
                return "organism %d is in the sparse recording only (dead=%s)"
                        .formatted(id, byIdActual.get(id).getIsDead());
            }
        }
        return "the organism lists hold the same organisms in a different order";
    }

    private static Map<Integer, OrganismState> byId(List<OrganismState> organisms) {
        Map<Integer, OrganismState> byId = new TreeMap<>();
        for (OrganismState organism : organisms) {
            byId.put(organism.getOrganismId(), organism);
        }
        return byId;
    }

    /** Names the first cell whose molecule or owner differs, under its flat index. */
    private static String describeCellDifference(CellDataColumns expected, CellDataColumns actual) {
        Map<Integer, String> expectedCells = cells(expected);
        Map<Integer, String> actualCells = cells(actual);
        Set<Integer> indices = new TreeSet<>(expectedCells.keySet());
        indices.addAll(actualCells.keySet());
        for (int index : indices) {
            String want = expectedCells.getOrDefault(index, "empty");
            String got = actualCells.getOrDefault(index, "empty");
            if (!want.equals(got)) {
                return "cell %d: dense recording has %s, sparse recording has %s".formatted(index, want, got);
            }
        }
        return "the cell columns differ in their order, not in their content";
    }

    private static Map<Integer, String> cells(CellDataColumns columns) {
        Map<Integer, String> cells = new TreeMap<>();
        for (int i = 0; i < columns.getFlatIndicesCount(); i++) {
            cells.put(columns.getFlatIndices(i),
                    columns.getMoleculeData(i) + "/" + columns.getOwnerIds(i));
        }
        return cells;
    }

    /**
     * The first field of two messages of the same type that does not hold the same value, named
     * with both values. A field that one message sets and the other leaves absent is reported as
     * such, so a difference in presence is not lost behind two equal default values.
     */
    private static String firstDifferingField(Message expected, Message actual) {
        for (Descriptors.FieldDescriptor field : expected.getDescriptorForType().getFields()) {
            if (field.hasPresence() && expected.hasField(field) != actual.hasField(field)) {
                return "%s: set in the %s recording only".formatted(
                        field.getName(), expected.hasField(field) ? "dense" : "sparse");
            }
            Object want = expected.getField(field);
            Object got = actual.getField(field);
            if (!want.equals(got)) {
                return "%s: dense recording has %s, sparse recording has %s"
                        .formatted(field.getName(), abbreviate(want), abbreviate(got));
            }
        }
        return "no field of " + expected.getDescriptorForType().getName();
    }

    private static String abbreviate(Object value) {
        String text = String.valueOf(value).replace('\n', ' ');
        return text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }

    // ========================================================================
    // The run
    // ========================================================================

    /**
     * The program both founders run: it writes a genome, forks once and then idles.
     * <p>
     * The genome has to be written at run time. The marker register decides what a fork hands on,
     * and the molecules the compiler places carry marker 0, which {@code FORK} refuses — so the
     * only cells a child can inherit are cells the parent wrote itself with a non-zero marker. The
     * genome holds a label, a label reference, code and data, which is what the mutation plugins
     * look for when they act on the newborn.
     * <p>
     * The idling after the fork keeps the parent alive and unchanging for the rest of the run, so
     * that a single birth is compared rather than a growing population.
     */
    private static final String PROGRAM = """
            .REG %GENE %DR0
            .REG %SPIN %DR7

            .ORG 0|0
            START:
              SMRI DATA:5
              SEKI 0|1
              SETI %GENE LABEL:42
              POKI %GENE 0|0
              SEKI 1|0
              SETI %GENE CODE:1
              POKI %GENE 0|0
              SEKI 1|0
              SETI %GENE DATA:42
              POKI %GENE 0|0
              SEKI 1|0
              SETI %GENE LABELREF:42
              POKI %GENE 0|0
              SEKI 1|0
              SETI %GENE LABEL:42
              POKI %GENE 0|0
              FRKI 1|0 DATA:80 1|0
            IDLE:
              SETI %SPIN DATA:1
              JMPI IDLE
            """;

    /**
     * The configuration of one run. Everything except {@code samplingInterval} and the delta
     * intervals is the same for both runs of a comparison, the seed included, so a difference
     * between the two recordings can only come from the sampling.
     *
     * @param samplingInterval ticks between captures
     * @param accumulatedDeltaInterval samples between accumulated deltas
     * @param snapshotInterval accumulated deltas between snapshots
     * @param chunkInterval snapshots per chunk
     * @return the engine options
     */
    private Config engineConfig(int samplingInterval, int accumulatedDeltaInterval,
                                int snapshotInterval, int chunkInterval) {
        return ConfigFactory.parseString("""
            samplingInterval = %d
            accumulatedDeltaInterval = %d
            snapshotInterval = %d
            chunkInterval = %d
            metricsWindowSeconds = 1
            pauseTicks = []
            seed = %d
            environment {
                shape = [%d, %d]
                topology = "TORUS"
            }
            organisms = [
                { program = "%s", initialEnergy = %d, placement { positions = [0, 0] } },
                { program = "%s", initialEnergy = %d, placement { positions = [48, 48] } }
            ]
            plugins = %s
            runtime {
                parallelism = 1
                organism {
                    max-energy = 32767
                    max-entropy = 8191
                    error-penalty-cost = 10
                }
                thermodynamics {
                    default {
                        className = "org.evochora.runtime.thermodynamics.impl.UniversalThermodynamicPolicy"
                        options { base-energy = 1, base-entropy = 1 }
                    }
                    overrides { instructions = {}, families = {} }
                }
                label-matching {
                    className = "org.evochora.runtime.label.PreExpandedHammingStrategy"
                    options { tolerance = 2, hammingWeight = 50, foreignPenalty = 100, selectionSpread = 50 }
                }
            }
            """.formatted(
                samplingInterval, accumulatedDeltaInterval, snapshotInterval, chunkInterval,
                SEED, WORLD_SIZE, WORLD_SIZE,
                programPath(), PARENT_ENERGY,
                programPath(), STARVING_ENERGY,
                ResumeNeutralityHarness.pluginsJson(1.0)));
    }

    private String programPath() {
        return programFile.toString().replace("\\", "/");
    }

    /**
     * An output queue that keeps everything it was given, so a run can be inspected after it ended.
     */
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
