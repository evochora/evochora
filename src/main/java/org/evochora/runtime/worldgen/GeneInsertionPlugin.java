package org.evochora.runtime.worldgen;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.Instruction.OperandSource;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
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
 * or mutated labels into NOP (empty) regions of newborn organisms.
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
 * Label entries derive a hash from an existing LABEL in the organism's genome (or generate
 * a random one if none exist) and flip a configurable number of random bits, creating new
 * jump targets that are close in Hamming distance to existing ones.
 * <p>
 * <strong>NOP Area Search:</strong> Groups owned cells by scan line (perpendicular to DV),
 * tracks the DV extent per scan line, and walks between minDv and maxDv checking for empty
 * cells ({@code moleculeInt == 0}). This correctly handles the fact that empty cells have
 * no owner and thus never appear in {@code getCellsOwnedBy()}. A qualifying run is selected
 * uniformly at random via reservoir sampling across all scan lines.
 * <p>
 * <strong>Performance:</strong> Near-zero allocation after warmup. Reusable coordinate buffers,
 * ScanLineInfo pooling, in-place DV advancement, and reservoir sampling (instead of list
 * collection) minimize GC pressure. The only per-call allocations are one {@code getShape()}
 * defensive copy and 1-4 {@link Molecule} records for the chain.
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

    // --- Immutable config ---
    private final Random random;
    private final double mutationRate;
    private final List<MutationEntry> entries;
    private final double totalWeight;

    // --- Reusable buffers (lazy-initialized on first mutate() call) ---
    private int[] coordBuffer;
    private int[] walkPos;
    private int[] strides;
    private int[] perpStrides;

    // --- Scan line infrastructure (reused across mutate() calls) ---
    private final Int2ObjectOpenHashMap<ScanLineInfo> scanLineMap = new Int2ObjectOpenHashMap<>();
    private final ArrayList<ScanLineInfo> scanLinePool = new ArrayList<>();
    private int poolIndex;

    // --- Reservoir sampling state (reset per mutate() call) ---
    private int reservoirLabelHash;
    private int reservoirLabelCount;

    // --- NOP run selection state (reset per mutate() call) ---
    private ScanLineInfo selectedNopScanLine;
    private int selectedNopDvStart;
    private int nopCandidateCount;

    // --- Chain buffer (cleared per mutate() call) ---
    private final List<Molecule> chainBuffer = new ArrayList<>();

    // --- DV coordinate collector for shortest-arc computation (reused) ---
    private int[] dvCoordCollector;

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
     * Label entry: derives a label hash from an existing label or generates a random one,
     * then flips a configurable number of bits.
     *
     * @param weight Selection weight.
     * @param bitflips Number of random bits to flip in the hash.
     */
    record LabelEntry(
            double weight,
            int bitflips
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
        /** Any flat index on this scan line, for coordinate reconstruction. */
        int sampleFlatIndex;
        /** Number of owned cells on this scan line. */
        int count;
        /** Start of the shortest arc containing all owned cells (inclusive). */
        int walkStart;
        /** End of the shortest arc containing all owned cells (inclusive). */
        int walkEnd;

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
        this.mutationRate = mutationRate;
        this.entries = new ArrayList<>(entries);
        double w = 0.0;
        for (MutationEntry e : entries) {
            w += e.weight();
        }
        this.totalWeight = w;
    }

    /**
     * Parses a single entry from HOCON config.
     *
     * @param entryConfig The entry configuration.
     * @return The parsed mutation entry.
     */
    private MutationEntry parseEntry(com.typesafe.config.Config entryConfig) {
        double weight = entryConfig.getDouble("weight");
        if (weight <= 0.0) {
            throw new IllegalArgumentException("Entry weight must be positive, got: " + weight);
        }

        // Label entry
        if (entryConfig.hasPath("type") && "label".equals(entryConfig.getString("type"))) {
            int bitflips = entryConfig.getInt("bitflips");
            if (bitflips < 0 || bitflips > LABEL_HASH_BITS) {
                throw new IllegalArgumentException("bitflips must be in [0, " + LABEL_HASH_BITS + "], got: " + bitflips);
            }
            return new LabelEntry(weight, bitflips);
        }

        // Instruction entry
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
        return new InstructionEntry(opcodeIds, operandSourcesByOpcode, weight, argConfig);
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
     * Parses register configuration (DR, PR, FPR banks).
     *
     * @param config The REGISTER sub-config.
     * @return The parsed register config.
     */
    private RegisterConfig parseRegisterConfig(com.typesafe.config.Config config) {
        List<String> bankNames = config.getStringList("banks");
        List<Integer> range = config.getIntList("range");
        int rangeMin = range.get(0);
        int rangeMax = range.get(1);

        List<int[]> banks = new ArrayList<>();
        for (String bank : bankNames) {
            int base = switch (bank.toUpperCase()) {
                case "DR" -> 0;
                case "PR" -> Instruction.PR_BASE;
                case "FPR" -> Instruction.FPR_BASE;
                default -> throw new IllegalArgumentException("Unknown register bank: " + bank);
            };
            banks.add(new int[]{base, rangeMin, rangeMax});
        }
        return new RegisterConfig(banks);
    }

    /**
     * Parses location register configuration (LR bank).
     *
     * @param config The LOCATION_REGISTER sub-config.
     * @return The parsed register config with LR_BASE.
     */
    private RegisterConfig parseLocationRegisterConfig(com.typesafe.config.Config config) {
        List<Integer> range = config.getIntList("range");
        int rangeMin = range.get(0);
        int rangeMax = range.get(1);
        List<int[]> banks = List.of(new int[]{Instruction.LR_BASE, rangeMin, rangeMax});
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
     * via scan-line walk, and places the chain.
     *
     * @param child The newborn organism.
     * @param env The simulation environment.
     */
    void mutate(Organism child, Environment env) {
        int childId = child.getId();
        IntOpenHashSet owned = env.getCellsOwnedBy(childId);
        if (owned == null || owned.isEmpty()) {
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
        computeStrides(shape, dims);
        computePerpStrides(shape, dims, dvDim);
        buildScanLines(owned, env, dvDim);
        resolveWalkRanges(owned, env, dvDim, shape[dvDim]);

        MutationEntry entry = selectEntry();
        chainBuffer.clear();

        if (entry instanceof InstructionEntry ie) {
            if (!buildInstructionChain(ie, dims)) {
                LOG.debug("tick={} Organism {} gene insertion: chain build failed (missing arg config)", child.getBirthTick(), childId);
                return;
            }
        } else if (entry instanceof LabelEntry le) {
            buildLabelChain(le);
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
        placeChain(env, childId, dvDim, dvStep, shape[dvDim]);
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
     * Builds a syntactically correct instruction chain in {@link #chainBuffer}.
     * <p>
     * Picks a random opcode from the entry and generates type-correct argument molecules
     * according to the entry's argument configuration and the instruction's operand sources.
     *
     * @param entry The instruction entry.
     * @param dims Number of environment dimensions (for VECTOR operands).
     * @return {@code true} if the chain was built successfully, {@code false} if the entry's
     *         argument config does not cover a required operand type.
     */
    private boolean buildInstructionChain(InstructionEntry entry, int dims) {
        int opcodeIndex = random.nextInt(entry.opcodeIds().size());
        int opcodeId = entry.opcodeIds().get(opcodeIndex);
        List<OperandSource> sources = entry.operandSourcesByOpcode().get(opcodeIndex);

        chainBuffer.add(new Molecule(Config.TYPE_CODE, opcodeId & Config.VALUE_MASK));

        for (OperandSource source : sources) {
            switch (source) {
                case REGISTER -> {
                    RegisterConfig rc = entry.argConfig().register();
                    if (rc == null) {
                        return false;
                    }
                    chainBuffer.add(generateRegisterMolecule(rc));
                }
                case IMMEDIATE -> {
                    DataConfig dc = entry.argConfig().data();
                    if (dc == null) {
                        return false;
                    }
                    chainBuffer.add(new Molecule(Config.TYPE_DATA, randomInRange(dc.min(), dc.max())));
                }
                case LABEL -> {
                    if (entry.argConfig().labelRef() == null) {
                        return false;
                    }
                    int hash = reservoirLabelHash >= 0
                            ? reservoirLabelHash
                            : random.nextInt(LABEL_HASH_MAX + 1);
                    chainBuffer.add(new Molecule(Config.TYPE_LABELREF, hash));
                }
                case LOCATION_REGISTER -> {
                    RegisterConfig lrc = entry.argConfig().locationRegister();
                    if (lrc == null) {
                        return false;
                    }
                    chainBuffer.add(generateRegisterMolecule(lrc));
                }
                case VECTOR -> {
                    if (entry.argConfig().vector() == null) {
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
     * Builds a label chain in {@link #chainBuffer}.
     * <p>
     * Derives a hash from an existing LABEL in the genome (sampled during
     * {@link #buildScanLines}), or generates a random 19-bit hash if none exist.
     * Then flips the configured number of random bits.
     *
     * @param entry The label entry.
     */
    private void buildLabelChain(LabelEntry entry) {
        int hash = reservoirLabelHash >= 0
                ? reservoirLabelHash
                : random.nextInt(LABEL_HASH_MAX + 1);
        hash = flipBits(hash, entry.bitflips());
        chainBuffer.add(new Molecule(Config.TYPE_LABEL, hash));
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

    /**
     * Flips a specified number of random bits in a label hash.
     * Uses a bitmask to track selected positions (zero allocation).
     *
     * @param hash The original hash.
     * @param bitflips Number of bits to flip.
     * @return The hash with flipped bits, masked to 19-bit range.
     */
    int flipBits(int hash, int bitflips) {
        int selectedBits = 0;
        for (int i = 0; i < bitflips; i++) {
            int bit;
            do {
                bit = random.nextInt(LABEL_HASH_BITS);
            } while ((selectedBits & (1 << bit)) != 0);
            selectedBits |= (1 << bit);
            hash ^= (1 << bit);
        }
        return hash & LABEL_HASH_MAX;
    }

    // ---- Scan line infrastructure ----

    /**
     * Builds scan lines from owned cells and concurrently reservoir-samples a label hash.
     * <p>
     * Groups owned (non-empty) cells by perpendicular coordinate, tracking minDv/maxDv
     * per scan line. Also scans for TYPE_LABEL molecules via reservoir sampling, storing
     * the result in {@link #reservoirLabelHash}.
     *
     * @param owned The child's owned cell set.
     * @param env The simulation environment.
     * @param dvDim The DV dimension index.
     */
    private void buildScanLines(IntOpenHashSet owned, Environment env, int dvDim) {
        scanLineMap.clear();
        poolIndex = 0;
        reservoirLabelHash = -1;
        reservoirLabelCount = 0;

        final int dvDimFinal = dvDim;

        owned.forEach((int flatIndex) -> {
            env.properties.flatIndexToCoordinates(flatIndex, coordBuffer);

            int perpKey = computePerpKey(coordBuffer, dvDimFinal);
            int dvCoord = coordBuffer[dvDimFinal];

            ScanLineInfo line = scanLineMap.get(perpKey);
            if (line == null) {
                line = acquireFromPool();
                line.reset(dvCoord, flatIndex);
                scanLineMap.put(perpKey, line);
            } else {
                line.update(dvCoord);
            }

            // Concurrent reservoir sampling for label hashes
            int moleculeInt = env.getMoleculeInt(flatIndex);
            if ((moleculeInt & Config.TYPE_MASK) == Config.TYPE_LABEL) {
                reservoirLabelCount++;
                if (random.nextInt(reservoirLabelCount) == 0) {
                    reservoirLabelHash = moleculeInt & Config.VALUE_MASK;
                }
            }
        });
    }

    /**
     * Computes the shortest toroidal arc for each scan line's walk range.
     * <p>
     * For scan lines where the raw span (maxDv - minDv + 1) does not exceed half the axis size,
     * the shortest arc is trivially minDv to maxDv. For scan lines that span more than half the
     * axis, the organism wraps around the world boundary. In that case, this method collects the
     * DV coordinates of owned cells on that scan line, sorts them, and finds the largest gap to
     * determine the correct shortest arc.
     *
     * @param owned The child's owned cell set.
     * @param env The simulation environment.
     * @param dvDim The DV dimension index.
     * @param shapeDvDim The environment size along the DV dimension.
     */
    private void resolveWalkRanges(IntOpenHashSet owned, Environment env, int dvDim, int shapeDvDim) {
        boolean anyWrapping = false;
        for (ScanLineInfo line : scanLineMap.values()) {
            line.walkStart = line.minDv;
            line.walkEnd = line.maxDv;
            if (line.maxDv - line.minDv + 1 >= shapeDvDim - 1) {
                anyWrapping = true;
            }
        }

        if (!anyWrapping) {
            return;
        }

        final int dvDimF = dvDim;
        for (var entry : scanLineMap.int2ObjectEntrySet()) {
            ScanLineInfo line = entry.getValue();
            if (line.maxDv - line.minDv + 1 <= shapeDvDim / 2) {
                continue;
            }

            int perpKey = entry.getIntKey();
            ensureDvCollector(line.count);
            final int targetPK = perpKey;
            final int[] idx = {0};

            owned.forEach((int flatIndex) -> {
                env.properties.flatIndexToCoordinates(flatIndex, coordBuffer);
                if (computePerpKey(coordBuffer, dvDimF) == targetPK) {
                    dvCoordCollector[idx[0]++] = coordBuffer[dvDimF];
                }
            });

            int count = idx[0];
            Arrays.sort(dvCoordCollector, 0, count);

            int largestGap = 0;
            int gapAfterIdx = 0;
            for (int i = 1; i < count; i++) {
                int gap = dvCoordCollector[i] - dvCoordCollector[i - 1];
                if (gap > largestGap) {
                    largestGap = gap;
                    gapAfterIdx = i;
                }
            }

            int wrapGap = dvCoordCollector[0] + shapeDvDim - dvCoordCollector[count - 1];
            if (wrapGap > largestGap) {
                gapAfterIdx = 0;
            }

            line.walkStart = dvCoordCollector[gapAfterIdx];
            line.walkEnd = dvCoordCollector[(gapAfterIdx - 1 + count) % count];
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
     * Walks each scan line along its shortest arc ({@link ScanLineInfo#walkStart} to
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
                int flatIdx = computeFlatIndex(coordBuffer);
                int moleculeInt = env.getMoleculeInt(flatIdx);

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
            strides = new int[dims];
            perpStrides = new int[dims];
        }
    }

    /**
     * Computes row-major strides from the world shape.
     *
     * @param shape The world shape array.
     * @param dims Number of dimensions.
     */
    private void computeStrides(int[] shape, int dims) {
        strides[dims - 1] = 1;
        for (int i = dims - 2; i >= 0; i--) {
            strides[i] = strides[i + 1] * shape[i + 1];
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
     * Computes a flat index from coordinates using pre-computed strides.
     *
     * @param coord The coordinate array.
     * @return The flat index.
     */
    private int computeFlatIndex(int[] coord) {
        int index = 0;
        for (int i = 0; i < coord.length; i++) {
            index += coord[i] * strides[i];
        }
        return index;
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
