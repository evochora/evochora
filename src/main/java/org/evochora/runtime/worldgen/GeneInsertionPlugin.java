package org.evochora.runtime.worldgen;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.Instruction.OperandSource;
import org.evochora.runtime.isa.RegisterBank;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.GenomeFrame;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.MutationRecord;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.ScanLineArc;
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
 * Gene insertion birth handler that inserts syntactically correct instruction chains
 * or label detours into NOP (empty) regions of newborn organisms.
 * <p>
 * Called once per newborn organism in the post-Execute phase of each tick. With configurable
 * probability, selects a mutation entry via weighted random choice and inserts the resulting
 * molecule chain into a contiguous empty region of the organism's genome.
 * <p>
 * Instruction entries generate a complete chain: one CODE molecule (opcode) followed by
 * type-correct argument molecules (REGISTER, DATA, LABELREF, etc.) as defined by the
 * instruction's {@link OperandSource} list. This ensures inserted code is syntactically
 * valid, making most mutations neutral or functional rather than immediately lethal.
 * <p>
 * <strong>What a label entry builds.</strong> A label entry places a detour: a LABEL carrying the
 * value of one of the newborn's existing labels A, one instruction generated exactly as an
 * instruction entry generates it, and a {@code JMPI} to a value X. Because the fuzzy label match
 * finds the copy of A as readily as the original, code that jumped to A may land in the detour;
 * the jump at its end sends control on to where the original code would have gone. X is the value
 * of the first LABELREF in a label operand slot in the stretch A heads, otherwise the value of the
 * next block start on A's line, otherwise one of the newborn's other labels, drawn uniformly. Every
 * candidate must differ from A by more than the label index's Hamming tolerance, or the detour's
 * own jump would match its own label and loop. A newborn without a label, and one for which no
 * candidate clears the tolerance, receives nothing.
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
 * build costs, the two further {@link Molecule} records of the jump, and, only where the search
 * falls back to the newborn's other labels, one more visitor lambda.
 * <p>
 * <strong>What it records:</strong> a placed chain reports itself on the newborn as a
 * {@link MutationRecord} naming the cells of the chain in placement order, with the empty cell as
 * the old value and the placed molecule as the new one. An instruction chain reports the kind
 * {@code "instruction-insertion"} and no parameters. A detour reports the kind
 * {@code "label-insertion"} and, as its two parameters, the label value A it copies and the value
 * X it jumps to — neither of which can be told from the written cells alone, because the same two
 * values could have been chosen for any number of reasons. A run that finds no NOP run long enough
 * places nothing and records nothing.
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

    /** Maximum label hash value (19-bit unsigned). */
    private static final int LABEL_HASH_BITS = 19;
    private static final int LABEL_HASH_MAX = (1 << LABEL_HASH_BITS) - 1;

    /** The kind an inserted instruction chain is reported under. */
    private static final String INSTRUCTION_KIND = "instruction-insertion";

    /** The kind an inserted detour is reported under. */
    private static final String LABEL_KIND = "label-insertion";

    /** The instruction a detour ends with, an unconditional jump to a label value. */
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

    /** Opcode of the jump a detour ends with. */
    private final int jumpOpcodeId;

    // --- Reusable buffers (lazy-initialized on first mutate() call) ---
    private int[] coordBuffer;
    private int[] walkPos;
    private int[] perpStrides;

    // --- Scan line infrastructure (reused across mutate() calls) ---
    private final Int2ObjectOpenHashMap<ScanLineInfo> scanLineMap = new Int2ObjectOpenHashMap<>();
    private final ArrayList<ScanLineInfo> scanLinePool = new ArrayList<>();
    private int poolIndex;

    // --- Reservoir sampling state (reset per mutate() call) ---
    private int reservoirLabelHash;
    private int reservoirLabelCount;
    private int reservoirLabelPerpKey;
    private int reservoirLabelDvCoord;

    // --- Jump target search state (reset per detour) ---
    private int detourJumpTarget;
    private int fallbackTargetValue;
    private int fallbackTargetCount;

    // --- NOP run selection state (reset per mutate() call) ---
    private ScanLineInfo selectedNopScanLine;
    private int selectedNopDvStart;
    private int nopCandidateCount;

    // --- Chain buffer (cleared per mutate() call) ---
    private final List<Molecule> chainBuffer = new ArrayList<>();

    /** Collects the record of a placed chain; reused so that a birth allocates only the record itself. */
    private final MutationRecord.Builder recordBuilder = new MutationRecord.Builder();

    /**
     * The newborn's genome in the machine's reading frame; built only for a detour, and kept so
     * that such a build reuses the buffers of the one before.
     */
    private final GenomeFrame frame = new GenomeFrame();

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
     * Label entry: inserts a detour of a copied label, one instruction and a jump onwards.
     * The instruction is drawn from the same kind of description an instruction entry carries,
     * so that the body of a detour is code of the same shape as a plain insertion.
     *
     * @param opcodeIds Resolved opcode IDs of the instruction in the detour's middle.
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
     * Resolves the opcode of the jump a detour ends with.
     *
     * @return The opcode ID of {@value #JUMP_INSTRUCTION}.
     * @throws IllegalStateException if the instruction set does not carry that instruction, which
     *                               means the registry was not initialized.
     */
    private static int resolveJumpOpcode() {
        Integer id = Instruction.getInstructionIdByName(JUMP_INSTRUCTION);
        if (id == null) {
            throw new IllegalStateException("The instruction set carries no " + JUMP_INSTRUCTION
                    + ", which a label entry's detour ends with");
        }
        return id;
    }

    /**
     * Parses a single entry from HOCON config.
     * <p>
     * Both entry types describe the instruction they generate the same way, through
     * {@code instructions} and {@code args}; a label entry is marked by {@code type = "label"} and
     * wraps that instruction in a detour. A setting the entry type does not know is rejected, so
     * that a stale name fails loudly instead of being ignored.
     *
     * @param entryConfig The entry configuration.
     * @return The parsed mutation entry.
     * @throws IllegalArgumentException if the entry names an unknown type, carries an unaccepted
     *                                  key, or lacks a setting its type requires.
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
     * opcode and its operands. A label entry first determines the value the detour jumps to, which
     * draws only where the search falls back to the newborn's other labels, and then draws the
     * opcode and operands of the instruction in the detour's middle. The NOP run is drawn last,
     * by a reservoir over the runs in the order the scan lines are walked.
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
            if (!appendInstruction(ie.opcodeIds(), ie.operandSourcesByOpcode(), ie.argConfig(), dims)) {
                LOG.debug("tick={} Organism {} gene insertion: chain build failed (missing arg config)", child.getBirthTick(), childId);
                chainBuffer.clear();
                return;
            }
        } else if (entry instanceof LabelEntry le) {
            if (!buildDetourChain(le, child, env, dv, dvDim, shape[dvDim], dims)) {
                chainBuffer.clear();
                return;
            }
        }

        if (chainBuffer.isEmpty()) {
            return;
        }

        if (!selectNopRun(env, dvDim, chainBuffer.size(), shape[dvDim])) {
            LOG.debug("tick={} Organism {} gene insertion: no NOP area of length {} found", child.getBirthTick(), childId, chainBuffer.size());
            return;
        }

        int dvStep = dv[dvDim];
        if (dvStep < 0) {
            selectedNopDvStart = (selectedNopDvStart + chainBuffer.size() - 1) % shape[dvDim];
        }
        // A detour carries the label it copies and the value it jumps to, neither of which the
        // written cells alone say anything about
        if (entry instanceof LabelEntry) {
            recordBuilder.start(getClass().getName(), LABEL_KIND, dv)
                    .param(reservoirLabelHash)
                    .param(detourJumpTarget);
        } else {
            recordBuilder.start(getClass().getName(), INSTRUCTION_KIND, dv);
        }
        placeChain(env, childId, dvDim, dvStep, shape[dvDim]);
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
     * @return {@code true} if the instruction was appended, {@code false} if the argument config
     *         does not cover a required operand type.
     */
    private boolean appendInstruction(List<Integer> opcodeIds,
                                      List<List<OperandSource>> operandSourcesByOpcode,
                                      ArgumentConfig argConfig,
                                      int dims) {
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
                    int hash = reservoirLabelHash >= 0
                            ? reservoirLabelHash
                            : random.nextInt(LABEL_HASH_MAX + 1);
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
     * Builds a detour in {@link #chainBuffer}: the value of an existing label, one instruction and
     * a jump onwards.
     * <p>
     * The label value is the one the reservoir of {@link #buildScanLines} sampled; the value the
     * jump carries is chosen by {@link #selectJumpTarget} and kept in {@link #detourJumpTarget}, as
     * the second parameter of the record a placed detour reports. A newborn without a label and one
     * for which no jump target qualifies receive nothing.
     *
     * @param entry The label entry.
     * @param child The newborn organism.
     * @param env The simulation environment.
     * @param dv The newborn's direction vector.
     * @param dvDim The DV dimension index.
     * @param shapeDvDim The environment size along the DV dimension.
     * @param dims Number of environment dimensions.
     * @return {@code true} if the chain was built, {@code false} if nothing is to be placed.
     */
    private boolean buildDetourChain(LabelEntry entry, Organism child, Environment env, int[] dv,
                                     int dvDim, int shapeDvDim, int dims) {
        if (reservoirLabelHash < 0) {
            LOG.debug("tick={} Organism {} insertion: no label to build a detour from",
                    child.getBirthTick(), child.getId());
            return false;
        }

        int target = selectJumpTarget(child, env, dv, dvDim, shapeDvDim);
        if (target < 0) {
            LOG.debug("tick={} Organism {} insertion: no jump target outside the tolerance of label {}",
                    child.getBirthTick(), child.getId(), reservoirLabelHash);
            return false;
        }
        detourJumpTarget = target;

        chainBuffer.add(new Molecule(Config.TYPE_LABEL, reservoirLabelHash));
        if (!appendInstruction(entry.opcodeIds(), entry.operandSourcesByOpcode(), entry.argConfig(), dims)) {
            LOG.debug("tick={} Organism {} insertion: detour build failed (missing arg config)",
                    child.getBirthTick(), child.getId());
            return false;
        }
        chainBuffer.add(new Molecule(Config.TYPE_CODE, jumpOpcodeId & Config.VALUE_MASK));
        chainBuffer.add(new Molecule(Config.TYPE_LABELREF, target));
        return true;
    }

    /**
     * Chooses the value a detour's jump carries, so that control leaves the detour for where the
     * code behind the copied label goes on.
     * <p>
     * Three sources are tried in order, and the first candidate that clears
     * {@link #acceptsAsJumpTarget} wins:
     * <ol>
     *   <li>the value of the first LABELREF standing in a label operand slot in the stretch the
     *       sampled label heads — the walk runs along the direction vector from that label to the
     *       next block start on its line or to the end of the newborn's extent on it, and the
     *       reading frame says which cells are operand slots and which LABEL cells open a block;</li>
     *   <li>the value of that next block start, where the code behind the label falls through to;</li>
     *   <li>one of the newborn's other labels, drawn uniformly by one reservoir pass over the
     *       owned cells in flat-index order.</li>
     * </ol>
     *
     * @param child The newborn organism.
     * @param env The simulation environment.
     * @param dv The newborn's direction vector.
     * @param dvDim The DV dimension index.
     * @param shapeDvDim The environment size along the DV dimension.
     * @return The chosen value, or {@code -1} if no candidate qualifies.
     */
    private int selectJumpTarget(Organism child, Environment env, int[] dv, int dvDim, int shapeDvDim) {
        ScanLineInfo line = scanLineMap.get(reservoirLabelPerpKey);
        if (line == null) {
            return -1;
        }
        int childId = child.getId();
        int sourceHash = reservoirLabelHash;
        int tolerance = env.getLabelIndex().getStrategy().getTolerance();
        int dvStep = dv[dvDim];

        frame.build(env, childId, child.getInitialPosition(), dv);

        // The cells of the stretch, the sampled label itself included
        int available = (dvStep > 0)
                ? toroidalForwardDistance(reservoirLabelDvCoord, line.walkEnd, shapeDvDim)
                : toroidalForwardDistance(line.walkStart, reservoirLabelDvCoord, shapeDvDim);

        env.properties.flatIndexToCoordinates(line.sampleFlatIndex, walkPos);
        int dvPos = reservoirLabelDvCoord;
        int labelRefValue = -1;
        int nextBlockStart = -1;

        for (int offset = 1; offset < available; offset++) {
            dvPos += dvStep;
            if (dvPos >= shapeDvDim) {
                dvPos -= shapeDvDim;
            } else if (dvPos < 0) {
                dvPos += shapeDvDim;
            }
            walkPos[dvDim] = dvPos;
            int moleculeInt = env.getMoleculeIntAt(walkPos);
            int type = moleculeInt & Config.TYPE_MASK;
            if (type != Config.TYPE_LABEL && type != Config.TYPE_LABELREF) {
                continue;
            }
            GenomeFrame.Slot slot = frame.slot(env.properties.toFlatIndex(walkPos));
            if (type == Config.TYPE_LABEL
                    && slot == GenomeFrame.Slot.NONE
                    && env.getOwnerIdAt(walkPos) == childId) {
                nextBlockStart = moleculeInt & Config.VALUE_MASK;
                break;
            }
            if (type == Config.TYPE_LABELREF && slot == GenomeFrame.Slot.LABEL && labelRefValue < 0) {
                labelRefValue = moleculeInt & Config.VALUE_MASK;
            }
        }

        if (labelRefValue >= 0 && acceptsAsJumpTarget(labelRefValue, sourceHash, tolerance)) {
            return labelRefValue;
        }
        if (nextBlockStart >= 0 && acceptsAsJumpTarget(nextBlockStart, sourceHash, tolerance)) {
            return nextBlockStart;
        }
        return drawOtherLabel(env, childId, sourceHash, tolerance);
    }

    /**
     * Draws one of the newborn's labels that can serve as a detour's jump target.
     * <p>
     * One reservoir pass over the owned cells in flat-index order, so that the draw does not depend
     * on the order in which the genome's cells were written.
     *
     * @param env The simulation environment.
     * @param childId The newborn whose labels are considered.
     * @param sourceHash The label value the detour copies.
     * @param tolerance The label index's Hamming tolerance.
     * @return The drawn value, or {@code -1} if no label qualifies.
     */
    private int drawOtherLabel(Environment env, int childId, int sourceHash, int tolerance) {
        fallbackTargetValue = -1;
        fallbackTargetCount = 0;
        env.visitCellsOwnedBy(childId, cell -> {
            int moleculeInt = cell.moleculeInt();
            if ((moleculeInt & Config.TYPE_MASK) != Config.TYPE_LABEL) {
                return;
            }
            int value = moleculeInt & Config.VALUE_MASK;
            if (!acceptsAsJumpTarget(value, sourceHash, tolerance)) {
                return;
            }
            fallbackTargetCount++;
            if (random.nextInt(fallbackTargetCount) == 0) {
                fallbackTargetValue = value;
            }
        });
        return fallbackTargetValue;
    }

    /**
     * Reports whether a value can be a detour's jump target for a given copied label.
     * <p>
     * The jump has to leave the detour, and the label match is fuzzy: a value the index would
     * resolve to the detour's own label would turn the detour into a loop. The candidate must
     * therefore lie further from the copied label than the index's tolerance reaches.
     *
     * @param candidate The value considered as a jump target.
     * @param sourceHash The label value the detour copies.
     * @param tolerance The label index's Hamming tolerance.
     * @return {@code true} if the candidate is a different value far enough from the copied label.
     */
    private static boolean acceptsAsJumpTarget(int candidate, int sourceHash, int tolerance) {
        return candidate != sourceHash && Integer.bitCount(candidate ^ sourceHash) > tolerance;
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
     * the sampled label's value in {@link #reservoirLabelHash} and where it stands in
     * {@link #reservoirLabelPerpKey} and {@link #reservoirLabelDvCoord}, because a detour walks
     * the code from there.
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
                    reservoirLabelPerpKey = perpKey;
                    reservoirLabelDvCoord = dvCoord;
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
        for (ScanLineInfo line : scanLineMap.values()) {
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
        for (ScanLineInfo line : scanLineMap.values()) {
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

        for (ScanLineInfo line : scanLineMap.values()) {
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
     * @param minLength Minimum required contiguous empty cells.
     * @param shapeDvDim The environment size along the DV dimension.
     * @return {@code true} if a qualifying run was found.
     */
    private boolean selectNopRun(Environment env, int dvDim, int minLength, int shapeDvDim) {
        nopCandidateCount = 0;

        for (ScanLineInfo line : scanLineMap.values()) {
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
                        nopCandidateCount++;
                        if (random.nextInt(nopCandidateCount) == 0) {
                            selectedNopScanLine = line;
                            selectedNopDvStart = nopRunStart;
                        }
                    }
                    nopRunLength = 0;
                    nopRunStart = -1;
                }

                dvPos++;
                if (dvPos >= shapeDvDim) dvPos = 0;
            }
            // Trailing run
            if (nopRunLength >= minLength) {
                nopCandidateCount++;
                if (random.nextInt(nopCandidateCount) == 0) {
                    selectedNopScanLine = line;
                    selectedNopDvStart = nopRunStart;
                }
            }
        }

        return nopCandidateCount > 0;
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
