package org.evochora.tools.trace;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.evochora.datapipeline.api.contracts.CellDataColumns;
import org.evochora.datapipeline.api.contracts.InstructionMapping;
import org.evochora.datapipeline.api.contracts.OrganismState;
import org.evochora.datapipeline.api.contracts.ParamInfo;
import org.evochora.datapipeline.api.contracts.ParamType;
import org.evochora.datapipeline.api.contracts.ProcFrame;
import org.evochora.datapipeline.api.contracts.ProgramArtifact;
import org.evochora.datapipeline.api.contracts.RegisterValue;
import org.evochora.datapipeline.api.contracts.SimulationMetadata;
import org.evochora.datapipeline.api.contracts.SourceMapEntry;
import org.evochora.datapipeline.api.contracts.TickData;
import org.evochora.datapipeline.api.contracts.TickDataChunk;
import org.evochora.datapipeline.api.contracts.TickDelta;
import org.evochora.datapipeline.api.contracts.Vector;
import org.evochora.datapipeline.api.resources.IResource;
import org.evochora.datapipeline.api.resources.queues.IInputQueueResource;
import org.evochora.datapipeline.api.resources.queues.StreamingBatch;
import org.evochora.datapipeline.services.AbstractService;
import org.evochora.datapipeline.utils.delta.MutableCellState;
import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Family;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.RegisterBank;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.MoleculeTypeRegistry;

import com.google.protobuf.util.JsonFormat;
import com.typesafe.config.ConfigFactory;

/**
 * Records a run as tab-separated tables that a query tool can read: what every organism executed
 * in every tick, the state it was left in, and every cell that changed. Nothing is persisted by
 * the pipeline; this consumer is the only writer, and the trace directory is the only output.
 *
 * <p>Three tables and two side files land in {@code outputDir}:</p>
 * <ul>
 *   <li>{@code steps.tsv} - one row per organism and tick in which it executed or failed an
 *       instruction: the instruction as it was actually fetched from the cells (opcode, raw
 *       argument molecules, resolved arguments), the data pointer it acted with, its costs and
 *       failure reason, whether a conditional was met, and beside it what the compiled program
 *       expects at that address (file, line, source text, enclosing label, expected opcode) with
 *       a flag when the two disagree; last what the tick changed in the organism's state.</li>
 *   <li>{@code state.tsv} - one row per organism and tick with the complete state after the
 *       tick: energy, entropy, marker, pointers, every register, the three stacks.</li>
 *   <li>{@code cells.tsv} - every cell that changed, per tick, and the complete set of occupied
 *       cells at the first tick and then every {@code cellSnapshotInterval} ticks.</li>
 *   <li>{@code run.tsv} - key/value facts of the run: id, seed, world shape, programs.</li>
 *   <li>{@code artifact_<programId>.json} - the compiler artifact of every program, as JSON.</li>
 * </ul>
 *
 * <p>Cells are tracked from the recorded full states and deltas; a change row is written for every
 * cell whose molecule or owner differs from the tracked state, whatever delivered it, and a full
 * state after the first also yields a change row for every cell that became empty, which a full
 * state does not list. Lists are written without quotes, as {@code [a,b,c]}, so the files hold no quote characters
 * and a CSV reader needs no quoting rules.</p>
 *
 * <p>Options: {@code outputDir} (required), {@code cellSnapshotInterval} (default 1000),
 * {@code organisms} (a list of organism IDs; when given, {@code steps.tsv} and {@code state.tsv}
 * hold rows of these organisms only, while {@code cells.tsv} still holds every cell of the world).
 * Resources: {@code input} (tick chunks), {@code metadata} (the run's metadata message).</p>
 */
public final class TraceConsumer extends AbstractService {

    private static final Pattern LABEL_LINE = Pattern.compile(
            "^\\s*(?:EXPORT\\s+)?(?:\\.PROC\\s+([A-Za-z_][A-Za-z0-9_.]*)|([A-Za-z_][A-Za-z0-9_.]*):)");

    private final IInputQueueResource<TickDataChunk> input;
    private final IInputQueueResource<SimulationMetadata> metadata;
    private final Path outputDir;
    private final long cellSnapshotInterval;
    /** The organisms whose steps and states are written; null writes every organism. */
    private final Set<Integer> organismFilter;

    private EnvironmentProperties envProps;
    private MutableCellState cells;
    /** The cells {@link #cells} holds as occupied - molecule data, an owner, or both - one bit per flat index. */
    private BitSet occupied;
    /** Scratch set of the cells a full state lists as occupied, swapped with {@link #occupied} once it is applied. */
    private BitSet occupiedInFullState;
    private final Map<String, Program> programs = new HashMap<>();
    /** State of every living organism as recorded at the previous tick. */
    private final Map<Integer, OrganismState> previousStates = new HashMap<>();
    /**
     * The last step row of every organism, held back until its next step says whether a
     * conditional was met: the instruction that follows a met condition is the one right behind
     * it, the one that follows an unmet condition is the one behind that.
     */
    private final Map<Integer, PendingStep> pendingSteps = new HashMap<>();
    /** Names of the register slots, in the order the state rows and the engine use. */
    private String[] slotNames;

    /** A step row split around its {@code cond_met} column, with what decides that column. */
    private record PendingStep(String before, String after, boolean conditional, Integer address, int length) {}

    private BufferedWriter steps;
    private BufferedWriter state;
    private BufferedWriter cellsOut;
    private boolean stateHeaderWritten = false;
    private boolean firstFullStateSeen = false;
    private long chunkCount = 0;
    private long lastTick = -1;
    private long lastDrainedTick = -1;

    /** The lookups derived from one program's artifact. */
    private static final class Program {
        final Map<String, Integer> relCoordToAddress = new HashMap<>();
        final Map<Integer, SourceMapEntry> addressToSource = new HashMap<>();
        final Map<String, List<String>> sources = new HashMap<>();
        final Map<Integer, String> labelValueToName = new HashMap<>();
        /** The molecule the compiler placed at every relative coordinate of the code layout. */
        final Map<String, Integer> relCoordToLayoutMolecule = new HashMap<>();
        final Map<String, String> enclosingLabelCache = new HashMap<>();
        /** The declared parameters of every procedure, by its qualified name. */
        final Map<String, List<ParamInfo>> procParams = new HashMap<>();
        /** The directory prefix all source file names share, dropped from the trace. */
        String sourcePrefix = "";
    }

    @SuppressWarnings("unchecked")
    public TraceConsumer(String name, com.typesafe.config.Config options, Map<String, List<IResource>> resources) {
        super(name, options, resources);
        this.input = (IInputQueueResource<TickDataChunk>) getRequiredResource("input", IInputQueueResource.class);
        this.metadata = (IInputQueueResource<SimulationMetadata>) getRequiredResource("metadata", IInputQueueResource.class);
        this.outputDir = Path.of(options.getString("outputDir"));
        this.cellSnapshotInterval = options.hasPath("cellSnapshotInterval") ? options.getLong("cellSnapshotInterval") : 1000L;
        if (this.cellSnapshotInterval < 1) {
            throw new IllegalArgumentException("cellSnapshotInterval must be >= 1");
        }
        this.organismFilter = options.hasPath("organisms") ? Set.copyOf(options.getIntList("organisms")) : null;
        try {
            Files.createDirectories(this.outputDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create outputDir " + this.outputDir, e);
        }
        Instruction.init();
    }

    @Override
    protected void run() throws InterruptedException {
        try {
            readMetadata();
            openWriters();
            while (!isStopRequested() && !Thread.currentThread().isInterrupted()) {
                checkPause();
                try (StreamingBatch<TickDataChunk> batch = input.receiveBatch(10, 5, TimeUnit.SECONDS)) {
                    if (batch.size() == 0) {
                        // The queue ran dry: everything received so far is on disk. The line lets
                        // a script tell a finished recording from one that is still draining.
                        if (lastTick != lastDrainedTick) {
                            log.info("TRACE drained lastTick={}", lastTick);
                            lastDrainedTick = lastTick;
                        }
                        continue;
                    }
                    for (TickDataChunk chunk : batch) {
                        recordChunk(chunk);
                    }
                    batch.commit();
                    flushAll();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write trace to " + outputDir, e);
        } finally {
            closeAll();
        }
    }

    // ---------------------------------------------------------------- metadata

    private void readMetadata() throws InterruptedException, IOException {
        SimulationMetadata meta = null;
        while (meta == null && !isStopRequested() && !Thread.currentThread().isInterrupted()) {
            checkPause();
            try (StreamingBatch<SimulationMetadata> batch = metadata.receiveBatch(1, 5, TimeUnit.SECONDS)) {
                for (SimulationMetadata m : batch) {
                    meta = m;
                }
                batch.commit();
            }
        }
        if (meta == null) {
            return;
        }
        com.typesafe.config.Config resolved = ConfigFactory.parseString(meta.getResolvedConfigJson());
        int[] shape = resolved.getIntList("environment.shape").stream().mapToInt(i -> i).toArray();
        boolean toroidal = "TORUS".equalsIgnoreCase(resolved.getString("environment.topology"));
        this.envProps = new EnvironmentProperties(shape, toroidal);
        this.cells = new MutableCellState(Math.toIntExact(envProps.getTotalCells()));
        this.occupied = new BitSet(Math.toIntExact(envProps.getTotalCells()));
        this.occupiedInFullState = new BitSet(Math.toIntExact(envProps.getTotalCells()));

        StringBuilder programIds = new StringBuilder();
        for (ProgramArtifact artifact : meta.getProgramsList()) {
            programs.put(artifact.getProgramId(), indexProgram(artifact));
            String json = JsonFormat.printer().print(artifact);
            Files.writeString(outputDir.resolve("artifact_" + fileSafe(artifact.getProgramId()) + ".json"), json, StandardCharsets.UTF_8);
            if (programIds.length() > 0) {
                programIds.append(',');
            }
            programIds.append(artifact.getProgramId());
        }

        StringBuilder shapeText = new StringBuilder();
        for (int i = 0; i < shape.length; i++) {
            if (i > 0) {
                shapeText.append('|');
            }
            shapeText.append(shape[i]);
        }
        try (BufferedWriter run = Files.newBufferedWriter(outputDir.resolve("run.tsv"), StandardCharsets.UTF_8)) {
            run.write("key\tvalue\n");
            run.write("run_id\t" + meta.getSimulationRunId() + "\n");
            run.write("seed\t" + meta.getInitialSeed() + "\n");
            run.write("build_revision\t" + meta.getBuildRevision() + "\n");
            run.write("start_time_ms\t" + meta.getStartTimeMs() + "\n");
            run.write("dimensions\t" + shape.length + "\n");
            run.write("shape\t" + shapeText + "\n");
            run.write("toroidal\t" + toroidal + "\n");
            run.write("programs\t" + programIds + "\n");
            run.write("cell_snapshot_interval\t" + cellSnapshotInterval + "\n");
            run.write("organisms\t" + (organismFilter == null ? "all" : sortedList(organismFilter)) + "\n");
        }
        log.info("Trace of run {} starts: world {} {}, {} program(s), output {}",
                meta.getSimulationRunId(), shapeText, toroidal ? "toroidal" : "bounded", programs.size(), outputDir);
    }

    private static Program indexProgram(ProgramArtifact artifact) {
        Program p = new Program();
        p.relCoordToAddress.putAll(artifact.getRelativeCoordToLinearAddressMap());
        for (SourceMapEntry entry : artifact.getSourceMapList()) {
            p.addressToSource.put(entry.getLinearAddress(), entry);
        }
        artifact.getSourcesMap().forEach((file, lines) -> p.sources.put(file, lines.getLinesList()));
        p.labelValueToName.putAll(artifact.getLabelValueToNameMap());
        for (InstructionMapping mapping : artifact.getMachineCodeLayoutList()) {
            p.relCoordToLayoutMolecule.put(vector(mapping.getPosition()), mapping.getInstruction());
        }
        artifact.getProcNameToParamNamesMap().forEach((name, params) -> p.procParams.put(name, params.getParamsList()));
        p.sourcePrefix = commonDirectory(p.sources.keySet());
        return p;
    }

    /** The longest directory prefix, slash included, that all the given file names share. */
    private static String commonDirectory(java.util.Collection<String> files) {
        String prefix = null;
        for (String file : files) {
            String dir = file.substring(0, file.lastIndexOf('/') + 1);
            if (prefix == null) {
                prefix = dir;
                continue;
            }
            int i = 0;
            while (i < prefix.length() && i < dir.length() && prefix.charAt(i) == dir.charAt(i)) {
                i++;
            }
            prefix = prefix.substring(0, prefix.lastIndexOf('/', i - 1) + 1);
        }
        return prefix == null ? "" : prefix;
    }

    private static String sortedList(Set<Integer> ids) {
        List<Integer> sorted = new ArrayList<>(ids);
        java.util.Collections.sort(sorted);
        return sorted.toString().replace(" ", "");
    }

    private static String fileSafe(String id) {
        return id.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    // ---------------------------------------------------------------- chunks

    /**
     * Records every tick a chunk carries.
     * <p>
     * A recorded tick comes in one of two forms, and each is read for what it is, wherever it
     * stands in the chunk: a full state, which lists every occupied cell and no empty one, or a
     * set of changes, which lists every cell that changed since an earlier recording with its
     * present content, a cell that became empty included. Deltas of both known types are such
     * change sets; neither is read as relative to a particular recording, each is compared with
     * the tracked state. A delta of any other type stops the trace, and so does a tick that does
     * not follow the previous one by the chunk's sampling interval - a recording in a form this
     * consumer does not read would otherwise drop out of the trace without a trace of its own.
     */
    private void recordChunk(TickDataChunk chunk) throws IOException {
        int samplingInterval = chunk.getSamplingInterval();
        if (chunk.hasSnapshot()) {
            TickData snapshot = chunk.getSnapshot();
            advanceTo(snapshot.getTickNumber(), samplingInterval);
            recordFullState(snapshot.getTickNumber(), snapshot.getCellColumns());
            recordOrganisms(snapshot.getTickNumber(), snapshot.getOrganismsList());
        }
        for (TickDelta delta : chunk.getDeltasList()) {
            long tick = delta.getTickNumber();
            advanceTo(tick, samplingInterval);
            switch (delta.getDeltaType()) {
                case INCREMENTAL, ACCUMULATED -> recordChanges(tick, delta.getChangedCells());
                default -> throw new IllegalStateException("Tick " + tick + " is recorded as a delta of type "
                        + delta.getDeltaType() + ", which the trace consumer cannot read: it reads full states "
                        + "and deltas that list the changed cells");
            }
            recordOrganisms(tick, delta.getOrganismsList());
        }
        chunkCount++;
        if (chunkCount % 100 == 0) {
            log.info("TRACE chunks={} lastTick={}", chunkCount, lastTick);
        }
    }

    /**
     * Requires a recorded tick to follow the previous one by the sampling interval, and makes it
     * the last tick.
     *
     * @throws IllegalStateException if a tick is missing between the previous one and this one
     */
    private void advanceTo(long tick, int samplingInterval) {
        if (lastTick >= 0 && tick != lastTick + samplingInterval) {
            throw new IllegalStateException(String.format(
                    "Tick %d follows tick %d, but the run records every %d ticks: a recorded tick did not "
                            + "reach the trace", tick, lastTick, samplingInterval));
        }
        lastTick = tick;
    }

    /**
     * Records a full state. The first one is written as the complete set of occupied cells. Every
     * later one is written as its difference to the tracked state: a change row for every listed
     * cell whose content differs, and a change row with an empty cell for every tracked occupied
     * cell the state no longer lists. The state then replaces the tracked one.
     */
    private void recordFullState(long tick, CellDataColumns state) throws IOException {
        int n = state.getFlatIndicesCount();
        if (!firstFullStateSeen) {
            firstFullStateSeen = true;
            cells.applySnapshot(state);
            occupied.clear();
            for (int i = 0; i < n; i++) {
                if (state.getMoleculeData(i) != 0 || state.getOwnerIds(i) != 0) {
                    occupied.set(state.getFlatIndices(i));
                }
            }
            writeFullCells(tick);
            return;
        }
        int[] coord = new int[envProps.getDimensions()];
        occupiedInFullState.clear();
        for (int i = 0; i < n; i++) {
            int flat = state.getFlatIndices(i);
            int molecule = state.getMoleculeData(i);
            int owner = state.getOwnerIds(i);
            if (molecule != 0 || owner != 0) {
                occupiedInFullState.set(flat);
            }
            if (cells.getMoleculeData(flat) != molecule || cells.getOwnerId(flat) != owner) {
                envProps.flatIndexToCoordinates(flat, coord);
                writeCellRow(tick, "change", coord, molecule, owner);
            }
        }
        for (int flat = occupied.nextSetBit(0); flat >= 0; flat = occupied.nextSetBit(flat + 1)) {
            if (!occupiedInFullState.get(flat)) {
                envProps.flatIndexToCoordinates(flat, coord);
                writeCellRow(tick, "change", coord, 0, 0);
            }
        }
        cells.applySnapshot(state);
        BitSet previous = occupied;
        occupied = occupiedInFullState;
        occupiedInFullState = previous;
        if (tick % cellSnapshotInterval == 0) {
            writeFullCells(tick);
        }
    }

    /**
     * Records a set of changes: a change row for every listed cell whose content differs from the
     * tracked state, a cell that became empty included, after which the tracked state takes the
     * listed contents.
     */
    private void recordChanges(long tick, CellDataColumns changes) throws IOException {
        int n = changes.getFlatIndicesCount();
        int[] coord = new int[envProps.getDimensions()];
        for (int i = 0; i < n; i++) {
            int flat = changes.getFlatIndices(i);
            int molecule = changes.getMoleculeData(i);
            int owner = changes.getOwnerIds(i);
            occupied.set(flat, molecule != 0 || owner != 0);
            if (cells.getMoleculeData(flat) == molecule && cells.getOwnerId(flat) == owner) {
                continue;
            }
            envProps.flatIndexToCoordinates(flat, coord);
            writeCellRow(tick, "change", coord, molecule, owner);
        }
        cells.applyDelta(changes);
        if (tick % cellSnapshotInterval == 0) {
            writeFullCells(tick);
        }
    }

    private void writeFullCells(long tick) throws IOException {
        int[] coord = new int[envProps.getDimensions()];
        IOException[] failure = new IOException[1];
        cells.forEachOccupiedCell((flat, molecule, owner) -> {
            if (failure[0] != null) {
                return;
            }
            try {
                envProps.flatIndexToCoordinates(flat, coord);
                writeCellRow(tick, "full", coord, molecule, owner);
            } catch (IOException e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) {
            throw failure[0];
        }
    }

    private void writeCellRow(long tick, String kind, int[] coord, int moleculeInt, int owner) throws IOException {
        Molecule m = Molecule.fromInt(moleculeInt);
        StringBuilder sb = new StringBuilder(64);
        sb.append(tick).append('\t').append(kind);
        for (int c : coord) {
            sb.append('\t').append(c);
        }
        sb.append('\t').append(MoleculeTypeRegistry.typeToName(m.type()))
          .append('\t').append(m.toScalarValue())
          .append('\t').append(m.marker())
          .append('\t').append(owner)
          .append('\n');
        cellsOut.write(sb.toString());
    }

    // ---------------------------------------------------------------- organisms

    private void recordOrganisms(long tick, List<OrganismState> organisms) throws IOException {
        for (OrganismState o : organisms) {
            if (organismFilter != null && !organismFilter.contains(o.getOrganismId())) {
                continue;
            }
            if (!stateHeaderWritten) {
                writeStateHeader(o.getDataPointersCount());
                stateHeaderWritten = true;
            }
            if (o.hasInstructionOpcodeId() || o.getInstructionFailed()) {
                writeStepRow(tick, o);
            }
            writeStateRow(tick, o);
            if (o.getIsDead()) {
                flushPendingStep(o.getOrganismId());
                previousStates.remove(o.getOrganismId());
            } else {
                previousStates.put(o.getOrganismId(), o);
            }
        }
    }

    /**
     * The components of a recorded vector as the array the runtime's coordinate arithmetic takes.
     *
     * @param vector the recorded vector
     * @param dims the number of dimensions of the world
     * @return a new array holding the vector's components
     */
    private static int[] coordinates(Vector vector, int dims) {
        int[] components = new int[dims];
        for (int d = 0; d < dims; d++) {
            components[d] = vector.getComponents(d);
        }
        return components;
    }

    private void writeStepRow(long tick, OrganismState o) throws IOException {
        Program program = programs.get(o.getProgramId());
        int dims = envProps.getDimensions();
        Vector ipBefore = o.getIpBeforeFetch();
        Vector origin = o.getInitialPosition();

        // Relative position within the program, as the compiler laid it out. On a toroidal world
        // the difference to the origin is taken the short way round, the same rule the runtime
        // uses: a body that lies across the world edge would otherwise be reported at an offset of
        // nearly a world width, and none of its cells would be found in the program's layout.
        int[] relative = envProps.getRelativeVector(coordinates(origin, dims), coordinates(ipBefore, dims));
        StringBuilder rel = new StringBuilder();
        for (int d = 0; d < dims; d++) {
            if (d > 0) {
                rel.append('|');
            }
            rel.append(relative[d]);
        }
        Integer address = program == null ? null : program.relCoordToAddress.get(rel.toString());

        OrganismState previous = previousStates.get(o.getOrganismId());
        String execOp = "";
        String argsRaw = "";
        String args = "";
        boolean conditional = false;
        int length = 1 + o.getInstructionRawArgumentsCount();
        if (o.hasInstructionOpcodeId()) {
            int opcodeId = o.getInstructionOpcodeId();
            execOp = Instruction.getInstructionNameById(opcodeId);
            argsRaw = rawArguments(o);
            args = resolvedArguments(o, opcodeId, program, dims, previous);
            conditional = Instruction.getFamilyById(opcodeId) == Family.CONDITIONAL;
        }
        // The data pointer the instruction acted with: the active one as the tick began.
        String dp = "";
        if (previous != null && previous.getActiveDpIndex() < previous.getDataPointersCount()) {
            dp = previous.getActiveDpIndex() + "@" + vector(previous.getDataPointers(previous.getActiveDpIndex()));
        }

        String srcFile = "";
        String srcLine = "";
        String srcText = "";
        String srcLabel = "";
        String srcOp = "";
        String expectedOp = "";
        if (program != null && address != null) {
            SourceMapEntry entry = program.addressToSource.get(address);
            if (entry != null) {
                String file = entry.getSourceInfo().getFileName();
                int line = entry.getSourceInfo().getLineNumber();
                srcFile = file.startsWith(program.sourcePrefix) ? file.substring(program.sourcePrefix.length()) : file;
                srcLine = Integer.toString(line);
                List<String> lines = program.sources.get(file);
                if (lines != null && line >= 1 && line <= lines.size()) {
                    srcText = lines.get(line - 1).strip();
                }
                srcLabel = enclosingLabel(program, file, line);
            }
            Integer placed = program.relCoordToLayoutMolecule.get(rel.toString());
            if (placed != null) {
                Molecule m = Molecule.fromInt(placed);
                if (m.type() == Config.TYPE_CODE) {
                    srcOp = Instruction.getInstructionNameById(placed);
                    expectedOp = srcOp;
                } else {
                    // A label cell is shown as what it is; the machine executes it as a NOP.
                    srcOp = MoleculeTypeRegistry.typeToName(m.type());
                    expectedOp = m.type() == Config.TYPE_LABEL ? "NOP" : "";
                }
            }
        }
        // The executed opcode disagrees with the program when the position is not in the code
        // layout at all, or when the layout holds another instruction there.
        boolean differs = !execOp.isEmpty() && !expectedOp.equalsIgnoreCase(execOp);

        // This step settles the previous one's cond_met column.
        finishPendingStep(o.getOrganismId(), address);

        StringBuilder before = new StringBuilder(256);
        before.append(tick).append('\t').append(o.getOrganismId())
          .append('\t').append(vector(ipBefore))
          .append('\t').append(rel)
          .append('\t').append(address == null ? "" : address.toString())
          .append('\t').append(execOp)
          .append('\t').append(argsRaw)
          .append('\t').append(args)
          .append('\t').append(dp)
          .append('\t').append(o.hasInstructionEnergyCost() ? Integer.toString(o.getInstructionEnergyCost()) : "")
          .append('\t').append(o.hasInstructionEntropyDelta() ? Integer.toString(o.getInstructionEntropyDelta()) : "")
          .append('\t').append(o.getInstructionFailed() ? 1 : 0)
          .append('\t').append(clean(o.hasFailureReason() ? o.getFailureReason() : ""));
        StringBuilder after = new StringBuilder(256);
        after.append('\t').append(srcFile)
          .append('\t').append(srcLine)
          .append('\t').append(clean(srcText))
          .append('\t').append(srcLabel)
          .append('\t').append(srcOp)
          .append('\t').append(differs ? 1 : 0)
          .append('\t').append(changes(previous, o))
          .append('\n');
        pendingSteps.put(o.getOrganismId(), new PendingStep(before.toString(), after.toString(), conditional, address, length));
    }

    /**
     * Writes the organism's held-back step, its {@code cond_met} column decided by the address
     * the next step executed at: 1 when it is the instruction right behind the conditional, 0
     * when that one was skipped, empty for anything that is not a conditional or cannot be placed.
     */
    private void finishPendingStep(int organismId, Integer nextAddress) throws IOException {
        PendingStep pending = pendingSteps.remove(organismId);
        if (pending == null) {
            return;
        }
        String condMet = "";
        if (pending.conditional() && pending.address() != null && nextAddress != null) {
            condMet = nextAddress == pending.address() + pending.length() ? "1" : "0";
        }
        steps.write(pending.before() + '\t' + condMet + pending.after());
    }

    private void flushPendingStep(int organismId) throws IOException {
        finishPendingStep(organismId, null);
    }

    /**
     * What the tick changed in the organism: every register, data pointer and stack depth whose
     * value differs from the previous tick's, as {@code name:old>new}, plus the active pointer,
     * marker and direction. Empty when nothing changed or no previous tick is known.
     */
    private String changes(OrganismState previous, OrganismState current) {
        if (previous == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        List<RegisterValue> before = previous.getRegistersList();
        List<RegisterValue> now = current.getRegistersList();
        for (int i = 0; i < now.size() && i < before.size() && i < slotNames.length; i++) {
            if (!before.get(i).equals(now.get(i))) {
                append(sb, slotNames[i], registerValue(before.get(i)), registerValue(now.get(i)));
            }
        }
        for (int i = 0; i < current.getDataPointersCount() && i < previous.getDataPointersCount(); i++) {
            if (!previous.getDataPointers(i).equals(current.getDataPointers(i))) {
                append(sb, "dp" + i, vector(previous.getDataPointers(i)), vector(current.getDataPointers(i)));
            }
        }
        if (previous.getActiveDpIndex() != current.getActiveDpIndex()) {
            append(sb, "adp", Integer.toString(previous.getActiveDpIndex()), Integer.toString(current.getActiveDpIndex()));
        }
        if (previous.getMoleculeMarkerRegister() != current.getMoleculeMarkerRegister()) {
            append(sb, "mr", Integer.toString(previous.getMoleculeMarkerRegister()), Integer.toString(current.getMoleculeMarkerRegister()));
        }
        if (!previous.getDv().equals(current.getDv())) {
            append(sb, "dv", vector(previous.getDv()), vector(current.getDv()));
        }
        if (previous.getDataStackCount() != current.getDataStackCount()) {
            append(sb, "ds", Integer.toString(previous.getDataStackCount()), Integer.toString(current.getDataStackCount()));
        }
        if (previous.getLocationStackCount() != current.getLocationStackCount()) {
            append(sb, "ls", Integer.toString(previous.getLocationStackCount()), Integer.toString(current.getLocationStackCount()));
        }
        if (previous.getCallStackCount() != current.getCallStackCount()) {
            append(sb, "cs", Integer.toString(previous.getCallStackCount()), Integer.toString(current.getCallStackCount()));
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String name, String from, String to) {
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(name).append(':').append(from).append('>').append(to);
    }

    private static String rawArguments(OrganismState o) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < o.getInstructionRawArgumentsCount(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(molecule(o.getInstructionRawArguments(i)));
        }
        return sb.toString();
    }

    /**
     * Renders the arguments the way the instruction read them: registers by name with the value
     * they held before the instruction, immediates as molecules, vectors as components, labels by
     * name, stack operands with the value taken from the top of the previous tick's data stack.
     */
    private String resolvedArguments(OrganismState o, int opcodeId, Program program, int dims, OrganismState previous) {
        List<Instruction.OperandSource> sources = Instruction.getOperandSourcesById(opcodeId);
        if (sources == null) {
            return "";
        }
        List<Integer> raw = o.getInstructionRawArgumentsList();
        Map<Integer, RegisterValue> before = o.getInstructionRegisterValuesBeforeMap();
        List<RegisterValue> previousStack = previous == null ? null : previous.getDataStackList();
        // The procedure the instruction ran in names its formal registers.
        List<ProcFrame> frames = (previous != null ? previous : o).getCallStackList();
        List<ParamInfo> params = null;
        if (program != null && !frames.isEmpty()) {
            String procName = program.labelValueToName.get(frames.get(0).getLabelHash());
            params = procName == null ? null : program.procParams.get(procName);
        }
        StringBuilder sb = new StringBuilder();
        int slot = 0;
        int stackDepth = 0;
        for (Instruction.OperandSource source : sources) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            if (source == Instruction.OperandSource.STACK) {
                RegisterValue v = previousStack != null && stackDepth < previousStack.size() ? previousStack.get(stackDepth) : null;
                sb.append("S").append(stackDepth).append('=').append(v == null ? "?" : registerValue(v));
                stackDepth++;
                continue;
            }
            if (slot >= raw.size()) {
                sb.append('?');
                break;
            }
            int rawMol = raw.get(slot++);
            switch (source) {
                case REGISTER, LOCATION_REGISTER -> {
                    int regId = Molecule.extractSignedValue(rawMol);
                    RegisterValue v = before.get(regId);
                    sb.append(registerName(regId));
                    String param = parameterName(params, regId);
                    if (param != null) {
                        // The machine register stays first: it is what the cell holds, and the
                        // source is only what the compiler once meant by it.
                        sb.append('(').append(param).append(')');
                    }
                    sb.append('=').append(v == null ? "?" : registerValue(v));
                }
                case IMMEDIATE -> sb.append(molecule(rawMol));
                case VECTOR -> {
                    sb.append(Molecule.extractSignedValue(rawMol));
                    for (int d = 1; d < dims; d++) {
                        sb.append('|');
                        if (slot < raw.size()) {
                            sb.append(Molecule.extractSignedValue(raw.get(slot++)));
                        } else {
                            sb.append('?');
                        }
                    }
                }
                case LABEL -> {
                    int value = rawMol & Config.VALUE_MASK;
                    String name = program == null ? null : program.labelValueToName.get(value);
                    sb.append(name != null ? name : "LABEL:" + value);
                }
                default -> sb.append(molecule(rawMol));
            }
        }
        return sb.toString();
    }

    private static String enclosingLabel(Program program, String file, int line) {
        String key = file + ":" + line;
        String cached = program.enclosingLabelCache.get(key);
        if (cached != null) {
            return cached;
        }
        String found = "";
        List<String> lines = program.sources.get(file);
        if (lines != null) {
            for (int i = Math.min(line, lines.size()) - 1; i >= 0; i--) {
                Matcher m = LABEL_LINE.matcher(lines.get(i));
                if (m.find()) {
                    found = m.group(1) != null ? m.group(1) : m.group(2);
                    break;
                }
            }
        }
        program.enclosingLabelCache.put(key, found);
        return found;
    }

    private void writeStateHeader(int dpCount) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("tick\torg\tdead\tbirth_tick\tdeath_tick\tparent\tgeneration\tprogram\torigin"
                + "\ter\tsr\tmr\tip\tdv\tadp");
        for (int i = 0; i < dpCount; i++) {
            sb.append("\tdp").append(i);
        }
        for (String name : slotNames) {
            sb.append('\t').append(name);
        }
        sb.append("\tds\tls\tcs\tgenome_hash\n");
        state.write(sb.toString());
    }

    private void writeStateRow(long tick, OrganismState o) throws IOException {
        StringBuilder sb = new StringBuilder(512);
        sb.append(tick).append('\t').append(o.getOrganismId())
          .append('\t').append(o.getIsDead() ? 1 : 0)
          .append('\t').append(o.getBirthTick())
          .append('\t').append(o.hasDeathTick() ? Long.toString(o.getDeathTick()) : "")
          .append('\t').append(o.hasParentId() ? Integer.toString(o.getParentId()) : "")
          .append('\t').append(o.getGeneration())
          .append('\t').append(o.getProgramId())
          .append('\t').append(vector(o.getInitialPosition()))
          .append('\t').append(o.getEnergy())
          .append('\t').append(o.getEntropyRegister())
          .append('\t').append(o.getMoleculeMarkerRegister())
          .append('\t').append(vector(o.getIp()))
          .append('\t').append(vector(o.getDv()))
          .append('\t').append(o.getActiveDpIndex());
        for (Vector dp : o.getDataPointersList()) {
            sb.append('\t').append(vector(dp));
        }
        // Registers arrive in slot order, the order of the banks and their registers.
        List<RegisterValue> registers = o.getRegistersList();
        int slots = 0;
        for (RegisterBank bank : RegisterBank.values()) {
            slots += bank.count;
        }
        for (int i = 0; i < slots; i++) {
            sb.append('\t').append(i < registers.size() ? registerValue(registers.get(i)) : "?");
        }
        sb.append('\t').append(registerList(o.getDataStackList()));
        sb.append('\t').append(vectorList(o.getLocationStackList()));
        sb.append('\t').append(callStack(o.getCallStackList(), programs.get(o.getProgramId())));
        sb.append('\t').append(o.getGenomeHash());
        sb.append('\n');
        state.write(sb.toString());
    }

    // ---------------------------------------------------------------- formatting

    private static String molecule(int moleculeInt) {
        Molecule m = Molecule.fromInt(moleculeInt);
        String text = MoleculeTypeRegistry.typeToName(m.type()) + ":" + m.toScalarValue();
        return m.marker() != 0 ? text + "/" + m.marker() : text;
    }

    private static String registerValue(RegisterValue v) {
        return v.hasVector() ? vector(v.getVector()) : molecule(v.getScalar());
    }

    private static String vector(Vector v) {
        if (v.getComponentsCount() == 0) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.getComponentsCount(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(v.getComponents(i));
        }
        return sb.toString();
    }

    private static String registerList(List<RegisterValue> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(registerValue(values.get(i)));
        }
        return sb.append(']').toString();
    }

    private static String vectorList(List<Vector> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector(values.get(i)));
        }
        return sb.append(']').toString();
    }

    private static String callStack(List<ProcFrame> frames, Program program) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < frames.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            ProcFrame f = frames.get(i);
            String name = program == null ? null : program.labelValueToName.get(f.getLabelHash());
            sb.append(name != null ? name : "LABEL:" + f.getLabelHash())
              .append('@').append(vector(f.getAbsoluteReturnIp()));
        }
        return sb.append(']').toString();
    }

    /**
     * The declared name of a formal register in the given procedure: data parameters (REF, VAL)
     * take the FDR slots in declaration order, location parameters (LREF, LVAL) the FLR slots.
     *
     * @return the parameter name, or null when the register is not a formal one of that procedure
     */
    private static String parameterName(List<ParamInfo> params, int regId) {
        RegisterBank bank = RegisterBank.forId(regId);
        if (params == null || bank == null || (bank != RegisterBank.FDR && bank != RegisterBank.FLR)) {
            return null;
        }
        int index = RegisterBank.ID_TO_SLOT[regId] - bank.slotOffset();
        boolean location = bank == RegisterBank.FLR;
        int seen = 0;
        for (ParamInfo param : params) {
            boolean isLocation = param.getType() == ParamType.PARAM_TYPE_LREF || param.getType() == ParamType.PARAM_TYPE_LVAL;
            if (isLocation != location) {
                continue;
            }
            if (seen == index) {
                return param.getName();
            }
            seen++;
        }
        return null;
    }

    private static String registerName(int regId) {
        RegisterBank bank = RegisterBank.forId(regId);
        if (bank == null) {
            return "%?" + regId;
        }
        int index = RegisterBank.ID_TO_SLOT[regId] - bank.slotOffset();
        return bank.prefix + index;
    }

    /** Keeps a field on one line and free of tabs and quotes. */
    private static String clean(String s) {
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').replace('"', '\'');
    }

    // ---------------------------------------------------------------- files

    private void openWriters() throws IOException {
        steps = Files.newBufferedWriter(outputDir.resolve("steps.tsv"), StandardCharsets.UTF_8);
        steps.write("tick\torg\tip\trel\taddr\texec_op\texec_args_raw\texec_args\tdp\tcost_e\tcost_s"
                + "\tfailed\tfail_reason\tcond_met\tsrc_file\tsrc_line\tsrc_text\tsrc_label\tsrc_op\tdiffers\tchanged\n");
        List<String> names = new ArrayList<>();
        for (RegisterBank bank : RegisterBank.values()) {
            for (int i = 0; i < bank.count; i++) {
                names.add(bank.prefix.substring(1).toLowerCase() + i);
            }
        }
        slotNames = names.toArray(new String[0]);
        state = Files.newBufferedWriter(outputDir.resolve("state.tsv"), StandardCharsets.UTF_8);
        cellsOut = Files.newBufferedWriter(outputDir.resolve("cells.tsv"), StandardCharsets.UTF_8);
        StringBuilder header = new StringBuilder("tick\tkind");
        int dims = envProps.getDimensions();
        String[] axes = {"x", "y", "z"};
        for (int d = 0; d < dims; d++) {
            header.append('\t').append(d < axes.length && dims <= axes.length ? axes[d] : "c" + d);
        }
        header.append("\ttype\tvalue\tmarker\towner\n");
        cellsOut.write(header.toString());
    }

    private void flushAll() throws IOException {
        steps.flush();
        state.flush();
        cellsOut.flush();
    }

    private void closeAll() {
        if (steps != null) {
            try {
                for (Integer organismId : new ArrayList<>(pendingSteps.keySet())) {
                    flushPendingStep(organismId);
                }
            } catch (IOException e) {
                log.warn("Cannot write the last steps in {}: {}", outputDir, e.getMessage());
            }
        }
        for (BufferedWriter w : new BufferedWriter[] {steps, state, cellsOut}) {
            if (w == null) {
                continue;
            }
            try {
                w.close();
            } catch (IOException e) {
                log.warn("Cannot close a trace file in {}: {}", outputDir, e.getMessage());
            }
        }
        log.info("TRACE finished: chunks={} lastTick={} output={}", chunkCount, lastTick, outputDir);
    }
}
