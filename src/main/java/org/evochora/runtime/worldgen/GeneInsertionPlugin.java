package org.evochora.runtime.worldgen;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Family;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.Instruction.OperandSource;
import org.evochora.runtime.isa.RegisterBank;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.GenomeFlow;
import org.evochora.runtime.model.GenomeFrame;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.MutationRecord;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.ScanLineArc;
import org.evochora.runtime.spi.ILabelMatchingStrategy;
import org.evochora.runtime.spi.IBirthHandler;
import org.evochora.runtime.spi.IRandomProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Gene insertion birth handler that inserts syntactically correct instruction chains into NOP
 * (empty) regions of newborn organisms, either where they stand or in front of a block.
 * <p>
 * Called once per newborn organism in the post-Execute phase of each tick. With configurable
 * probability, selects a mutation entry via weighted random choice and inserts the resulting
 * molecule chain into a contiguous empty region of the organism's genome.
 * <p>
 * Instruction entries generate a complete chain: one CODE molecule (opcode) followed by
 * type-correct argument molecules (REGISTER, DATA, LABELREF, etc.) as defined by the
 * instruction's {@link OperandSource} list. This ensures inserted code is syntactically
 * valid, making most mutations neutral or functional rather than immediately lethal. Such a chain
 * takes effect only where execution passes the empty region it lands in.
 * <p>
 * <strong>What a label entry builds.</strong> A label entry inserts one instruction in front of a
 * block of code that has no room in front of it. It picks one of the newborn's labels A, gives
 * that label a new value A' and writes elsewhere: a LABEL carrying A, one instruction generated as
 * an instruction entry generates it, and a {@code JMPI} to A'. Every reference to A now finds the
 * new label, runs the instruction and arrives at the block it meant, which is unchanged. Four
 * rules keep the insertion well-formed whatever the program is:
 * <ul>
 *   <li><b>A is a jump target and nothing else</b> ({@link GenomeFlow#isJumpTarget}): a label a
 *       location instruction addresses names a place for the data pointer, and moving its value
 *       would move that place without what is kept there.</li>
 *   <li><b>A' differs from A in exactly one bit.</b> The label match prefers an exact own label,
 *       so references to A reach the new label and the closing jump reaches the block. Where the
 *       label matching strategy lets a reference address a value one bit away, one bit also keeps
 *       the block within reach of those references: should the new label mutate away, they fall
 *       back to the block instead of failing. The bit is drawn uniformly among those whose value
 *       no label of the newborn carries and which let no reference address A' that did not
 *       address A ({@link GenomeFlow#drawsNoForeignReference}); a reference that could address A
 *       already may divide its jumps between the labels differently.</li>
 *   <li><b>Execution goes on behind the inserted instruction.</b> A conditional is none, because
 *       a failed test would skip the closing jump, and neither is an instruction that
 *       {@linkplain Instruction#neverFallsThrough(int) never falls through}, behind which
 *       the closing jump is never reached. A wildcard leaves both out; an entry that names one is
 *       rejected.</li>
 *   <li><b>The chain goes where execution does not run on into</b>
 *       ({@link GenomeFlow#reachedByFallThrough}): in an empty region other code passes through,
 *       the closing jump would carry that code's execution off into the block.</li>
 * </ul>
 * A newborn without such a label, without such a bit or without such a region receives nothing.
 * An instruction's own LABEL operand receives A', so that it never refers to the new label.
 * <p>
 * <strong>NOP Area Search:</strong> Groups owned cells by scan line (perpendicular to DV),
 * tracks the DV extent per scan line, and walks the arc the owned cells span on it (see
 * {@link ScanLineArc}) checking for empty cells ({@code moleculeInt == 0}). This correctly handles the fact that empty cells have
 * no owner and thus never appear among the owned cells. A qualifying run is selected
 * uniformly at random via reservoir sampling across all scan lines.
 * <p>
 * <strong>Performance:</strong> Near-zero allocation after warmup. The owned cells are visited
 * through the environment's cell views, and reusable coordinate buffers, ScanLineInfo pooling,
 * in-place DV advancement, and reservoir sampling (instead of list collection) minimize GC
 * pressure. The only per-call allocations are one {@code getShape()} defensive copy, the two
 * visitor lambdas (one per owned-cell pass), 1-4 {@link Molecule} records for the chain and, when
 * a chain is placed, the {@link MutationRecord} handed to the newborn. A label entry adds the
 * defensive copy of the newborn's initial position together with what one {@link GenomeFrame}
 * build costs, two further visitor lambdas (the references and the labels), the three further
 * {@link Molecule} records of the label and the jump, and the record of the renamed label.
 * <p>
 * <strong>What it records:</strong> a placed chain reports itself on the newborn as a
 * {@link MutationRecord} naming the cells of the chain in placement order, with the empty cell as
 * the old value and the placed molecule as the new one. An instruction chain reports the kind
 * {@code "instruction-insertion"} and no parameters. A label entry reports the kind
 * {@code "label-insertion"}, names the renamed label after the cells of the chain, with its old
 * and its new molecule, and carries two parameters: the value A the new label copies and the value
 * A' the block's label received. A run that finds no NOP run long enough places nothing and
 * records nothing.
 * <p>
 * <strong>Thread Safety:</strong> Not thread-safe. Runs in the sequential post-Execute phase of
 * {@code Simulation.tick()}.
 *
 * @see org.evochora.runtime.spi.IBirthHandler
 * @see GeneDuplicationPlugin
 * @see GeneDeletionPlugin
 */
public class GeneInsertionPlugin implements IBirthHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GeneInsertionPlugin.class);

    /** The largest label hash: a label hash is a bit pattern over the whole value field of a cell. */
    private static final int LABEL_HASH_MAX = Config.VALUE_MASK;

    /** The kind an inserted instruction chain is reported under. */
    private static final String INSTRUCTION_KIND = "instruction-insertion";

    /** The kind a label entry's insertion is reported under. */
    private static final String LABEL_KIND = "label-insertion";

    /** The instruction a label entry's chain ends with, an unconditional jump to a label value. */
    private static final String JUMP_INSTRUCTION = "JMPI";

    /** The settings an instruction entry is read for. */
    private static final List<String> INSTRUCTION_ENTRY_KEYS = List.of("instructions", "weight", "args");

    /** The settings a label entry is read for. */
    private static final List<String> LABEL_ENTRY_KEYS = List.of("type", "instructions", "weight", "args");

    // --- Immutable config ---
    private final Random random;
    private final double mutationRate;
    private final List<MutationEntry> entries;
    private final double totalWeight;

    /** Opcode of the jump a label entry's chain ends with. */
    private final int jumpOpcodeId;

    // --- Reusable buffers (lazy-initialized on first mutate() call) ---
    private int[] coordBuffer;
    private int[] walkPos;
    private int[] perpStrides;

    // --- Scan line infrastructure (reused across mutate() calls) ---
    /**
     * Finds a scan line by its perpendicular key. Only for lookups: the map is reused across births
     * and keeps the table size it once grew to, so its iteration order depends on the bodies this
     * instance processed before, and a choice made in that order would differ between a run and
     * its resumed or forked continuation.
     */
    private final Int2ObjectOpenHashMap<ScanLineInfo> scanLineMap = new Int2ObjectOpenHashMap<>();
    /**
     * The scan lines of the current newborn at indices {@code 0} to {@code poolIndex - 1}, in the
     * order in which the flat-index visit of its cells first reached them - an order set by the
     * body alone. Every pass over the scan lines runs in this order.
     */
    private final ArrayList<ScanLineInfo> scanLinePool = new ArrayList<>();
    private int poolIndex;

    // --- Reservoir sampling state (reset per mutate() call) ---
    private int reservoirLabelHash;
    private int reservoirLabelCount;

    // --- Label entry state (reset per label entry) ---
    /** The value of the label a label entry copies. */
    private int copiedLabelValue;
    /** The flat index of that label, which is renamed once the chain is placed. */
    private int copiedLabelFlatIndex;
    /** The value that label is renamed to and the chain's closing jump carries. */
    private int renamedLabelValue;
    /** Number of jump targets the label reservoir has seen. */
    private int jumpTargetCount;
    /** The values of all LABEL molecules the newborn owns; a renamed label must carry none of them. */
    private final IntArrayList ownLabelValues = new IntArrayList();

    // --- NOP run selection state (reset per mutate() call) ---
    private ScanLineInfo selectedNopScanLine;
    private int selectedNopDvStart;
    private int nopCandidateCount;

    // --- Chain buffer (cleared per mutate() call) ---
    private final List<Molecule> chainBuffer = new ArrayList<>();

    /** Collects the record of a placed chain; reused so that a birth allocates only the record itself. */
    private final MutationRecord.Builder recordBuilder = new MutationRecord.Builder();

    /**
     * The newborn's genome in the machine's reading frame; built only for a label entry, and kept
     * so that such a build reuses the buffers of the one before.
     */
    private final GenomeFrame frame = new GenomeFrame();

    /** What the instruction set says about control flow in that genome; used by label entries only. */
    private final GenomeFlow flow = new GenomeFlow();

    // --- DV coordinate collector for arc resolution (reused) ---
    private int[] dvCoordCollector;

    /** Receives the ends of a scan line's arc; reused so that resolving a line allocates nothing. */
    private final ScanLineArc.Result arc = new ScanLineArc.Result();

    // --- Entry types ---

    /**
     * A weighted mutation entry. Either an instruction or a label entry.
     */
    sealed interface MutationEntry permits InstructionEntry, LabelEntry {
        /** Selection weight for weighted random choice. */
        double weight();
    }

    /**
     * Instruction entry: inserts a random instruction from the list with type-correct arguments.
     *
     * @param opcodeIds Resolved opcode IDs.
     * @param operandSourcesByOpcode Cached operand sources per opcode, parallel to opcodeIds.
     * @param weight Selection weight.
     * @param argConfig Argument generation configuration.
     */
    record InstructionEntry(
            List<Integer> opcodeIds,
            List<List<OperandSource>> operandSourcesByOpcode,
            double weight,
            ArgumentConfig argConfig
    ) implements MutationEntry {}

    /**
     * Label entry: inserts one instruction in front of a block, as a copied label, the instruction
     * and a jump to the block's renamed label. The instruction is drawn from the same kind of
     * description an instruction entry carries, so that it is code of the same shape as a plain
     * insertion; an instruction behind which execution may not go on is no such instruction.
     *
     * @param opcodeIds Resolved opcode IDs of the inserted instruction.
     * @param operandSourcesByOpcode Cached operand sources per opcode, parallel to opcodeIds.
     * @param weight Selection weight.
     * @param argConfig Argument generation configuration.
     */
    record LabelEntry(
            List<Integer> opcodeIds,
            List<List<OperandSource>> operandSourcesByOpcode,
            double weight,
            ArgumentConfig argConfig
    ) implements MutationEntry {}

    /**
     * Configuration for generating type-correct arguments.
     *
     * @param register Register config for REGISTER operands (nullable).
     * @param locationRegister Register config for LOCATION_REGISTER operands (nullable).
     * @param data Data config for IMMEDIATE operands (nullable).
     * @param labelRef Label reference mode: "existing" to copy from genome (nullable).
     * @param vector Vector mode: "unit" for unit vectors (nullable).
     */
    record ArgumentConfig(
            RegisterConfig register,
            RegisterConfig locationRegister,
            DataConfig data,
            String labelRef,
            String vector
    ) {}

    /**
     * Configuration for generating register values.
     *
     * @param banks List of [base, rangeMin, rangeMax] triples for each bank.
     */
    record RegisterConfig(List<int[]> banks) {}

    /**
     * Configuration for generating data (immediate) values.
     *
     * @param min Minimum value (inclusive).
     * @param max Maximum value (inclusive).
     */
    record DataConfig(int min, int max) {}

    /**
     * Mutable scan line info for grouping owned cells by perpendicular coordinates.
     * Pooled and reused across {@link #mutate} calls to avoid allocation.
     */
    static class ScanLineInfo {
        /** Minimum DV-dimension coordinate on this scan line. */
        int minDv;
        /** Maximum DV-dimension coordinate on this scan line. */
        int maxDv;
        /** The flat index of one owned cell on this scan line, for coordinate reconstruction. */
        int sampleFlatIndex;
        /** Number of owned cells on this scan line. */
        int count;
        /** Start of the arc this line's owned cells span (inclusive), see {@link ScanLineArc}. */
        int walkStart;
        /** End of the arc this line's owned cells span (inclusive), see {@link ScanLineArc}. */
        int walkEnd;
        /** Start of this line's segment in the shared DV coordinate buffer while walk ranges are resolved. */
        int segmentStart;
        /** Number of DV coordinates already placed in this line's segment. */
        int segmentFill;

        /**
         * Resets this info for a new scan line.
         *
         * @param dvCoord The DV-dimension coordinate of the first cell seen.
         * @param flatIndex The flat index of the first cell seen.
         */
        void reset(int dvCoord, int flatIndex) {
            this.minDv = dvCoord;
            this.maxDv = dvCoord;
            this.sampleFlatIndex = flatIndex;
            this.count = 1;
        }

        /**
         * Updates min/max tracking with a new DV coordinate.
         *
         * @param dvCoord The DV-dimension coordinate of a cell on this scan line.
         */
        void update(int dvCoord) {
            if (dvCoord < minDv) minDv = dvCoord;
            if (dvCoord > maxDv) maxDv = dvCoord;
            count++;
        }
    }

    /**
     * Creates a gene insertion plugin from configuration.
     *
     * @param randomProvider Source of randomness.
     * @param config Configuration containing mutationRate and entries.
     */
    public GeneInsertionPlugin(IRandomProvider randomProvider, com.typesafe.config.Config config) {
        this.random = randomProvider.asJavaRandom();
        this.jumpOpcodeId = resolveJumpOpcode();
        this.mutationRate = config.getDouble("mutationRate");
        if (mutationRate < 0.0 || mutationRate > 1.0) {
            throw new IllegalArgumentException("mutationRate must be in [0.0, 1.0], got: " + mutationRate);
        }

        this.entries = new ArrayList<>();
        double weight = 0.0;

        for (com.typesafe.config.Config entryConfig : config.getConfigList("entries")) {
            MutationEntry entry = parseEntry(entryConfig);
            entries.add(entry);
            weight += entry.weight();
        }

        if (entries.isEmpty()) {
            throw new IllegalArgumentException("entries list must not be empty");
        }
        this.totalWeight = weight;
    }

    /**
     * Convenience constructor for tests.
     *
     * @param randomProvider Source of randomness.
     * @param mutationRate Probability of mutation per newborn (0.0 to 1.0).
     * @param entries Pre-built list of mutation entries.
     */
    GeneInsertionPlugin(IRandomProvider randomProvider, double mutationRate, List<MutationEntry> entries) {
        this.random = randomProvider.asJavaRandom();
        this.jumpOpcodeId = resolveJumpOpcode();
        this.mutationRate = mutationRate;
        this.entries = new ArrayList<>(entries);
        double w = 0.0;
        for (MutationEntry e : entries) {
            w += e.weight();
        }
        this.totalWeight = w;
    }

    /**
     * Resolves the opcode of the jump a label entry's chain ends with.
     *
     * @return The opcode ID of {@value #JUMP_INSTRUCTION}.
     * @throws IllegalStateException if the instruction set does not carry that instruction, which
     *                               means the registry was not initialized.
     */
    private static int resolveJumpOpcode() {
        Integer id = Instruction.getInstructionIdByName(JUMP_INSTRUCTION);
        if (id == null) {
            throw new IllegalStateException("The instruction set carries no " + JUMP_INSTRUCTION
                    + ", which a label entry's chain ends with");
        }
        return id;
    }

    /**
     * Parses a single entry from HOCON config.
     * <p>
     * Both entry types describe the instruction they generate the same way, through
     * {@code instructions} and {@code args}; a label entry is marked by {@code type = "label"} and
     * puts that instruction in front of a block. It inserts only an instruction behind which
     * execution goes on ({@link #goesOnBehind}), because the jump that closes its chain has to be
     * reached: a wildcard leaves the others out, and a list that names one is rejected. A setting
     * the entry type does not know is rejected, so that a stale name fails loudly instead of being
     * ignored.
     *
     * @param entryConfig The entry configuration.
     * @return The parsed mutation entry.
     * @throws IllegalArgumentException if the entry names an unknown type, carries an unaccepted
     *                                  key, lacks a setting its type requires, or is a label entry
     *                                  that names an instruction execution may not go on behind.
     */
    private MutationEntry parseEntry(com.typesafe.config.Config entryConfig) {
        boolean isLabelEntry = false;
        if (entryConfig.hasPath("type")) {
            String type = entryConfig.getString("type");
            if (!"label".equals(type)) {
                throw new IllegalArgumentException("Insertion entry has no type '" + type
                        + "'; the only named type is 'label'.");
            }
            isLabelEntry = true;
        }
        requireEntryKeys(entryConfig, isLabelEntry ? LABEL_ENTRY_KEYS : INSTRUCTION_ENTRY_KEYS);

        double weight = entryConfig.getDouble("weight");
        if (weight <= 0.0) {
            throw new IllegalArgumentException("Entry weight must be positive, got: " + weight);
        }
        if (!entryConfig.hasPath("instructions") || !entryConfig.hasPath("args")) {
            throw new IllegalArgumentException("Insertion entry needs 'instructions' and "
                    + "'args' to generate its instruction from.");
        }

        List<Integer> opcodeIds;
        List<List<OperandSource>> operandSourcesByOpcode;

        Object instrValue = entryConfig.getValue("instructions").unwrapped();
        if ("*".equals(instrValue)) {
            // Wildcard: use all opcode IDs directly (no name roundtrip)
            Map<Integer, String> allInstructions = Instruction.getAllInstructions();
            opcodeIds = new ArrayList<>(allInstructions.keySet());
            operandSourcesByOpcode = new ArrayList<>(opcodeIds.size());
            if (isLabelEntry) {
                opcodeIds.removeIf(id -> !goesOnBehind(id));
            }
            for (int id : opcodeIds) {
                operandSourcesByOpcode.add(Instruction.getOperandSourcesById(id));
            }
        } else {
            List<String> instructionNames = entryConfig.getStringList("instructions");
            opcodeIds = new ArrayList<>(instructionNames.size());
            operandSourcesByOpcode = new ArrayList<>(instructionNames.size());
            for (String name : instructionNames) {
                Integer id = Instruction.getInstructionIdByName(name);
                if (id == null) {
                    throw new IllegalArgumentException("Unknown instruction: " + name);
                }
                if (isLabelEntry && !goesOnBehind(id)) {
                    throw new IllegalArgumentException("A label entry cannot insert " + name
                            + ": execution may not go on behind it, to the jump that closes the chain.");
                }
                opcodeIds.add(id);
                operandSourcesByOpcode.add(Instruction.getOperandSourcesById(id));
            }
        }

        if (opcodeIds.isEmpty()) {
            throw new IllegalArgumentException("instructions list resolved to 0 opcodes");
        }

        ArgumentConfig argConfig = parseArgumentConfig(entryConfig.getConfig("args"));
        return isLabelEntry
                ? new LabelEntry(opcodeIds, operandSourcesByOpcode, weight, argConfig)
                : new InstructionEntry(opcodeIds, operandSourcesByOpcode, weight, argConfig);
    }

    /**
     * Tells whether execution goes on with the cell behind an instruction whenever it runs.
     * <p>
     * It does not behind a conditional, whose failed test skips the next instruction, and not
     * behind an instruction that {@linkplain Instruction#neverFallsThrough(int) never falls
     * through}. A call is no such instruction: its return comes back to that cell.
     *
     * @param opcodeId The instruction opcode ID.
     * @return {@code true} if a label entry may insert the instruction.
     */
    private static boolean goesOnBehind(int opcodeId) {
        return Instruction.getFamilyById(opcodeId) != Family.CONDITIONAL
                && !Instruction.neverFallsThrough(opcodeId);
    }

    /**
     * Requires that an entry carries no setting its type does not read.
     *
     * @param entryConfig The entry configuration.
     * @param accepted The keys the entry type is read for.
     * @throws IllegalArgumentException if the entry carries an unaccepted key.
     */
    private static void requireEntryKeys(com.typesafe.config.Config entryConfig, List<String> accepted) {
        for (String key : entryConfig.root().keySet()) {
            if (!accepted.contains(key)) {
                throw new IllegalArgumentException("Insertion entry has no setting '" + key
                        + "'; accepted names are " + String.join(", ", accepted) + ".");
            }
        }
    }

    /**
     * Parses argument generation configuration from HOCON.
     *
     * @param argsConfig The args sub-config.
     * @return The parsed argument config.
     */
    private ArgumentConfig parseArgumentConfig(com.typesafe.config.Config argsConfig) {
        RegisterConfig register = null;
        RegisterConfig locationRegister = null;
        DataConfig data = null;
        String labelRef = null;
        String vector = null;

        if (argsConfig.hasPath("REGISTER")) {
            register = parseRegisterConfig(argsConfig.getConfig("REGISTER"));
        }
        if (argsConfig.hasPath("LOCATION_REGISTER")) {
            locationRegister = parseLocationRegisterConfig(argsConfig.getConfig("LOCATION_REGISTER"));
        }
        if (argsConfig.hasPath("DATA")) {
            data = parseDataConfig(argsConfig.getConfig("DATA"));
        }
        if (argsConfig.hasPath("LABELREF")) {
            labelRef = argsConfig.getString("LABELREF");
        }
        if (argsConfig.hasPath("VECTOR")) {
            vector = argsConfig.getString("VECTOR");
        }

        return new ArgumentConfig(register, locationRegister, data, labelRef, vector);
    }

    /**
     * Parses register configuration (DR bank, hardcoded).
     *
     * @param config The REGISTER sub-config.
     * @return The parsed register config with {@link RegisterBank#DR} base.
     */
    private RegisterConfig parseRegisterConfig(com.typesafe.config.Config config) {
        List<Integer> range = config.getIntList("range");
        int rangeMin = range.get(0);
        int rangeMax = range.get(1);
        List<int[]> banks = List.of(new int[]{RegisterBank.DR.base, rangeMin, rangeMax});
        return new RegisterConfig(banks);
    }

    /**
     * Parses location register configuration (LR bank).
     *
     * @param config The LOCATION_REGISTER sub-config.
     * @return The parsed register config with {@link RegisterBank#LR} base.
     */
    private RegisterConfig parseLocationRegisterConfig(com.typesafe.config.Config config) {
        List<Integer> range = config.getIntList("range");
        int rangeMin = range.get(0);
        int rangeMax = range.get(1);
        List<int[]> banks = List.of(new int[]{RegisterBank.LR.base, rangeMin, rangeMax});
        return new RegisterConfig(banks);
    }

    /**
     * Parses data (immediate) value configuration.
     *
     * @param config The DATA sub-config.
     * @return The parsed data config.
     */
    private DataConfig parseDataConfig(com.typesafe.config.Config config) {
        int min = config.getInt("min");
        int max = config.getInt("max");
        return new DataConfig(min, max);
    }

    /** {@inheritDoc} */
    @Override
    public void onBirth(Organism child, Environment environment) {
        if (random.nextDouble() >= mutationRate) {
            return;
        }
        mutate(child, environment);
    }

    /**
     * Performs gene insertion for a single newborn organism.
     * <p>
     * Builds scan lines from owned cells (with concurrent label hash reservoir sampling),
     * selects a mutation entry, generates the molecule chain, finds a suitable NOP area
     * via scan-line walk, and places the chain. A chain that is placed is recorded on the child.
     * <p>
     * <strong>Draw order.</strong> The label reservoir runs first, over the owned cells in
     * flat-index order; then the entry is drawn by weight. An instruction entry then draws its
     * opcode and its operands. A label entry first draws the label it copies, by a reservoir over
     * the newborn's jump targets in flat-index order, then the bit the renamed value differs in,
     * and then the opcode and operands of the inserted instruction. The NOP run is drawn
     * last, by a reservoir over the runs in the order the scan lines are walked; a label entry
     * offers that reservoir only the runs execution does not run on into.
     *
     * @param child The newborn organism.
     * @param env The simulation environment.
     */
    void mutate(Organism child, Environment env) {
        int childId = child.getId();
        if (env.countCellsOwnedBy(childId) == 0) {
            LOG.debug("tick={} Organism {} gene insertion: no owned cells", child.getBirthTick(), childId);
            return;
        }

        int[] dv = child.getDv();
        int dvDim = findDvDim(dv);
        if (dvDim == -1) {
            LOG.debug("tick={} Organism {} gene insertion: degenerate DV", child.getBirthTick(), childId);
            return;
        }

        int[] shape = env.getShape();
        int dims = shape.length;
        ensureBuffers(dims);
        computePerpStrides(shape, dims, dvDim);
        buildScanLines(childId, env, dvDim);
        resolveWalkRanges(childId, env, dvDim, shape[dvDim]);

        MutationEntry entry = selectEntry();
        chainBuffer.clear();

        if (entry instanceof InstructionEntry ie) {
            if (!appendInstruction(ie.opcodeIds(), ie.operandSourcesByOpcode(), ie.argConfig(), dims, -1)) {
                LOG.debug("tick={} Organism {} gene insertion: chain build failed (missing arg config)", child.getBirthTick(), childId);
                chainBuffer.clear();
                return;
            }
        } else if (entry instanceof LabelEntry le) {
            if (!buildLabelChain(le, child, env, dv, dvDim, dims)) {
                chainBuffer.clear();
                return;
            }
        }

        if (chainBuffer.isEmpty()) {
            return;
        }

        int dvStep = dv[dvDim];
        boolean isLabelEntry = entry instanceof LabelEntry;
        if (!selectNopRun(env, dvDim, dvStep, chainBuffer.size(), shape[dvDim], isLabelEntry)) {
            LOG.debug("tick={} Organism {} gene insertion: no NOP area of length {} found", child.getBirthTick(), childId, chainBuffer.size());
            return;
        }

        if (dvStep < 0) {
            selectedNopDvStart = (selectedNopDvStart + chainBuffer.size() - 1) % shape[dvDim];
        }
        if (isLabelEntry) {
            recordBuilder.start(getClass().getName(), LABEL_KIND, dv)
                    .param(copiedLabelValue)
                    .param(renamedLabelValue);
        } else {
            recordBuilder.start(getClass().getName(), INSTRUCTION_KIND, dv);
        }
        placeChain(env, childId, dvDim, dvStep, shape[dvDim]);
        if (isLabelEntry) {
            // Only now: a chain that found no room must leave the label it copies as it is
            renameCopiedLabel(env, childId);
        }
        child.recordBirthMutation(recordBuilder.build());
        if (LOG.isDebugEnabled()) {
            env.properties.flatIndexToCoordinates(selectedNopScanLine.sampleFlatIndex, coordBuffer);
            coordBuffer[dvDim] = selectedNopDvStart;
            LOG.debug("tick={} Organism {} gene insertion: placed {} molecules at {}",
                    child.getBirthTick(), childId, chainBuffer.size(), Arrays.toString(coordBuffer));
        }
    }

    /**
     * Selects a mutation entry using weighted random choice.
     *
     * @return The selected entry.
     */
    private MutationEntry selectEntry() {
        double r = random.nextDouble() * totalWeight;
        double cumulative = 0.0;
        for (MutationEntry entry : entries) {
            cumulative += entry.weight();
            if (r < cumulative) {
                return entry;
            }
        }
        return entries.get(entries.size() - 1);
    }

    /**
     * Appends a syntactically correct instruction to {@link #chainBuffer}.
     * <p>
     * Picks a random opcode from the list and generates type-correct argument molecules
     * according to the argument configuration and the instruction's operand sources.
     *
     * @param opcodeIds The opcodes one is drawn from.
     * @param operandSourcesByOpcode The operand sources per opcode, parallel to {@code opcodeIds}.
     * @param argConfig How the arguments are generated.
     * @param dims Number of environment dimensions (for VECTOR operands).
     * @param labelOperand The value a LABEL operand receives, or {@code -1} to take the label the
     *                     reservoir sampled, or a random value when the body has none. A label
     *                     entry passes the value its closing jump carries, so that the inserted
     *                     instruction never refers to the label the chain opens with.
     * @return {@code true} if the instruction was appended, {@code false} if the argument config
     *         does not cover a required operand type.
     */
    private boolean appendInstruction(List<Integer> opcodeIds,
                                      List<List<OperandSource>> operandSourcesByOpcode,
                                      ArgumentConfig argConfig,
                                      int dims,
                                      int labelOperand) {
        int opcodeIndex = random.nextInt(opcodeIds.size());
        int opcodeId = opcodeIds.get(opcodeIndex);
        List<OperandSource> sources = operandSourcesByOpcode.get(opcodeIndex);

        chainBuffer.add(new Molecule(Config.TYPE_CODE, opcodeId & Config.VALUE_MASK));

        for (OperandSource source : sources) {
            switch (source) {
                case REGISTER -> {
                    RegisterConfig rc = argConfig.register();
                    if (rc == null) {
                        return false;
                    }
                    chainBuffer.add(generateRegisterMolecule(rc));
                }
                case IMMEDIATE -> {
                    DataConfig dc = argConfig.data();
                    if (dc == null) {
                        return false;
                    }
                    chainBuffer.add(new Molecule(Config.TYPE_DATA, randomInRange(dc.min(), dc.max())));
                }
                case LABEL -> {
                    if (argConfig.labelRef() == null) {
                        return false;
                    }
                    int hash;
                    if (labelOperand >= 0) {
                        hash = labelOperand;
                    } else if (reservoirLabelHash >= 0) {
                        hash = reservoirLabelHash;
                    } else {
                        hash = random.nextInt(LABEL_HASH_MAX + 1);
                    }
                    chainBuffer.add(new Molecule(Config.TYPE_LABELREF, hash));
                }
                case LOCATION_REGISTER -> {
                    RegisterConfig lrc = argConfig.locationRegister();
                    if (lrc == null) {
                        return false;
                    }
                    chainBuffer.add(generateRegisterMolecule(lrc));
                }
                case VECTOR -> {
                    if (argConfig.vector() == null) {
                        return false;
                    }
                    generateUnitVector(dims);
                }
                case STACK -> { /* no code-stream molecule */ }
            }
        }
        return true;
    }

    /**
     * Builds a label entry's chain in {@link #chainBuffer}: the value of one of the newborn's jump
     * targets, one instruction and a jump to the value that target is renamed to.
     * <p>
     * The label is chosen by {@link #selectLabelToCopy} and the renamed value by
     * {@link #chooseRenamedValue}; both are kept for the record and for {@link #renameCopiedLabel},
     * which runs once the chain is placed. A newborn without a jump target and one whose chosen
     * label has no value to be renamed to receive nothing.
     *
     * @param entry The label entry.
     * @param child The newborn organism.
     * @param env The simulation environment.
     * @param dv The newborn's direction vector.
     * @param dvDim The DV dimension index.
     * @param dims Number of environment dimensions.
     * @return {@code true} if the chain was built, {@code false} if nothing is to be placed.
     */
    private boolean buildLabelChain(LabelEntry entry, Organism child, Environment env, int[] dv,
                                    int dvDim, int dims) {
        int childId = child.getId();
        ILabelMatchingStrategy matching = env.getLabelIndex().getStrategy();

        frame.build(env, childId, child.getInitialPosition(), dv);
        flow.collectReferences(env, frame, childId, dvDim, dv[dvDim]);

        if (!selectLabelToCopy(env, childId, matching)) {
            LOG.debug("tick={} Organism {} insertion: no label that is a jump target and nothing else",
                    child.getBirthTick(), childId);
            return false;
        }
        renamedLabelValue = chooseRenamedValue(matching);
        if (renamedLabelValue < 0) {
            LOG.debug("tick={} Organism {} insertion: no value one bit from label {} is free",
                    child.getBirthTick(), childId, copiedLabelValue);
            return false;
        }

        chainBuffer.add(new Molecule(Config.TYPE_LABEL, copiedLabelValue));
        if (!appendInstruction(entry.opcodeIds(), entry.operandSourcesByOpcode(), entry.argConfig(), dims,
                renamedLabelValue)) {
            LOG.debug("tick={} Organism {} insertion: label entry build failed (missing arg config)",
                    child.getBirthTick(), childId);
            return false;
        }
        chainBuffer.add(new Molecule(Config.TYPE_CODE, jumpOpcodeId & Config.VALUE_MASK));
        chainBuffer.add(new Molecule(Config.TYPE_LABELREF, renamedLabelValue));
        return true;
    }

    /**
     * Draws the label a label entry copies, uniformly among the newborn's jump targets.
     * <p>
     * One reservoir pass over the owned cells in flat-index order, so that the draw does not depend
     * on the order in which the genome's cells were written. A candidate is a LABEL molecule that
     * opens a block — one the reading frame reads as no operand — and whose value is a jump target
     * and nothing else ({@link GenomeFlow#isJumpTarget}). The pass also collects the value of every
     * LABEL molecule the newborn owns into {@link #ownLabelValues}.
     *
     * @param env The simulation environment.
     * @param childId The newborn whose labels are considered.
     * @param matching The run's label matching strategy, which tells whether a reference addresses a label.
     * @return {@code true} if a label was drawn into {@link #copiedLabelValue} and
     *         {@link #copiedLabelFlatIndex}.
     */
    private boolean selectLabelToCopy(Environment env, int childId, ILabelMatchingStrategy matching) {
        ownLabelValues.clear();
        jumpTargetCount = 0;
        env.visitCellsOwnedBy(childId, cell -> {
            int moleculeInt = cell.moleculeInt();
            if ((moleculeInt & Config.TYPE_MASK) != Config.TYPE_LABEL) {
                return;
            }
            int value = moleculeInt & Config.VALUE_MASK;
            ownLabelValues.add(value);
            int flatIndex = env.properties.toFlatIndex(cell.coordinate());
            if (frame.slot(flatIndex) != GenomeFrame.Slot.NONE || !flow.isJumpTarget(value, matching)) {
                return;
            }
            jumpTargetCount++;
            if (random.nextInt(jumpTargetCount) == 0) {
                copiedLabelValue = value;
                copiedLabelFlatIndex = flatIndex;
            }
        });
        return jumpTargetCount > 0;
    }

    /**
     * Chooses the value the copied label is renamed to: its own value with one bit flipped.
     * <p>
     * A bit qualifies if no LABEL molecule of the newborn carries the resulting value — two labels
     * of one value would share the references to it — and if that value draws no reference away
     * from another label ({@link GenomeFlow#drawsNoForeignReference}). The qualifying bits are
     * collected first and one of them is drawn uniformly, with a single random number.
     *
     * @param matching The run's label matching strategy, which tells whether a reference addresses a label.
     * @return The chosen value, or {@code -1} if no bit qualifies.
     */
    private int chooseRenamedValue(ILabelMatchingStrategy matching) {
        int qualifyingBits = 0;
        for (int bit = 0; bit < Config.VALUE_BITS; bit++) {
            int candidate = copiedLabelValue ^ (1 << bit);
            if (!ownLabelValues.contains(candidate)
                    && flow.drawsNoForeignReference(candidate, copiedLabelValue, matching)) {
                qualifyingBits |= 1 << bit;
            }
        }
        if (qualifyingBits == 0) {
            return -1;
        }
        // The drawn number counts qualifying bits from the lowest one up
        int remaining = random.nextInt(Integer.bitCount(qualifyingBits));
        int bit = Integer.numberOfTrailingZeros(qualifyingBits);
        while (remaining > 0) {
            qualifyingBits &= qualifyingBits - 1;
            bit = Integer.numberOfTrailingZeros(qualifyingBits);
            remaining--;
        }
        return copiedLabelValue ^ (1 << bit);
    }

    /**
     * Gives the copied label its new value and appends the change to {@link #recordBuilder}.
     * <p>
     * Everything about the molecule but its value stays as it is.
     *
     * @param env The simulation environment.
     * @param childId The child organism's ID.
     */
    private void renameCopiedLabel(Environment env, int childId) {
        env.properties.flatIndexToCoordinates(copiedLabelFlatIndex, walkPos);
        int oldMoleculeInt = env.getMoleculeIntAt(walkPos);
        Molecule renamed = Molecule.fromInt((oldMoleculeInt & ~Config.VALUE_MASK) | renamedLabelValue);
        recordBuilder.cell(copiedLabelFlatIndex, oldMoleculeInt, renamed.toInt());
        env.setMolecule(renamed, childId, walkPos);
    }

    /**
     * Computes the number of cells from one coordinate to another in the direction of rising
     * coordinates on a toroidal axis, both ends included.
     *
     * @param from Start coordinate.
     * @param to End coordinate.
     * @param axisSize Size of the axis.
     * @return The forward distance including both endpoints.
     */
    private static int toroidalForwardDistance(int from, int to, int axisSize) {
        int d = to - from;
        if (d < 0) {
            d += axisSize;
        }
        return d + 1;
    }

    /**
     * Generates a REGISTER molecule from the given config, selecting a random bank and index.
     *
     * @param config The register config with bank definitions.
     * @return A TYPE_REGISTER molecule with the generated register ID.
     */
    private Molecule generateRegisterMolecule(RegisterConfig config) {
        int[] bank = config.banks().get(random.nextInt(config.banks().size()));
        int base = bank[0];
        int index = randomInRange(bank[1], bank[2]);
        return new Molecule(Config.TYPE_REGISTER, base + index);
    }

    /**
     * Generates unit vector molecules and appends them to the chain buffer.
     * A unit vector has exactly one component set to +1 or -1, all others 0.
     *
     * @param dims Number of dimensions.
     */
    private void generateUnitVector(int dims) {
        int axis = random.nextInt(dims);
        int sign = random.nextBoolean() ? 1 : -1;
        for (int d = 0; d < dims; d++) {
            int value = (d == axis) ? sign : 0;
            chainBuffer.add(new Molecule(Config.TYPE_DATA, value & Config.VALUE_MASK));
        }
    }

    // ---- Scan line infrastructure ----

    /**
     * Builds scan lines from owned cells and concurrently reservoir-samples a label hash.
     * <p>
     * Groups owned (non-empty) cells by perpendicular coordinate, tracking minDv/maxDv
     * per scan line. Also scans for TYPE_LABEL molecules via reservoir sampling, storing
     * the sampled label's value in {@link #reservoirLabelHash}.
     *
     * @param childId The child whose owned cells are grouped, visited in index order.
     * @param env The simulation environment.
     * @param dvDim The DV dimension index.
     */
    private void buildScanLines(int childId, Environment env, int dvDim) {
        scanLineMap.clear();
        poolIndex = 0;
        reservoirLabelHash = -1;
        reservoirLabelCount = 0;

        final int dvDimFinal = dvDim;

        // The visit runs in flat-index order: the choice below must not depend on write history
        env.visitCellsOwnedBy(childId, cell -> {
            System.arraycopy(cell.coordinate(), 0, coordBuffer, 0, coordBuffer.length);

            int perpKey = computePerpKey(coordBuffer, dvDimFinal);
            int dvCoord = coordBuffer[dvDimFinal];

            ScanLineInfo line = scanLineMap.get(perpKey);
            if (line == null) {
                line = acquireFromPool();
                line.reset(dvCoord, env.properties.toFlatIndex(coordBuffer));
                scanLineMap.put(perpKey, line);
            } else {
                line.update(dvCoord);
            }

            // Concurrent reservoir sampling for label hashes
            int moleculeInt = cell.moleculeInt();
            if ((moleculeInt & Config.TYPE_MASK) == Config.TYPE_LABEL) {
                reservoirLabelCount++;
                if (random.nextInt(reservoirLabelCount) == 0) {
                    reservoirLabelHash = moleculeInt & Config.VALUE_MASK;
                }
            }
        });
    }

    /**
     * Determines the walk range of each scan line, the arc the newborn spans on that line.
     * <p>
     * A line whose owned cells cannot reach around the world edge spans the arc from its smallest
     * to its largest DV coordinate, which the grouping pass already knows. Only a line that can
     * reach around it needs the coordinates in between: for those this method collects the DV
     * coordinates of the owned cells, sorts them and hands them to {@link ScanLineArc}, which
     * decides where the body ends and the outside begins.
     *
     * @param childId The child whose owned cells are grouped, visited in index order.
     * @param env The simulation environment.
     * @param dvDim The DV dimension index.
     * @param shapeDvDim The environment size along the DV dimension.
     */
    private void resolveWalkRanges(int childId, Environment env, int dvDim, int shapeDvDim) {
        boolean toroidal = env.properties.isToroidal();
        boolean anyWrapping = false;
        for (int i = 0; i < poolIndex; i++) {
            ScanLineInfo line = scanLinePool.get(i);
            line.walkStart = line.minDv;
            line.walkEnd = line.maxDv;
            if (ScanLineArc.largestGapRuleApplies(line.minDv, line.maxDv, shapeDvDim, toroidal)) {
                anyWrapping = true;
            }
        }

        if (!anyWrapping) {
            return;
        }

        // One pass over the child's cells groups the DV coordinates by scan line: every line owns
        // a segment of one shared buffer, starting at its offset, sized by its cell count.
        int total = 0;
        for (int i = 0; i < poolIndex; i++) {
            ScanLineInfo line = scanLinePool.get(i);
            line.segmentStart = total;
            line.segmentFill = 0;
            total += line.count;
        }
        ensureDvCollector(total);
        final int dvDimF = dvDim;
        env.visitCellsOwnedBy(childId, cell -> {
            System.arraycopy(cell.coordinate(), 0, coordBuffer, 0, coordBuffer.length);
            ScanLineInfo line = scanLineMap.get(computePerpKey(coordBuffer, dvDimF));
            dvCoordCollector[line.segmentStart + line.segmentFill++] = coordBuffer[dvDimF];
        });

        for (int i = 0; i < poolIndex; i++) {
            ScanLineInfo line = scanLinePool.get(i);
            if (!ScanLineArc.largestGapRuleApplies(line.minDv, line.maxDv, shapeDvDim, toroidal)) {
                continue;
            }
            int from = line.segmentStart;
            int count = line.count;
            Arrays.sort(dvCoordCollector, from, from + count);
            ScanLineArc.resolve(dvCoordCollector, from, count, shapeDvDim, toroidal, arc);
            line.walkStart = arc.start;
            line.walkEnd = arc.end;
        }
    }

    /**
     * Ensures the DV coordinate collector buffer has sufficient capacity.
     *
     * @param capacity Required minimum capacity.
     */
    private void ensureDvCollector(int capacity) {
        if (dvCoordCollector == null || dvCoordCollector.length < capacity) {
            dvCoordCollector = new int[capacity];
        }
    }

    /**
     * Selects a NOP run of at least {@code minLength} via reservoir sampling across all scan lines.
     * <p>
     * Walks each scan line along its arc ({@link ScanLineInfo#walkStart} to
     * {@link ScanLineInfo#walkEnd}), checking for empty cells ({@code moleculeInt == 0}).
     * Each qualifying run is a candidate; one is selected uniformly at random. The result is stored
     * in {@link #selectedNopScanLine} and {@link #selectedNopDvStart}.
     * <p>
     * The arc walk correctly handles toroidal wrapping by advancing from walkStart in the positive
     * direction, wrapping at the world boundary if walkStart &gt; walkEnd.
     *
     * @param env The simulation environment.
     * @param dvDim The DV dimension index.
     * @param dvStep The DV step value ({@code dv[dvDim]}).
     * @param minLength Minimum required contiguous empty cells.
     * @param shapeDvDim The environment size along the DV dimension.
     * @param awayFromExecution Whether only a run execution does not run on into qualifies; needs
     *                          {@link #frame} built for the newborn.
     * @return {@code true} if a qualifying run was found.
     */
    private boolean selectNopRun(Environment env, int dvDim, int dvStep, int minLength, int shapeDvDim,
                                 boolean awayFromExecution) {
        nopCandidateCount = 0;

        for (int i = 0; i < poolIndex; i++) {
            ScanLineInfo line = scanLinePool.get(i);
            env.properties.flatIndexToCoordinates(line.sampleFlatIndex, coordBuffer);

            int arcLength = (line.walkEnd >= line.walkStart)
                    ? line.walkEnd - line.walkStart + 1
                    : shapeDvDim - line.walkStart + line.walkEnd + 1;

            int nopRunStart = -1;
            int nopRunLength = 0;
            int dvPos = line.walkStart;

            for (int step = 0; step < arcLength; step++) {
                coordBuffer[dvDim] = dvPos;
                int moleculeInt = env.getMoleculeIntAt(coordBuffer);

                if (moleculeInt == 0) {
                    if (nopRunStart == -1) {
                        nopRunStart = dvPos;
                    }
                    nopRunLength++;
                } else {
                    if (nopRunLength >= minLength) {
                        offerNopRun(env, line, nopRunStart, nopRunLength, dvDim, dvStep, shapeDvDim, awayFromExecution);
                    }
                    nopRunLength = 0;
                    nopRunStart = -1;
                }

                dvPos++;
                if (dvPos >= shapeDvDim) dvPos = 0;
            }
            // Trailing run
            if (nopRunLength >= minLength) {
                offerNopRun(env, line, nopRunStart, nopRunLength, dvDim, dvStep, shapeDvDim, awayFromExecution);
            }
        }

        return nopCandidateCount > 0;
    }

    /**
     * Offers one NOP run to the reservoir of {@link #selectNopRun}.
     * <p>
     * Where only a run away from execution qualifies, the run is asked about at the cell execution
     * would enter it by; the empty cells of the run itself change nothing about the answer. Uses
     * {@link #walkPos}; {@link #coordBuffer} holds the line's coordinates and is left as it is.
     *
     * @param env The simulation environment.
     * @param line The scan line the run lies on.
     * @param runStart The run's smallest DV coordinate, in the direction of rising coordinates.
     * @param runLength The number of cells of the run.
     * @param dvDim The DV dimension index.
     * @param dvStep The DV step value ({@code dv[dvDim]}).
     * @param shapeDvDim The environment size along the DV dimension.
     * @param awayFromExecution Whether a run execution runs on into is passed over.
     */
    private void offerNopRun(Environment env, ScanLineInfo line, int runStart, int runLength,
                             int dvDim, int dvStep, int shapeDvDim, boolean awayFromExecution) {
        if (awayFromExecution) {
            System.arraycopy(coordBuffer, 0, walkPos, 0, walkPos.length);
            int entry = dvStep > 0 ? runStart : (runStart + runLength - 1) % shapeDvDim;
            walkPos[dvDim] = entry;
            int cellsBefore = dvStep > 0
                    ? toroidalForwardDistance(line.walkStart, entry, shapeDvDim) - 1
                    : toroidalForwardDistance(entry, line.walkEnd, shapeDvDim) - 1;
            if (flow.reachedByFallThrough(env, frame, walkPos, dvDim, dvStep, cellsBefore)) {
                return;
            }
        }
        nopCandidateCount++;
        if (random.nextInt(nopCandidateCount) == 0) {
            selectedNopScanLine = line;
            selectedNopDvStart = runStart;
        }
    }

    /**
     * Places the molecule chain from {@link #chainBuffer} into the environment,
     * starting at the selected NOP area and advancing in the DV direction.
     * Uses in-place coordinate advancement with toroidal wrap.
     * <p>
     * Every cell is appended to {@link #recordBuilder} before it is written, so the record names
     * the chain in placement order with the molecule that stood there before. The caller has
     * started the builder with the kind that belongs to the entry.
     *
     * @param env The simulation environment.
     * @param childId The child organism's ID.
     * @param dvDim The DV dimension index.
     * @param dvStep The DV step value ({@code dv[dvDim]}).
     * @param shapeDvDim The environment size along the DV dimension.
     */
    private void placeChain(Environment env, int childId, int dvDim, int dvStep, int shapeDvDim) {
        env.properties.flatIndexToCoordinates(selectedNopScanLine.sampleFlatIndex, walkPos);
        walkPos[dvDim] = selectedNopDvStart;

        for (Molecule mol : chainBuffer) {
            recordBuilder.cell(env.properties.toFlatIndex(walkPos),
                    env.getMoleculeIntAt(walkPos), mol.toInt());
            env.setMolecule(mol, childId, walkPos);
            walkPos[dvDim] += dvStep;
            if (walkPos[dvDim] >= shapeDvDim) {
                walkPos[dvDim] -= shapeDvDim;
            } else if (walkPos[dvDim] < 0) {
                walkPos[dvDim] += shapeDvDim;
            }
        }
    }

    // ---- Coordinate and stride utilities ----

    /**
     * Finds the first non-zero component of the direction vector.
     *
     * @param dv The direction vector.
     * @return The DV dimension index, or -1 if all components are zero.
     */
    private static int findDvDim(int[] dv) {
        for (int i = 0; i < dv.length; i++) {
            if (dv[i] != 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Ensures reusable buffers are initialized for the given dimensionality.
     *
     * @param dims Number of dimensions.
     */
    private void ensureBuffers(int dims) {
        if (coordBuffer == null || coordBuffer.length != dims) {
            coordBuffer = new int[dims];
            walkPos = new int[dims];
            perpStrides = new int[dims];
        }
    }

    /**
     * Computes strides for the perpendicular key calculation, excluding the DV dimension.
     *
     * @param shape The world shape array.
     * @param dims Number of dimensions.
     * @param dvDim The DV dimension index.
     */
    private void computePerpStrides(int[] shape, int dims, int dvDim) {
        int stride = 1;
        for (int i = dims - 1; i >= 0; i--) {
            if (i != dvDim) {
                perpStrides[i] = stride;
                stride *= shape[i];
            } else {
                perpStrides[i] = 0;
            }
        }
    }

    /**
     * Computes a unique perpendicular key from coordinates, excluding the DV dimension.
     *
     * @param coord The coordinate array.
     * @param dvDim The DV dimension index to exclude.
     * @return A unique integer key for the perpendicular coordinate combination.
     */
    private int computePerpKey(int[] coord, int dvDim) {
        int key = 0;
        for (int i = 0; i < coord.length; i++) {
            key += coord[i] * perpStrides[i];
        }
        return key;
    }

    /**
     * Acquires a ScanLineInfo from the pool, or creates a new one if the pool is exhausted.
     * After warmup (first few ticks), this method never allocates.
     *
     * @return A reusable ScanLineInfo instance.
     */
    private ScanLineInfo acquireFromPool() {
        if (poolIndex < scanLinePool.size()) {
            return scanLinePool.get(poolIndex++);
        }
        ScanLineInfo info = new ScanLineInfo();
        scanLinePool.add(info);
        poolIndex++;
        return info;
    }

    /**
     * Returns a random integer in the range [min, max] (inclusive).
     *
     * @param min Minimum value.
     * @param max Maximum value.
     * @return Random value in range.
     */
    private int randomInRange(int min, int max) {
        if (min == max) {
            return min;
        }
        return min + random.nextInt(max - min + 1);
    }

    /** {@inheritDoc} */
    @Override
    public byte[] saveState() {
        return new byte[0];
    }

    /** {@inheritDoc} */
    @Override
    public void loadState(byte[] state) {
        // Stateless plugin - nothing to restore
    }
}
