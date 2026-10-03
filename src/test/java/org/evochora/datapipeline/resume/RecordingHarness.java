package org.evochora.datapipeline.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
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
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.delta.ChunkCorruptedException;
import org.evochora.datapipeline.api.resources.queues.IOutputQueueResource;
import org.evochora.datapipeline.services.SimulationEngine;
import org.evochora.datapipeline.utils.delta.DeltaCodec;

import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

/**
 * What every test shares that holds two recordings of the same run against each other: the run
 * itself, running an engine until its chunks cover a range, decoding them back into ticks, and
 * naming the first place two ticks differ.
 * <p>
 * The questions the recordings are asked — whether the sampling interval or a resume may change
 * what was written — belong to the individual tests. Keeping the machinery here means a second
 * comparison cannot quietly compare less than the first.
 */
final class RecordingHarness {

    /** Edge length of the square world. Wide enough for the program to lie in a single row. */
    static final int WORLD_SIZE = 96;

    static final long SEED = 42L;

    /** Energy of the parent: enough to lay out its genome, fork and then idle for the whole run. */
    private static final int PARENT_ENERGY = 30_000;

    /** Energy of the second founder: too little to finish its program, so it dies during the run. */
    private static final int STARVING_ENERGY = 30;

    /** How many diverging ticks a failure names before it only counts the rest. */
    private static final int REPORTED_DIFFERENCES = 10;

    private RecordingHarness() {}

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
     * Writes the program both founders run into the given directory.
     *
     * @param directory where the program file is placed
     * @return the program file
     * @throws IOException if the file cannot be written
     */
    static Path writeProgram(Path directory) throws IOException {
        Path programFile = directory.resolve("recording-harness.evo");
        Files.writeString(programFile, PROGRAM);
        return programFile;
    }

    /**
     * The configuration of one run. Everything except {@code samplingInterval} and the delta
     * intervals is fixed, the seed included, so a difference between two recordings can only come
     * from what the test varies.
     *
     * @param programFile the program both founders run
     * @param samplingInterval ticks between captures
     * @param accumulatedDeltaInterval samples between accumulated deltas
     * @param snapshotInterval accumulated deltas between snapshots
     * @param chunkInterval snapshots per chunk
     * @return the engine options
     */
    static Config engineConfig(Path programFile, int samplingInterval, int accumulatedDeltaInterval,
                               int snapshotInterval, int chunkInterval) {
        String programPath = programFile.toString().replace("\\", "/");
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
                    className = "org.evochora.runtime.label.HammingLabelMatchingStrategy"
                    options { tolerance = 2, selectionSpread = 50 }
                }
            }
            """.formatted(
                samplingInterval, accumulatedDeltaInterval, snapshotInterval, chunkInterval,
                SEED, WORLD_SIZE, WORLD_SIZE,
                programPath, PARENT_ENERGY,
                programPath, STARVING_ENERGY,
                ResumeNeutralityHarness.pluginsJson(1.0)));
    }

    // ========================================================================
    // Running an engine and decoding what it published
    // ========================================================================

    /** One run's recording: the reconstructed ticks and which of them were chunk snapshots. */
    record Recording(Map<Long, TickData> ticks, Set<Long> snapshotTicks) {}

    /**
     * Runs an engine until the chunks it published cover the given tick, then stops it.
     *
     * @param engine the engine, not yet started
     * @param tickData the queue the engine publishes its chunks to
     * @param until the last tick the published chunks have to cover
     */
    static void runUntil(SimulationEngine engine, CapturingQueue<TickDataChunk> tickData, long until) {
        engine.start();
        try {
            await().atMost(Duration.ofSeconds(120))
                    .pollInterval(Duration.ofMillis(50))
                    .until(() -> lastCoveredTick(tickData.getCaptured()) >= until);
        } finally {
            engine.stop();
        }
    }

    /** The last tick the published chunks cover without a gap. */
    private static long lastCoveredTick(List<TickDataChunk> chunks) {
        return chunks.isEmpty() ? -1 : chunks.get(chunks.size() - 1).getLastTick();
    }

    /**
     * Reconstructs every tick the chunks carry and records which ticks the chunks hold as
     * snapshots, since only those carry the random state and the plugin states.
     */
    static Recording decode(List<TickDataChunk> chunks) throws ChunkCorruptedException {
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
    // Deaths and births as a recording wrote them
    // ========================================================================

    /** A dead organism as one recording wrote it, with the tick it was written at. */
    record FinalAppearance(long tick, OrganismState state) {}

    /** The mutation records of one birth as one recording wrote them, with the tick and birth tick. */
    record MutatedBirth(long tick, long birthTick, List<MutationEvent> events) {}

    /** Every appearance of a dead organism in a recording, by organism, in tick order. */
    static Map<Integer, List<FinalAppearance>> finalAppearances(Recording run) {
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
    static Map<Integer, List<MutatedBirth>> mutatedBirths(Recording run) {
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

    // ========================================================================
    // Reporting a difference
    // ========================================================================

    /**
     * The differences a failure prints. A run that diverges at every tick would otherwise produce a
     * message of tens of thousands of characters, in which the first entries — the ones that say
     * where the divergence begins — are the only ones that carry information.
     */
    static List<String> reported(List<String> differences) {
        if (differences.size() <= REPORTED_DIFFERENCES) {
            return differences;
        }
        List<String> reported = new ArrayList<>(differences.subList(0, REPORTED_DIFFERENCES));
        reported.add("… and " + (differences.size() - REPORTED_DIFFERENCES) + " further ticks");
        return reported;
    }

    /**
     * Names the first place two reconstructed ticks differ, or an empty string when they are equal.
     * <p>
     * A {@link TickData} of a populated world prints as thousands of lines, so an equality failure
     * on the whole message would hide the one field that moved. The check starts from whole-message
     * equality and only then narrows down, so nothing is left out: every field of the message is
     * either drilled into here or reported by name by {@link #firstDifferingField}.
     *
     * @param expected the tick as the reference recording holds it
     * @param actual the tick as the recording under test holds it
     * @return a description of the first difference, empty when the messages are equal
     */
    static String describeDifference(TickData expected, TickData actual) {
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
                return "organism %d is in the reference recording only (dead=%s)"
                        .formatted(entry.getKey(), entry.getValue().getIsDead());
            }
            if (!entry.getValue().equals(other)) {
                return "organism %d differs in %s"
                        .formatted(entry.getKey(), firstDifferingField(entry.getValue(), other));
            }
        }
        for (Integer id : byIdActual.keySet()) {
            if (!byIdExpected.containsKey(id)) {
                return "organism %d is in the recording under test only (dead=%s)"
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
                return "cell %d: reference recording has %s, recording under test has %s"
                        .formatted(index, want, got);
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
                return "%s: set in the %s only".formatted(field.getName(),
                        expected.hasField(field) ? "reference recording" : "recording under test");
            }
            Object want = expected.getField(field);
            Object got = actual.getField(field);
            if (!want.equals(got)) {
                return "%s: reference recording has %s, recording under test has %s"
                        .formatted(field.getName(), abbreviate(want), abbreviate(got));
            }
        }
        return "no field of " + expected.getDescriptorForType().getName();
    }

    /**
     * The named fields in which two messages of the same type differ, each with both values. A
     * field one message sets and the other leaves absent counts as differing, so a difference in
     * presence is not lost behind two equal default values.
     */
    static List<String> differingFields(Message expected, Message actual, List<String> names) {
        List<String> differences = new ArrayList<>();
        for (String name : names) {
            Descriptors.FieldDescriptor field = expected.getDescriptorForType().findFieldByName(name);
            assertThat(field).as("the comparison names the field %s, which the message must have", name)
                    .isNotNull();
            if (field.hasPresence() && expected.hasField(field) != actual.hasField(field)) {
                differences.add("%s: set in the %s only".formatted(name,
                        expected.hasField(field) ? "reference recording" : "recording under test"));
            } else if (!expected.getField(field).equals(actual.getField(field))) {
                differences.add("%s: reference recording has %s, recording under test has %s".formatted(
                        name, abbreviate(expected.getField(field)), abbreviate(actual.getField(field))));
            }
        }
        return differences;
    }

    private static String abbreviate(Object value) {
        String text = String.valueOf(value).replace('\n', ' ');
        return text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }

    /**
     * An output queue that keeps everything it was given, so a run can be inspected after it ended.
     */
    static final class CapturingQueue<T> implements IOutputQueueResource<T> {
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
