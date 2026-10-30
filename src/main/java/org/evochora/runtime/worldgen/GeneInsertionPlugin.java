package org.evochora.runtime.worldgen;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.Instruction.OperandSource;
import org.evochora.runtime.isa.RegisterBank;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.GenomeFlow;
import org.evochora.runtime.model.GenomeFrame;
import org.evochora.runtime.model.GenomeLines;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.MutationRecord;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.spi.ILabelMatchingStrategy;
import org.evochora.runtime.spi.IBirthHandler;
import org.evochora.runtime.spi.IRandomProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
 * takes effect only where execution passes the empty region it lands in. Which instruction an entry
 * inserts is drawn by the weights of its {@code instructionWeights} ({@link InstructionWeights}).
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
 *   <li><b>Execution goes on behind the inserted instruction.</b> A conditional skip is none,
 *       because a failed test would skip the closing jump, and neither is an instruction that
 *       {@linkplain Instruction#neverFallsThrough(int) never falls through}, behind which
 *       the closing jump is never reached. Both are left out whatever their weight. A conditional
 *       jump is one: its own label operand receives A' as well, so it reaches the block whether it
 *       jumps or not.</li>
 *   <li><b>The chain goes where execution does not run on into</b>
 *       ({@link GenomeFlow#reachedByFallThrough}): in an empty region other code passes through,
 *       the closing jump would carry that code's execution off into the block.</li>
 * </ul>
 * A newborn without such a label, without such a bit or without such a region receives nothing.
 * An instruction's own LABEL operand receives A', so that it never refers to the new label.
 * <p>
 * <strong>NOP Area Search:</strong> Walks the extent of every line of the newborn's genome (see
 * {@link GenomeLines}) checking for empty cells ({@code moleculeInt == 0}). This correctly handles
 * the fact that empty cells have no owner and thus never appear among the owned cells. A
 * qualifying run is selected uniformly at random via reservoir sampling across all lines.
 * <p>
 * <strong>Performance:</strong> Near-zero allocation after warmup. The owned cells are grouped
 * into lines by {@link GenomeLines}, whose tables survive a birth, and reusable coordinate
 * buffers, in-place DV advancement, and reservoir sampling (instead of list collection) minimize
 * GC pressure. The only per-call allocations are one {@code getShape()} defensive copy, the copies
 * of the newborn's direction vector and initial position, the visitors of the lines' owned-cell
 * visits, 1-4 {@link Molecule} records for the chain and, when a chain is placed, the
 * {@link MutationRecord} handed to the newborn. A label entry adds what one {@link GenomeFrame}
 * build costs, the visitor lambda that collects the references, the three further
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
    private static final List<String> INSTRUCTION_ENTRY_KEYS = List.of("instructionWeights", "weight", "args");

    /** The settings a label entry is read for. */
    private static final List<String> LABEL_ENTRY_KEYS = List.of("type", "instructionWeights", "weight", "args");

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

    /** The lines of the newborn's genome, rebuilt for every insertion. */
    private final GenomeLines lines = new GenomeLines();

    // --- Reservoir sampling state (reset per mutate() call) ---
    private int reservoirLabelHash;

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
    private int selectedNopLine;
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

    // --- Entry types ---

    /**
     * A weighted mutation entry. Either an instruction or a label entry.
     */
    sealed interface MutationEntry permits InstructionEntry, LabelEntry {
        /** Selection weight for weighted random choice. */
        double weight();
    }

    /**
     * Instruction entry: inserts an instruction drawn by its weight, with type-correct arguments.
     *
     * @param opcodes The opcodes the entry draws from, with their weights.
     * @param weight Selection weight.
     * @param argConfig Argument generation configuration.
     */
    record InstructionEntry(
            WeightedOpcodes opcodes,
            double weight,
            ArgumentConfig argConfig
    ) implements MutationEntry {}

    /**
     * Label entry: inserts one instruction in front of a block, as a copied label, the instruction
     * and a jump to the block's renamed label. The instruction is drawn from the same kind of
     * description an instruction entry carries, so that it is code of the same shape as a plain
     * insertion; an instruction behind which execution may not go on is no such instruction.
     *
     * @param opcodes The opcodes of the inserted instruction, with their weights.
     * @param weight Selection weight.
     * @param argConfig Argument generation configuration.
     */
    record LabelEntry(
            WeightedOpcodes opcodes,
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
     * {@code instructionWeights} ({@link InstructionWeights}) and {@code args}; a label entry is
     * marked by {@code type = "label"} and puts that instruction in front of a block. It inserts
     * only an instruction behind which execution goes on ({@link #goesOnBehind}), because the jump
     * that closes its chain has to be reached: the others are left out whatever their weight. An
     * entry left without an opcode of weight above 0 is rejected. A setting the entry type does not
     * know is rejected, so that a stale name fails loudly instead of being ignored.
     *
     * @param entryConfig The entry configuration.
     * @return The parsed mutation entry.
     * @throws IllegalArgumentException if the entry names an unknown type, carries an unaccepted
     *                                  key, lacks a setting its type requires, or is left without an
     *                                  opcode it may insert.
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
        if (!entryConfig.hasPath("instructionWeights") || !entryConfig.hasPath("args")) {
            throw new IllegalArgumentException("Insertion entry needs 'instructionWeights' and "
                    + "'args' to generate its instruction from.");
        }

        InstructionWeights weights = InstructionWeights.fromConfig(
                entryConfig.getConfig("instructionWeights"), "Insertion entry");
        boolean labelEntry = isLabelEntry;
        WeightedOpcodes opcodes = weights.selectRegistered(id -> !labelEntry || goesOnBehind(id));
        if (opcodes.isEmpty()) {
            throw new IllegalArgumentException("Insertion entry" + (isLabelEntry ? " of type 'label'" : "")
                    + " is left without an opcode of weight above 0"
                    + (isLabelEntry ? " that execution goes on behind" : "")
                    + ": remove the entry, or give it instructionWeights that leave an opcode.");
        }

        ArgumentConfig argConfig = parseArgumentConfig(entryConfig.getConfig("args"));
        return isLabelEntry
                ? new LabelEntry(opcodes, weight, argConfig)
                : new InstructionEntry(opcodes, weight, argConfig);
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
        return !Instruction.skipsNext(opcodeId) && !Instruction.neverFallsThrough(opcodeId);
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
     * Builds the newborn's lines, samples a label hash, selects a mutation entry, generates the
     * molecule chain, finds a suitable NOP area by walking the lines, and places the chain. A
     * chain that is placed is recorded on the child.
     * <p>
     * <strong>Draw order.</strong> The label reservoir runs first, over the newborn's LABEL cells
     * in flat-index order; then the entry is drawn by weight. An instruction entry then draws its
     * opcode and its operands. A label entry first draws the label it copies, by a reservoir over
     * the newborn's jump targets in flat-index order, then the bit the renamed value differs in,
     * and then the opcode and operands of the inserted instruction. The NOP run is drawn
     * last, by a reservoir over the runs in the order the lines are walked; a label entry
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
        lines.build(env, childId, dv, child.getInitialPosition());
        int dvDim = lines.dvDim();
        if (dvDim == -1) {
            LOG.debug("tick={} Organism {} gene insertion: degenerate DV", child.getBirthTick(), childId);
            return;
        }

        int[] shape = env.getShape();
        int dims = shape.length;
        ensureBuffers(dims);
        sampleLabelHash(env);

        MutationEntry entry = selectEntry();
        chainBuffer.clear();

        if (entry instanceof InstructionEntry ie) {
            if (!appendInstruction(ie.opcodes(), ie.argConfig(), dims, -1)) {
                LOG.debug("tick={} Organism {} gene insertion: chain build failed (missing arg config)", child.getBirthTick(), childId);
                chainBuffer.clear();
                return;
            }
        } else if (entry instanceof LabelEntry le) {
            if (!buildLabelChain(le, child, env, dims)) {
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
        placeChain(env, childId, dvDim, dvStep);
        if (isLabelEntry) {
            // Only now: a chain that found no room must leave the label it copies as it is
            renameCopiedLabel(env, childId);
        }
        child.recordBirthMutation(recordBuilder.build());
        if (LOG.isDebugEnabled()) {
            env.properties.flatIndexToCoordinates(lines.sampleFlatIndex(selectedNopLine), coordBuffer);
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
     * Draws an opcode by its weight and generates type-correct argument molecules according to the
     * argument configuration and the instruction's operand sources.
     *
     * @param opcodes The opcodes one is drawn from, with their weights.
     * @param argConfig How the arguments are generated.
     * @param dims Number of environment dimensions (for VECTOR operands).
     * @param labelOperand The value a LABEL operand receives, or {@code -1} to take the label the
     *                     reservoir sampled, or a random value when the body has none. A label
     *                     entry passes the value its closing jump carries, so that the inserted
     *                     instruction never refers to the label the chain opens with.
     * @return {@code true} if the instruction was appended, {@code false} if the argument config
     *         does not cover a required operand type.
     */
    private boolean appendInstruction(WeightedOpcodes opcodes,
                                      ArgumentConfig argConfig,
                                      int dims,
                                      int labelOperand) {
        int opcodeId = opcodes.opcodeAt(opcodes.drawIndex(random));
        List<OperandSource> sources = Instruction.getOperandSourcesById(opcodeId);

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
     * @param dims Number of environment dimensions.
     * @return {@code true} if the chain was built, {@code false} if nothing is to be placed.
     */
    private boolean buildLabelChain(LabelEntry entry, Organism child, Environment env, int dims) {
        int childId = child.getId();
        ILabelMatchingStrategy matching = env.getLabelIndex().getStrategy();

        frame.build(env, lines);
        flow.collectReferences(env, frame);

        if (!selectLabelToCopy(env, matching)) {
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
        if (!appendInstruction(entry.opcodes(), entry.argConfig(), dims,
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
     * One reservoir pass over the newborn's LABEL cells, which the lines list in flat-index order,
     * so that the draw does not depend on the order in which the genome's cells were written. A
     * candidate is a LABEL molecule that opens a block — one the reading frame reads as no operand
     * — and whose value is a jump target and nothing else ({@link GenomeFlow#isJumpTarget}). The
     * pass also collects the value of every LABEL molecule the newborn owns into
     * {@link #ownLabelValues}. Uses {@link #walkPos}.
     *
     * @param env The simulation environment.
     * @param matching The run's label matching strategy, which tells whether a reference addresses a label.
     * @return {@code true} if a label was drawn into {@link #copiedLabelValue} and
     *         {@link #copiedLabelFlatIndex}.
     */
    private boolean selectLabelToCopy(Environment env, ILabelMatchingStrategy matching) {
        ownLabelValues.clear();
        jumpTargetCount = 0;
        for (int i = 0; i < lines.labelCount(); i++) {
            int flatIndex = lines.labelFlatIndex(i);
            env.properties.flatIndexToCoordinates(flatIndex, walkPos);
            int value = env.getMoleculeIntAt(walkPos) & Config.VALUE_MASK;
            ownLabelValues.add(value);
            if (frame.slot(flatIndex) != GenomeFrame.Slot.NONE || !flow.isJumpTarget(value, matching)) {
                continue;
            }
            jumpTargetCount++;
            if (random.nextInt(jumpTargetCount) == 0) {
                copiedLabelValue = value;
                copiedLabelFlatIndex = flatIndex;
            }
        }
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

    // ---- Label sampling and NOP run search ----

    /**
     * Reservoir-samples the value of one of the newborn's LABEL cells into
     * {@link #reservoirLabelHash}, {@code -1} if it has none.
     * <p>
     * The lines list the labels in flat-index order, so that the choice does not depend on the
     * order in which the genome's cells were written.
     *
     * @param env The simulation environment.
     */
    private void sampleLabelHash(Environment env) {
        reservoirLabelHash = -1;
        for (int i = 0; i < lines.labelCount(); i++) {
            if (random.nextInt(i + 1) == 0) {
                env.properties.flatIndexToCoordinates(lines.labelFlatIndex(i), coordBuffer);
                reservoirLabelHash = env.getMoleculeIntAt(coordBuffer) & Config.VALUE_MASK;
            }
        }
    }

    /**
     * Selects a NOP run of at least {@code minLength} via reservoir sampling across all lines.
     * <p>
     * Walks each line along its extent ({@link GenomeLines#arcStart} to {@link GenomeLines#arcEnd}),
     * checking for empty cells ({@code moleculeInt == 0}). Each qualifying run is a candidate; one
     * is selected uniformly at random. The result is stored in {@link #selectedNopLine} and
     * {@link #selectedNopDvStart}.
     * <p>
     * The walk correctly handles toroidal wrapping by advancing from the arc's start in the
     * positive direction, wrapping at the world boundary if the arc crosses it.
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

        for (int line = 0; line < lines.lineCount(); line++) {
            env.properties.flatIndexToCoordinates(lines.sampleFlatIndex(line), coordBuffer);

            int arcLength = lines.arcLength(line);

            int nopRunStart = -1;
            int nopRunLength = 0;
            int dvPos = lines.arcStart(line);

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

                dvPos = env.properties.stepAlongAxis(dvPos, dvDim, 1);
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
     * Where only a run away from execution qualifies, a run the organism started in is passed
     * over, because its instruction pointer would start inside what is written there; otherwise
     * the run is asked about at the cell execution would enter it by, and the empty cells of the
     * run itself change nothing about the answer. Uses
     * {@link #walkPos}; {@link #coordBuffer} holds the line's coordinates and is left as it is.
     *
     * @param env The simulation environment.
     * @param line The number of the line the run lies on.
     * @param runStart The run's smallest DV coordinate, in the direction of rising coordinates.
     * @param runLength The number of cells of the run.
     * @param dvDim The DV dimension index.
     * @param dvStep The DV step value ({@code dv[dvDim]}).
     * @param shapeDvDim The environment size along the DV dimension.
     * @param awayFromExecution Whether a run execution runs on into is passed over.
     */
    private void offerNopRun(Environment env, int line, int runStart, int runLength,
                             int dvDim, int dvStep, int shapeDvDim, boolean awayFromExecution) {
        if (awayFromExecution) {
            System.arraycopy(coordBuffer, 0, walkPos, 0, walkPos.length);
            for (int i = 0; i < runLength; i++) {
                walkPos[dvDim] = (runStart + i) % shapeDvDim;
                if (frame.isInitialPosition(env.properties.toFlatIndex(walkPos))) {
                    return;
                }
            }
            int entry = dvStep > 0 ? runStart : (runStart + runLength - 1) % shapeDvDim;
            walkPos[dvDim] = entry;
            if (flow.reachedByFallThrough(env, frame, walkPos)) {
                return;
            }
        }
        nopCandidateCount++;
        if (random.nextInt(nopCandidateCount) == 0) {
            selectedNopLine = line;
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
     */
    private void placeChain(Environment env, int childId, int dvDim, int dvStep) {
        env.properties.flatIndexToCoordinates(lines.sampleFlatIndex(selectedNopLine), walkPos);
        walkPos[dvDim] = selectedNopDvStart;

        for (Molecule mol : chainBuffer) {
            recordBuilder.cell(env.properties.toFlatIndex(walkPos),
                    env.getMoleculeIntAt(walkPos), mol.toInt());
            env.setMolecule(mol, mol.isEmpty() ? 0 : childId, walkPos); // an inserted NOP is the empty cell, which has no owner
            walkPos[dvDim] = env.properties.stepAlongAxis(walkPos[dvDim], dvDim, dvStep);
        }
    }

    // ---- Buffers ----

    /**
     * Ensures reusable buffers are initialized for the given dimensionality.
     *
     * @param dims Number of dimensions.
     */
    private void ensureBuffers(int dims) {
        if (coordBuffer == null || coordBuffer.length != dims) {
            coordBuffer = new int[dims];
            walkPos = new int[dims];
        }
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
