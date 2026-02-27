package org.evochora.runtime.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.spi.IRandomProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents a single programmable agent within the simulation.
 * <p>
 * An Organism is a virtual machine with its own set of registers, pointers, and stacks.
 * It executes a program defined by {@code CODE} molecules in the environment to interact
 * with the world, consume resources, and reproduce.
 * <p>
 * <b>Thread safety:</b> Not thread-safe. During parallel dispatch, each organism is processed
 * by exactly one thread. No organism may be accessed by multiple threads concurrently.
 */
public class Organism {
    private static final Logger LOG = LoggerFactory.getLogger(Organism.class);

    /**
     * A record to hold information about a fork request.
     * @param childIp The initial IP of the child.
     * @param childEnergy The initial energy of the child.
     * @param childDv The initial DV of the child.
     */
    record ForkRequest(int[] childIp, int childEnergy, int[] childDv) {}
    /**
     * A record to hold the result of a fetch operation.
     * @param value The fetched value.
     * @param nextIp The IP of the next instruction.
     */
    public record FetchResult(int value, int[] nextIp) {}
    /**
     * A record to hold instruction execution data for history tracking.
     * @param opcodeId The opcode ID of the executed instruction.
     * @param rawArguments The raw argument values from the environment.
     * @param energyCost The total energy cost for executing this instruction.
     * @param registerValuesBefore Register values before instruction execution (for annotation display).
     *                             Maps register ID to register value (only for registers used as arguments).
     *                             Can be empty but never null.
     */
    public record InstructionExecutionData(
        int opcodeId,
        int[] rawArguments,
        int energyCost,
        int entropyDelta,
        java.util.Map<Integer, Object> registerValuesBefore
    ) {}

    private static final int[] EMPTY_INT_ARRAY = new int[0];

    private final int id;
    private Integer parentId = null;
    private long birthTick = 0L;
    private String programId = "";
    private int[] ip;
    private final List<int[]> dps;
    private int activeDpIndex;
    private int[] dv;
    private int er;
    private int sr; // Entropy Register
    private int mr; // Molecule Marker Register
    private long genomeHash = 0L; // Genome hash computed at birth
    private long deathTick = -1L; // Tick when organism died (-1 if alive)
    private final List<Object> drs;
    private final List<Object> prs;
    private final List<Object> fprs;
    private final List<Object> lrs;
    private final Deque<Object> dataStack;
    private final Deque<int[]> locationStack;
    private final Deque<ProcFrame> callStack;
    private boolean isDead = false;
    private boolean loggingEnabled = false;
    private boolean instructionFailed = false;
    private boolean previousInstructionFailed = false;
    private String failureReason = null;
    private Deque<ProcFrame> failureCallStack;

    /**
     * Represents a single frame on the call stack, created by a CALL instruction.
     * It stores the necessary state to return to the caller correctly.
     */
    public static final class ProcFrame {
        public final String procName;
        public final int[] absoluteReturnIp;
        public final int[] absoluteCallIp;
        public final Object[] savedPrs;
        public final Object[] savedFprs;
        public final java.util.Map<Integer, Integer> fprBindings;

        /**
         * Constructs a new ProcFrame.
         * @param procName The name of the procedure.
         * @param absoluteReturnIp The absolute return IP.
         * @param absoluteCallIp The absolute address of the CALL instruction that created this frame.
         * @param savedPrs The saved PRs.
         * @param savedFprs The saved FPRs.
         * @param fprBindings The FPR bindings.
         */
        public ProcFrame(String procName, int[] absoluteReturnIp, int[] absoluteCallIp, Object[] savedPrs, Object[] savedFprs, java.util.Map<Integer, Integer> fprBindings) {
            this.procName = procName;
            this.absoluteReturnIp = absoluteReturnIp;
            this.absoluteCallIp = absoluteCallIp;
            this.savedPrs = savedPrs;
            this.savedFprs = savedFprs;
            this.fprBindings = fprBindings;
        }
    }
    private boolean skipIpAdvance = false;
    private int[] ipBeforeFetch;
    private int[] dvBeforeFetch;
    private InstructionExecutionData lastInstructionExecution = null;
    private final Logger logger;
    private final Simulation simulation;
    private final int[] initialPosition;
    private final Random random;
    private final int maxEnergy;
    private final int maxEntropy;
    private final int nopOpcodeId;
    private final int maxSkipsPerTick;

    /**
     * Constructs a new Organism. This constructor should only be called via the static factory {@link #create}.
     *
     * @param id The unique identifier for this organism.
     * @param startIp The initial coordinate of the Instruction Pointer (IP).
     * @param initialEnergy The starting energy (ER) of the organism.
     * @param logger The logger instance for logging events.
     * @param simulation The simulation instance this organism belongs to.
     */
    Organism(int id, int[] startIp, int initialEnergy, Logger logger, Simulation simulation) {
        this.id = id;
        this.ip = startIp;
        this.dps = new ArrayList<>(Config.NUM_DATA_POINTERS);
        for (int i = 0; i < Config.NUM_DATA_POINTERS; i++) {
            this.dps.add(Arrays.copyOf(startIp, startIp.length));
        }
        
        // Load limits and constants from simulation config (required, no fallback)
        com.typesafe.config.Config orgConfig = simulation.getOrganismConfig();
        this.maxEnergy = orgConfig.getInt("max-energy");
        this.maxEntropy = orgConfig.getInt("max-entropy");
        this.nopOpcodeId = Instruction.getInstructionIdByName("NOP");
        this.maxSkipsPerTick = orgConfig.hasPath("max-skips-per-tick")
                ? orgConfig.getInt("max-skips-per-tick") : 100;

        this.er = initialEnergy;
        this.sr = 0;
        this.mr = 0;
        this.logger = logger;
        this.simulation = simulation;
        this.dv = new int[startIp.length];
        this.dv[0] = 1; // Default direction: +X
        this.drs = new ArrayList<>(Config.NUM_DATA_REGISTERS);
        for (int i = 0; i < Config.NUM_DATA_REGISTERS; i++) {
            this.drs.add(0);
        }
        this.prs = new ArrayList<>(Config.NUM_PROC_REGISTERS);
        for (int i = 0; i < Config.NUM_PROC_REGISTERS; i++) {
            this.prs.add(0);
        }
        this.fprs = new ArrayList<>(Config.NUM_FORMAL_PARAM_REGISTERS);
        for (int i = 0; i < Config.NUM_FORMAL_PARAM_REGISTERS; i++) {
            this.fprs.add(0);
        }
        this.lrs = new ArrayList<>(Config.NUM_LOCATION_REGISTERS);
        for (int i = 0; i < Config.NUM_LOCATION_REGISTERS; i++) {
            this.lrs.add(new int[startIp.length]);
        }
        this.locationStack = new ArrayDeque<>(Config.LOCATION_STACK_MAX_DEPTH);
        this.dataStack = new ArrayDeque<>(Config.STACK_MAX_DEPTH);
        this.callStack = new ArrayDeque<>(Config.CALL_STACK_MAX_DEPTH);
        this.activeDpIndex = 0;
        this.ipBeforeFetch = Arrays.copyOf(startIp, startIp.length);
        this.dvBeforeFetch = Arrays.copyOf(this.dv, this.dv.length);
        this.initialPosition = Arrays.copyOf(startIp, startIp.length);
        IRandomProvider baseProvider = simulation.getRandomProvider();
        if (baseProvider != null) {
            this.random = baseProvider.deriveFor("organism", id).asJavaRandom();
        } else {
            this.random = new Random(id);
        }
        
    }

    /**
     * Factory method to create a new Organism with a unique ID from the simulation.
     *
     * @param simulation The simulation instance.
     * @param startIp The initial coordinate of the Instruction Pointer.
     * @param initialEnergy The starting energy.
     * @param logger The logger instance.
     * @return A newly created Organism.
     */
    public static Organism create(Simulation simulation, int[] startIp, int initialEnergy, Logger logger) {
        int newId = simulation.getNextOrganismId();
        return new Organism(newId, startIp, initialEnergy, logger, simulation);
    }

    /**
     * Entry point for restoring an organism from serialized state.
     * <p>
     * This is used during simulation resume to reconstruct organisms from
     * persisted checkpoint data. Required fields (id, birthTick) are passed here;
     * optional fields are set via builder methods.
     *
     * @param id Unique organism identifier
     * @param birthTick Tick when organism was created
     * @return Builder for setting remaining fields
     */
    public static RestoreBuilder restore(int id, long birthTick) {
        return new RestoreBuilder(id, birthTick);
    }

    /**
     * Private constructor for restoration - only called by RestoreBuilder.build()
     */
    private Organism(RestoreBuilder b, Simulation simulation) {
        this.id = b.id;
        this.parentId = b.parentId;
        this.birthTick = b.birthTick;
        this.programId = b.programId;
        this.ip = Arrays.copyOf(b.ip, b.ip.length);
        this.dv = Arrays.copyOf(b.dv, b.dv.length);
        this.er = b.er;
        this.sr = b.sr;
        this.mr = b.mr;
        this.genomeHash = b.genomeHash;
        this.deathTick = b.deathTick;

        // Deep copy data pointers
        this.dps = new ArrayList<>(b.dps.size());
        for (int[] dp : b.dps) {
            this.dps.add(Arrays.copyOf(dp, dp.length));
        }
        this.activeDpIndex = b.activeDpIndex;

        // Copy registers (shallow copy is fine, values are immutable Integer or int[])
        this.drs = new ArrayList<>(b.drs);
        this.prs = new ArrayList<>(b.prs);
        this.fprs = new ArrayList<>(b.fprs);
        this.lrs = new ArrayList<>(b.lrs);

        // Copy stacks
        this.dataStack = new ArrayDeque<>(b.dataStack);
        this.locationStack = new ArrayDeque<>(b.locationStack);
        this.callStack = new ArrayDeque<>(b.callStack);

        // Status flags
        this.isDead = b.isDead;
        this.instructionFailed = b.instructionFailed;
        this.failureReason = b.failureReason;
        this.failureCallStack = b.failureCallStack != null
            ? new ArrayDeque<>(b.failureCallStack) : null;

        // Derived fields from simulation
        this.simulation = simulation;
        this.logger = null; // Restored organisms don't have individual loggers
        this.loggingEnabled = false;

        // Load limits and constants from simulation config
        com.typesafe.config.Config orgConfig = simulation.getOrganismConfig();
        this.maxEnergy = orgConfig.getInt("max-energy");
        this.maxEntropy = orgConfig.getInt("max-entropy");
        this.nopOpcodeId = Instruction.getInstructionIdByName("NOP");
        this.maxSkipsPerTick = orgConfig.hasPath("max-skips-per-tick")
                ? orgConfig.getInt("max-skips-per-tick") : 100;

        // Preserve original birth position from checkpoint data
        this.initialPosition = Arrays.copyOf(b.initialPosition, b.initialPosition.length);

        // Initialize random from simulation's random provider
        IRandomProvider baseProvider = simulation.getRandomProvider();
        if (baseProvider != null) {
            this.random = baseProvider.deriveFor("organism", this.id).asJavaRandom();
        } else {
            this.random = new Random(this.id);
        }

        // Per-tick state is reset
        this.skipIpAdvance = false;
        this.ipBeforeFetch = Arrays.copyOf(this.ip, this.ip.length);
        this.dvBeforeFetch = Arrays.copyOf(this.dv, this.dv.length);
        this.lastInstructionExecution = null;
    }

    /**
     * Builder for restoring organism state from serialized data.
     * <p>
     * Use {@link Organism#restore(int, long)} to obtain an instance.
     * This builder is used during simulation resume to reconstruct organisms
     * from persisted checkpoint data.
     */
    public static class RestoreBuilder {
        // Required fields (set in constructor)
        private final int id;
        private final long birthTick;

        // Fields with sensible defaults
        private Integer parentId = null;
        private String programId = "";
        private int[] ip = new int[0];
        private int[] dv = new int[0];
        private int er = 0;
        private int sr = 0;
        private int mr = 0;
        private long genomeHash = 0L;
        private long deathTick = -1L;
        private List<int[]> dps = new ArrayList<>();
        private int activeDpIndex = 0;
        private List<Object> drs = new ArrayList<>();
        private List<Object> prs = new ArrayList<>();
        private List<Object> fprs = new ArrayList<>();
        private List<Object> lrs = new ArrayList<>();
        private Deque<Object> dataStack = new ArrayDeque<>();
        private Deque<int[]> locationStack = new ArrayDeque<>();
        private Deque<ProcFrame> callStack = new ArrayDeque<>();
        private boolean isDead = false;
        private int[] initialPosition;
        private boolean instructionFailed = false;
        private String failureReason = null;
        private Deque<ProcFrame> failureCallStack = null;

        private RestoreBuilder(int id, long birthTick) {
            this.id = id;
            this.birthTick = birthTick;
        }

        /** Sets the parent organism ID. */
        public RestoreBuilder parentId(Integer parentId) {
            this.parentId = parentId;
            return this;
        }

        /** Sets the program ID. */
        public RestoreBuilder programId(String programId) {
            this.programId = programId;
            return this;
        }

        /** Sets the instruction pointer coordinates. */
        public RestoreBuilder ip(int[] ip) {
            this.ip = ip;
            return this;
        }

        /** Sets the direction vector. */
        public RestoreBuilder dv(int[] dv) {
            this.dv = dv;
            return this;
        }

        /** Sets the energy register value. */
        public RestoreBuilder energy(int er) {
            this.er = er;
            return this;
        }

        /** Sets the entropy register value. */
        public RestoreBuilder entropy(int sr) {
            this.sr = sr;
            return this;
        }

        /** Sets the molecule marker register value. */
        public RestoreBuilder marker(int mr) {
            this.mr = mr;
            return this;
        }

        /** Sets the original initial position (birth position) of the organism. */
        public RestoreBuilder initialPosition(int[] initialPosition) {
            this.initialPosition = initialPosition;
            return this;
        }

        /** Sets the genome hash. */
        public RestoreBuilder genomeHash(long genomeHash) {
            this.genomeHash = genomeHash;
            return this;
        }

        /** Sets the tick when the organism died (-1 if alive). */
        public RestoreBuilder deathTick(long deathTick) {
            this.deathTick = deathTick;
            return this;
        }

        /** Sets all data pointer coordinates. */
        public RestoreBuilder dataPointers(List<int[]> dps) {
            this.dps = dps;
            return this;
        }

        /** Sets the active data pointer index. */
        public RestoreBuilder activeDpIndex(int idx) {
            this.activeDpIndex = idx;
            return this;
        }

        /** Sets all data register values. */
        public RestoreBuilder dataRegisters(List<Object> drs) {
            this.drs = drs;
            return this;
        }

        /** Sets all procedure register values. */
        public RestoreBuilder procRegisters(List<Object> prs) {
            this.prs = prs;
            return this;
        }

        /** Sets all formal parameter register values. */
        public RestoreBuilder formalParamRegisters(List<Object> fprs) {
            this.fprs = fprs;
            return this;
        }

        /** Sets all location register values. */
        public RestoreBuilder locationRegisters(List<Object> lrs) {
            this.lrs = lrs;
            return this;
        }

        /** Sets the data stack contents. */
        public RestoreBuilder dataStack(Deque<Object> stack) {
            this.dataStack = stack;
            return this;
        }

        /** Sets the location stack contents, clamping to {@link Config#LOCATION_STACK_MAX_DEPTH}. */
        public RestoreBuilder locationStack(Deque<int[]> stack) {
            if (stack.size() > Config.LOCATION_STACK_MAX_DEPTH) {
                ArrayDeque<int[]> clamped = new ArrayDeque<>(Config.LOCATION_STACK_MAX_DEPTH);
                int kept = 0;
                for (int[] entry : stack) {
                    if (kept >= Config.LOCATION_STACK_MAX_DEPTH) break;
                    clamped.addLast(entry);
                    kept++;
                }
                this.locationStack = clamped;
            } else {
                this.locationStack = stack;
            }
            return this;
        }

        /** Sets the call stack contents. */
        public RestoreBuilder callStack(Deque<ProcFrame> stack) {
            this.callStack = stack;
            return this;
        }

        /** Sets whether the organism is dead. */
        public RestoreBuilder dead(boolean isDead) {
            this.isDead = isDead;
            return this;
        }

        /** Sets the instruction failure state. */
        public RestoreBuilder failed(boolean failed, String reason) {
            this.instructionFailed = failed;
            this.failureReason = reason;
            return this;
        }

        /** Sets the call stack at the time of failure. */
        public RestoreBuilder failureCallStack(Deque<ProcFrame> stack) {
            this.failureCallStack = stack;
            return this;
        }

        /**
         * Builds the Organism instance.
         *
         * @param simulation The simulation this organism belongs to
         * @return Fully constructed Organism
         * @throws IllegalStateException if required fields are missing or invalid
         */
        public Organism build(Simulation simulation) {
            // Validation
            if (simulation == null) {
                throw new IllegalStateException("Simulation cannot be null");
            }
            if (ip == null || ip.length == 0) {
                throw new IllegalStateException("IP must be set for restore");
            }
            if (dv == null || dv.length == 0) {
                throw new IllegalStateException("DV must be set for restore");
            }
            if (ip.length != dv.length) {
                throw new IllegalStateException("IP and DV must have same dimensions");
            }
            if (initialPosition == null || initialPosition.length == 0) {
                throw new IllegalStateException("Initial position must be set for restore");
            }
            if (er < 0 && !isDead) {
                LOG.warn("Organism {} restored with negative energy {} — will be killed on first tick",
                        id, er);
            }
            if (sr < 0) {
                LOG.warn("Organism {} restored with negative entropy {} — state may be corrupted",
                        id, sr);
            }
            return new Organism(this, simulation);
        }
    }

    /**
     * Resets the organism's per-tick state. Called by the VirtualMachine before planning a new instruction.
     */
    public void resetTickState() {
        this.previousInstructionFailed = this.instructionFailed;
        this.instructionFailed = false;
        this.failureReason = null;
        this.failureCallStack = null;
        this.skipIpAdvance = false;
        System.arraycopy(this.ip, 0, this.ipBeforeFetch, 0, this.ip.length);
        System.arraycopy(this.dv, 0, this.dvBeforeFetch, 0, this.dv.length);
        this.lastInstructionExecution = null;
    }

    /**
     * Advances the Instruction Pointer by a given number of steps along the current direction vector.
     *
     * @param steps The number of steps to advance.
     * @param environment The simulation environment.
     */
    public void advanceIpBy(int steps, Environment environment) {
        EnvironmentProperties props = environment.properties;
        boolean isToroidal = props.isToroidal();

        // DV is a unit vector: exactly one component is ±1, rest 0
        int dim = 0;
        int sign = 1;
        for (int i = 0; i < dvBeforeFetch.length; i++) {
            if (dvBeforeFetch[i] != 0) {
                dim = i;
                sign = dvBeforeFetch[i];
                break;
            }
        }

        int dimSize = props.getDimensionSize(dim);
        int dimPos = ip[dim];

        for (int i = 0; i < steps; i++) {
            dimPos += sign;
            if (isToroidal) {
                if (dimPos < 0) dimPos = dimSize - 1;
                else if (dimPos >= dimSize) dimPos = 0;
            }
        }

        ip[dim] = dimPos;
    }

    /**
     * Retrieves the raw integer values of an instruction's arguments from the environment.
     * Uses the organism's {@code ipBeforeFetch} and {@code dvBeforeFetch} as starting position and direction.
     *
     * @param instructionLength The total length of the instruction (opcode + arguments).
     * @param environment The simulation environment.
     * @return A list of raw integer values representing the arguments.
     */
    public int[] getRawArgumentsFromEnvironment(int instructionLength, Environment environment) {
        return getRawArgumentsFromEnvironment(instructionLength, environment, this.ipBeforeFetch, this.dvBeforeFetch);
    }

    /**
     * Retrieves the raw integer values of an instruction's arguments from the environment,
     * starting from an explicit position and advancing along an explicit direction vector.
     * <p>
     * Uses flat-index arithmetic along the unit-vector DV to avoid coordinate array allocations.
     *
     * @param instructionLength The total length of the instruction (opcode + arguments).
     * @param environment The simulation environment.
     * @param fromIp The starting position (opcode location).
     * @param withDv The direction vector for advancing to argument slots.
     * @return Raw integer values representing the arguments.
     */
    public int[] getRawArgumentsFromEnvironment(int instructionLength, Environment environment, int[] fromIp, int[] withDv) {
        int argCount = instructionLength - 1;
        if (argCount <= 0) return EMPTY_INT_ARRAY;

        EnvironmentProperties props = environment.properties;
        boolean isToroidal = props.isToroidal();

        // DV is a unit vector: exactly one component is ±1, rest 0
        int dim = 0;
        int sign = 1;
        for (int i = 0; i < withDv.length; i++) {
            if (withDv[i] != 0) {
                dim = i;
                sign = withDv[i];
                break;
            }
        }

        int dimStride = props.getStride(dim);
        int dimSize = props.getDimensionSize(dim);
        int dimPos = fromIp[dim];

        // Compute base flat index (all dimensions except active)
        int flatIp = 0;
        for (int i = 0; i < fromIp.length; i++) {
            flatIp += fromIp[i] * props.getStride(i);
        }
        int baseFlatIp = flatIp - dimPos * dimStride;

        int[] rawArgs = new int[argCount];
        for (int a = 0; a < argCount; a++) {
            dimPos += sign;
            if (isToroidal) {
                if (dimPos < 0) dimPos = dimSize - 1;
                else if (dimPos >= dimSize) dimPos = 0;
            }
            rawArgs[a] = (dimPos >= 0 && dimPos < dimSize)
                    ? environment.getMoleculeInt(baseFlatIp + dimPos * dimStride)
                    : 0;
        }
        return rawArgs;
    }

    /**
     * Marks the organism as dead and records the reason.
     *
     * @param reason The reason for death.
     */
    public void kill(String reason) {
        this.isDead = true;
        this.deathTick = simulation.getCurrentTick();
        if (this.loggingEnabled) {
            this.loggingEnabled = false;
            this.simulation.onOrganismLoggingChanged(false);
        }
        if (!this.instructionFailed) {
            instructionFailed(reason);
        }
    }

    /**
     * Checks if the IP should not be advanced automatically at the end of a tick.
     * This is typically true after a jump or call instruction.
     *
     * @return {@code true} if the IP advance should be skipped.
     */
    public boolean shouldSkipIpAdvance() {
        return skipIpAdvance;
    }

    /**
     * Fetches the value of an instruction argument from the cell following the given coordinate.
     *
     * @param currentIp The coordinate of the preceding molecule (opcode or another argument).
     * @param environment The simulation environment.
     * @return A {@link FetchResult} containing the fetched value and the coordinate of the next molecule.
     */
    public FetchResult fetchArgument(int[] currentIp, Environment environment) {
        int[] nextIp = getNextInstructionPosition(currentIp, this.dvBeforeFetch, environment);
        Molecule molecule = environment.getMolecule(nextIp);
        return new FetchResult(molecule.toInt(), nextIp);
    }

    /**
     * Fetches the signed scalar value of an instruction argument from the cell following the given coordinate.
     *
     * @param currentIp The coordinate of the preceding molecule.
     * @param environment The simulation environment.
     * @return A {@link FetchResult} containing the signed scalar value and the coordinate of the next molecule.
     */
    public FetchResult fetchSignedArgument(int[] currentIp, Environment environment) {
        int[] nextIp = getNextInstructionPosition(currentIp, this.dvBeforeFetch, environment);
        Molecule molecule = environment.getMolecule(nextIp);
        return new FetchResult(molecule.toScalarValue(), nextIp);
    }

    /**
     * Calculates the coordinate of the next instruction or argument based on the current position and direction.
     *
     * @param currentIp The current coordinate.
     * @param directionVector The direction vector to apply.
     * @param environment The simulation environment (for normalization).
     * @return The normalized coordinate of the next molecule.
     */
    public int[] getNextInstructionPosition(int[] currentIp, int[] directionVector, Environment environment) {
        return environment.properties.getNextPosition(currentIp, directionVector);
    }

    /**
     * Calculates an absolute target coordinate by adding a vector to a starting position.
     *
     * @param startPos The starting coordinate.
     * @param vector The vector to add.
     * @param environment The simulation environment (for normalization).
     * @return The normalized target coordinate.
     */
    public int[] getTargetCoordinate(int[] startPos, int[] vector, Environment environment) {
        return environment.properties.getTargetCoordinate(startPos, vector);
    }

    /**
     * Advances the IP past any non-CODE cells (or NOP) at the current position.
     * Only real CODE instructions (non-NOP) stop the skip. DATA, ENERGY, STRUCTURE,
     * LABEL, empty cells, and NOP are all skipped over.
     * This is used both after instruction execution (instant-skip) and by conditional
     * instructions to find the next real instruction to skip.
     *
     * @param environment The simulation environment.
     */
    public void skipNopCells(Environment environment) {
        EnvironmentProperties props = environment.properties;
        boolean isToroidal = props.isToroidal();

        // Determine active dimension and sign from dvBeforeFetch (unit vector: exactly one component is ±1)
        int dim = 0;
        int sign = 1;
        for (int i = 0; i < dvBeforeFetch.length; i++) {
            if (dvBeforeFetch[i] != 0) {
                dim = i;
                sign = dvBeforeFetch[i];
                break;
            }
        }

        int dimStride = props.getStride(dim);
        int dimSize = props.getDimensionSize(dim);
        int dimPos = ip[dim];

        // Compute flat index: ip[0]*stride[0] + ip[1]*stride[1] + ...
        int flatIp = 0;
        for (int i = 0; i < ip.length; i++) {
            flatIp += ip[i] * props.getStride(i);
        }
        // Base flat index = flat index contribution of all dimensions except the active one
        int baseFlatIp = flatIp - dimPos * dimStride;

        for (int skips = 0; skips < maxSkipsPerTick && !isDead; skips++) {
            // In bounded topology, out-of-bounds reads as empty (CODE:0 = skippable)
            int mol = (dimPos >= 0 && dimPos < dimSize)
                    ? environment.getMoleculeInt(flatIp)
                    : 0;
            if ((mol & Config.TYPE_MASK) == Config.TYPE_CODE
                    && (mol & Config.VALUE_MASK) != nopOpcodeId) {
                ip[dim] = dimPos;
                return;
            }
            dimPos += sign;
            if (isToroidal) {
                if (dimPos < 0) {
                    dimPos = dimSize - 1;
                } else if (dimPos >= dimSize) {
                    dimPos = 0;
                }
            }
            flatIp = baseFlatIp + dimPos * dimStride;
        }
        ip[dim] = dimPos;
        recoverFromStall();
        instructionFailed("Max skips exceeded (" + maxSkipsPerTick + ")");
    }

    /**
     * Recovers the instruction pointer after a stall (max-skip exceeded).
     * <p>
     * If the call stack is non-empty, pops the top frame and restores the IP
     * to the frame's return address, also restoring procedure registers (PRs)
     * to the caller's saved state — matching the RET instruction's semantics.
     * <p>
     * If the call stack is empty, falls back to the organism's initial position
     * (birth position), creating a genome-loop that re-executes from the start.
     * <p>
     * This mechanism smooths the fitness landscape: organisms that occasionally
     * escape their code region can recover and continue useful execution, with
     * the error penalty on each recovery providing proportional selection pressure.
     */
    private void recoverFromStall() {
        if (!callStack.isEmpty()) {
            ProcFrame frame = callStack.pop();
            restorePrs(frame.savedPrs);
            setIp(frame.absoluteReturnIp);
        } else {
            setIp(Arrays.copyOf(initialPosition, initialPosition.length));
        }
    }

    /**
     * Skips the next real instruction following the currently executing one.
     * Only CODE molecules (non-NOP) are considered real instructions; all other
     * molecule types are skipped over to find the actual instruction to skip.
     *
     * @param environment The simulation environment.
     */
    public void skipNextInstruction(Environment environment) {
        // Move IP past current instruction
        int[] currentIp = this.getIpBeforeFetch();
        int currentOpcode = environment.getMolecule(currentIp).value();
        int currentLength = Instruction.getInstructionLengthById(currentOpcode, environment);

        int[] pos = currentIp;
        for (int i = 0; i < currentLength; i++) {
            pos = getNextInstructionPosition(pos, this.getDvBeforeFetch(), environment);
        }
        this.setIp(pos);

        // Skip NOPs at new position
        skipNopCells(environment);
        if (instructionFailed) {
            setSkipIpAdvance(true);
            return;
        }

        // Skip the real instruction
        int nextOpcode = environment.getMolecule(ip).value();
        int lengthToSkip = Instruction.getInstructionLengthById(nextOpcode, environment);
        advanceIpBy(lengthToSkip, environment);

        setSkipIpAdvance(true);
    }

    /**
     * Validates if a given vector is a unit vector (sum of absolute components is 1).
     *
     * @param vector The vector to check.
     * @return {@code true} if it is a unit vector, otherwise {@code false}.
     */
    public boolean isUnitVector(int[] vector) {
        int expected = this.simulation.getEnvironment().getShape().length;
        if (vector.length != expected) {
            this.instructionFailed("Vector has incorrect dimensions: expected " + expected + ", got " + vector.length);
            return false;
        }
        int distance = 0;
        for (int component : vector) {
            distance += Math.abs(component);
        }
        if (distance != 1) {
            this.instructionFailed("Vector is not a unit vector (sum of abs components is " + distance + ")");
            return false;
        }
        return true;
    }

    /**
     * Sets the instruction-failed flag and records the reason.
     *
     * @param reason The reason for the failure.
     */
    public void instructionFailed(String reason) {
        if (!this.instructionFailed) {
            this.instructionFailed = true;
            this.failureReason = reason;
            if (this.callStack != null && !this.callStack.isEmpty()) {
                this.failureCallStack = new ArrayDeque<>(this.callStack);
            }
        }
    }

    // --- Public API for Instructions ---

    /**
     * Sets the value of a Data Register (DR).
     *
     * @param index The index of the register (0-7).
     * @param value The value to set (must be {@code Integer} or {@code int[]}).
     * @return {@code true} on success, {@code false} on failure.
     */
    public boolean setDr(int index, Object value) {
        if (index >= 0 && index < this.drs.size()) {
            if (value instanceof Integer || value instanceof int[]) {
                this.drs.set(index, value);
                return true;
            }
            this.instructionFailed("Attempted to set unsupported type " + (value != null ? value.getClass().getSimpleName() : "null") + " to DR " + index);
            return false;
        }
        this.instructionFailed("DR index out of bounds: " + index);
        return false;
    }

    /**
     * Gets the value of a Data Register (DR).
     *
     * @param index The index of the register (0-7).
     * @return The value of the register, or {@code null} if the index is invalid.
     */
    public Object getDr(int index) {
        if (index >= 0 && index < this.drs.size()) {
            return this.drs.get(index);
        }
        this.instructionFailed("DR index out of bounds: " + index);
        return null;
    }

    /**
     * Sets the Instruction Pointer (IP) to a new coordinate.
     *
     * @param newIp The new coordinate for the IP.
     */
    public void setIp(int[] newIp) { 
        this.ip = newIp; 
    }

    /**
     * Sets the coordinate of a specific Data Pointer (DP).
     *
     * @param index The index of the DP to modify.
     * @param newDp The new coordinate to set.
     * @return {@code true} on success, {@code false} on failure (e.g., invalid index).
     */
    public boolean setDp(int index, int[] newDp) {
        if (index >= 0 && index < this.dps.size()) {
            this.dps.set(index, newDp);
            return true;
        }
        this.instructionFailed("DP index out of bounds: " + index);
        return false;
    }

    /**
     * Gets the coordinate of a specific Data Pointer (DP).
     *
     * @param index The index of the DP to retrieve.
     * @return A copy of the DP's coordinate, or {@code null} if the index is invalid.
     */
    public int[] getDp(int index) {
        if (index >= 0 && index < this.dps.size()) {
            return Arrays.copyOf(dps.get(index), dps.get(index).length);
        }
        this.instructionFailed("DP index out of bounds: " + index);
        return null;
    }

    /**
     * Gets the index of the currently active Data Pointer (DP).
     * @return the active DP index.
     */
    public int getActiveDpIndex() {
        return this.activeDpIndex;
    }

    /**
     * Sets the active Data Pointer (DP) index.
     *
     * @param index Index to activate (0..NUM_DATA_POINTERS-1)
     * @return {@code true} if successful; {@code false} if out of bounds
     */
    public boolean setActiveDpIndex(int index) {
        if (index >= 0 && index < this.dps.size()) {
            this.activeDpIndex = index;
            return true;
        }
        this.instructionFailed("Active DP index out of bounds: " + index);
        return false;
    }

    /**
     * Returns a copy of the active DP coordinate.
     * @return a copy of the active DP coordinate.
     */
    public int[] getActiveDp() {
        return getDp(this.activeDpIndex);
    }

    /**
     * Sets the active DP coordinate.
     * @param newDp The new coordinate.
     * @return true if successful, false otherwise.
     */
    public boolean setActiveDp(int[] newDp) {
        return setDp(this.activeDpIndex, newDp);
    }

    /**
     * Gets a list of all Data Pointers (DPs).
     *
     * @return A new list containing copies of all DP coordinates.
     */
    public List<int[]> getDps() {
        return this.dps.stream()
                .map(dp -> Arrays.copyOf(dp, dp.length))
                .collect(Collectors.toList());
    }

    /**
     * Sets the Direction Vector (DV).
     *
     * @param newDv The new direction vector.
     */
    public void setDv(int[] newDv) { 
        this.dv = newDv; 
    }

    /**
     * Adds energy to the organism's Energy Register (ER), clamped to the maximum allowed.
     *
     * @param amount The amount of energy to add.
     */
    public void addEr(int amount) { 
        this.er = Math.min(this.er + amount, this.maxEnergy); 
    }

    /**
     * Subtracts energy from the organism's Energy Register (ER).
     *
     * @param amount The amount of energy to subtract.
     */
    public void takeEr(int amount) { 
        this.er -= amount; 
    }

    /**
     * Adds entropy to the organism's Entropy Register (SR).
     * The value is clamped to a minimum of 0 (cannot go negative).
     *
     * @param amount The amount of entropy to add (can be negative for dissipation).
     */
    public void addSr(int amount) {
        this.sr = Math.max(0, this.sr + amount);
    }

    /**
     * Subtracts entropy from the organism's Entropy Register (SR).
     * The value is clamped to 0 (cannot go negative).
     *
     * @param amount The amount of entropy to subtract.
     */
    public void takeSr(int amount) { 
        this.sr = Math.max(0, this.sr - amount); 
    }
    
    public int getMaxEnergy() { return maxEnergy; }
    public int getMaxEntropy() { return maxEntropy; }

    /**
     * Sets a flag to prevent the VM from automatically advancing the IP at the end of the tick.
     *
     * @param skip {@code true} to skip the IP advance.
     */
    public void setSkipIpAdvance(boolean skip) { this.skipIpAdvance = skip; }

    /** @return The unique ID of the organism. */
    public int getId() { return id; }
    /** @return The ID of the parent organism, or {@code null} if it has no parent. */
    public Integer getParentId() { return parentId; }
    /** Sets the ID of the parent organism.
     * @param parentId The parent ID.
     */
    public void setParentId(Integer parentId) { 
        this.parentId = parentId;
    }

    /**
     * Checks if a cell, identified by its owner's ID, is accessible to this organism.
     * A cell is considered accessible only if it is owned by the organism itself.
     * Parent-owned cells are treated as foreign.
     *
     * @param ownerId The ID of the cell's owner.
     * @return {@code true} if the cell is accessible (owned by this organism), otherwise {@code false}.
     */
    public boolean isCellAccessible(int ownerId) {
        // A cell is only accessible to its owner.
        return ownerId == this.id;
    }

    /** @return The simulation tick number at which the organism was born. */
    public long getBirthTick() { return birthTick; }
    /** Sets the birth tick of the organism.
     * @param birthTick The birth tick.
     */
    public void setBirthTick(long birthTick) { 
        this.birthTick = birthTick;
    }
    /** @return The program ID associated with this organism. */
    public String getProgramId() { return programId; }
    /** Sets the program ID for this organism.
     * @param programId The program ID.
     */
    public void setProgramId(String programId) { 
        this.programId = programId;
    }
    /** @return A copy of the current Instruction Pointer (IP) coordinate. */
    public int[] getIp() { return Arrays.copyOf(ip, ip.length); }
    /** @return A copy of the IP coordinate as it was at the beginning of the tick. */
    public int[] getIpBeforeFetch() { return Arrays.copyOf(ipBeforeFetch, ipBeforeFetch.length); }
    /** @return A copy of the DV as it was at the beginning of the tick. */
    public int[] getDvBeforeFetch() { return Arrays.copyOf(dvBeforeFetch, dvBeforeFetch.length); }
    /** @return The instruction execution data from the last executed instruction, or null if no instruction was executed. */
    public InstructionExecutionData getLastInstructionExecution() { return lastInstructionExecution; }
    /** Sets the instruction execution data for the last executed instruction.
     * @param data The instruction execution data to store.
     */
    public void setLastInstructionExecution(InstructionExecutionData data) { this.lastInstructionExecution = data; }
    /** @return The current energy level (ER). */
    public int getEr() { return er; }
    /** @return The current entropy level (SR). */
    public int getSr() { return sr; }
    /** @return The current molecule marker (MR). */
    public int getMr() { return mr; }

    /**
     * Sets the molecule marker (MR). The value is masked to 4 bits (0-15).
     * @param value The new marker value.
     */
    public void setMr(int value) {
        this.mr = value & Config.MARKER_VALUE_MASK;
    }

    /** @return The genome hash computed at birth, or 0L if not set. */
    public long getGenomeHash() { return genomeHash; }

    /**
     * Sets the genome hash. Called by FORK instruction and SimulationEngine after placing molecules.
     * @param hash The computed genome hash.
     */
    public void setGenomeHash(long hash) {
        this.genomeHash = hash;
    }

    /** @return The tick when this organism died, or -1L if still alive. */
    public long getDeathTick() { return deathTick; }

    /** @return A copy of the list of Data Registers (DRs). */
    public List<Object> getDrs() { return new ArrayList<>(drs); }
    /** @return true if the organism is dead, false otherwise. */
    public boolean isDead() { return isDead; }
    /** @return true if detailed logging is enabled for this organism. */
    public boolean isLoggingEnabled() { return loggingEnabled; }
    /** Enables or disables detailed logging for this organism.
     * @param loggingEnabled true to enable logging, false to disable.
     */
    public void setLoggingEnabled(boolean loggingEnabled) {
        if (this.loggingEnabled != loggingEnabled) {
            this.loggingEnabled = loggingEnabled;
            this.simulation.onOrganismLoggingChanged(loggingEnabled);
        }
    }
    /** @return true if the current instruction has failed. */
    public boolean isInstructionFailed() { return instructionFailed; }
    /** @return true if the previous tick's instruction failed. Used by IFER/INER conditionals. */
    public boolean wasPreviousInstructionFailed() { return previousInstructionFailed; }
    /** @return The reason for the last instruction failure. */
    public String getFailureReason() { return failureReason; }
    /** @return The logger instance. */
    public Logger getLogger() { return logger; }
    /** @return A copy of the current Direction Vector (DV). */
    public int[] getDv() { return Arrays.copyOf(dv, dv.length); }
    /** @return The simulation instance. */
    public Simulation getSimulation() { return simulation; }
    /** @return A copy of the organism's initial starting position. */
    public int[] getInitialPosition() { return Arrays.copyOf(this.initialPosition, this.initialPosition.length); }
    /** @return A reference to the Data Stack (DS). */
    public Deque<Object> getDataStack() { 
        return this.dataStack;
    }
    /** @return A reference to the Call Stack (CS). */
    public Deque<ProcFrame> getCallStack() { 
        return this.callStack;
    }

    /** @return A copy of the list of Procedure-local Registers (PRs). */
    public List<Object> getPrs() { return new ArrayList<>(this.prs); }

    /**
     * Sets the value of a Procedure-local Register (PR).
     *
     * @param index The index of the register.
     * @param value The value to set.
     * @return {@code true} on success, {@code false} on failure.
     */
    public boolean setPr(int index, Object value) {
        if (index >= 0 && index < this.prs.size()) {
            if (value instanceof Integer || value instanceof int[]) {
                this.prs.set(index, value);
                return true;
            }
            this.instructionFailed("Attempted to set unsupported type " + (value != null ? value.getClass().getSimpleName() : "null") + " to PR " + index);
            return false;
        }
        this.instructionFailed("PR index out of bounds: " + index);
        return false;
    }

    /**
     * Gets the value of a Procedure-local Register (PR).
     *
     * @param index The index of the register.
     * @return The value, or {@code null} on failure.
     */
    public Object getPr(int index) {
        if (index >= 0 && index < this.prs.size()) {
            return this.prs.get(index);
        }
        this.instructionFailed("PR index out of bounds: " + index);
        return null;
    }

    /**
     * Restores the state of all PRs from a snapshot array.
     *
     * @param snapshot The snapshot to restore from.
     */
    public void restorePrs(Object[] snapshot) {
        if (snapshot == null || snapshot.length != this.prs.size()) {
            this.instructionFailed("Invalid PR snapshot size: expected " + this.prs.size() + ", got " + (snapshot == null ? "null" : snapshot.length));
            return;
        }
        for (int i = 0; i < snapshot.length; i++) {
            this.prs.set(i, snapshot[i]);
        }
    }

    /** @return A copy of the list of Formal Parameter Registers (FPRs). */
    public List<Object> getFprs() { return new ArrayList<>(this.fprs); }

    /**
     * Sets the value of a Formal Parameter Register (FPR).
     *
     * @param index The index of the register.
     * @param value The value to set.
     * @return {@code true} on success, {@code false} on failure.
     */
    public boolean setFpr(int index, Object value) {
        if (index >= 0 && index < this.fprs.size()) {
            if (value instanceof Integer || value instanceof int[]) {
                this.fprs.set(index, value);
                return true;
            }
            this.instructionFailed("Attempted to set unsupported type " + (value != null ? value.getClass().getSimpleName() : "null") + " to FPR " + index);
            return false;
        }
        this.instructionFailed("FPR index out of bounds: " + index);
        return false;
    }

    /**
     * Gets the value of a Formal Parameter Register (FPR).
     *
     * @param index The index of the register.
     * @return The value, or {@code null} on failure.
     */
    public Object getFpr(int index) {
        if (index >= 0 && index < this.fprs.size()) {
            return this.fprs.get(index);
        }
        this.instructionFailed("FPR index out of bounds: " + index);
        return null;
    }

    /**
     * Restores the state of all FPRs from a snapshot array.
     *
     * @param snapshot The snapshot to restore from.
     */
    public void restoreFprs(Object[] snapshot) {
        if (snapshot == null || snapshot.length > this.fprs.size()) {
            this.instructionFailed("Invalid FPR snapshot for restore");
            return;
        }
        for (int i = 0; i < snapshot.length; i++) {
            this.fprs.set(i, snapshot[i]);
        }
    }

    /**
     * Gets a copy of the list of Location Registers (LRs).
     *
     * @return A new list containing the vector values of all LRs.
     */
    public List<Object> getLrs() {
        return new ArrayList<>(this.lrs);
    }

    /**
     * Sets the value of a Location Register (LR).
     *
     * @param index The index of the register.
     * @param value The vector value to set.
     * @return {@code true} on success, {@code false} on failure.
     */
    public boolean setLr(int index, int[] value) {
        if (index >= 0 && index < this.lrs.size()) {
            this.lrs.set(index, value);
            return true;
        }
        this.instructionFailed("LR index out of bounds: " + index);
        return false;
    }

    /**
     * Gets the value of a Location Register (LR).
     *
     * @param index The index of the register.
     * @return The vector value, or {@code null} on failure.
     */
    public int[] getLr(int index) {
        if (index >= 0 && index < this.lrs.size()) {
            return (int[]) this.lrs.get(index);
        }
        this.instructionFailed("LR index out of bounds: " + index);
        return null;
    }

    /**
     * Gets a reference to the Location Stack (LS).
     *
     * @return The location stack.
     */
    public Deque<int[]> getLocationStack() {
        return this.locationStack;
    }

    /**
     * Gets the organism-specific random number generator.
     *
     * @return The {@link Random} instance.
     */
    public Random getRandom() { return this.random; }

    /**
     * Gets the call stack as it was at the moment an instruction failure occurred.
     *
     * @return A copy of the call stack at the time of failure.
     */
    public Deque<ProcFrame> getFailureCallStack() { return this.failureCallStack; }

    /**
     * Reads a value from a register (DR, PR, FPR, or LR) using its full numeric ID.
     *
     * @param id The full ID of the register (e.g., 5 for DR5, 1002 for PR2, 3001 for LR1).
     * @return The value read from the register.
     */
    public Object readOperand(int id) {
        if (id >= Instruction.LR_BASE && id < Instruction.LR_BASE + Config.NUM_LOCATION_REGISTERS) {
            return getLr(id - Instruction.LR_BASE);
        }
        if (id >= Instruction.FPR_BASE && id < Instruction.FPR_BASE + Config.NUM_FORMAL_PARAM_REGISTERS) {
            return getFpr(id - Instruction.FPR_BASE);
        }
        if (id >= Instruction.PR_BASE && id < Instruction.PR_BASE + Config.NUM_PROC_REGISTERS) {
            return getPr(id - Instruction.PR_BASE);
        }
        if (id >= 0 && id < Config.NUM_DATA_REGISTERS) {
            return getDr(id);
        }
        this.instructionFailed("Invalid register ID: " + id);
        return null;
    }

    /**
     * Writes a value to a register (DR, PR, FPR, or LR) using its full numeric ID.
     *
     * @param id The full ID of the register.
     * @param value The value to write.
     * @return {@code true} if the write was successful.
     */
    public boolean writeOperand(int id, Object value) {
        if (id >= Instruction.LR_BASE && id < Instruction.LR_BASE + Config.NUM_LOCATION_REGISTERS) {
            return setLr(id - Instruction.LR_BASE, (int[]) value);
        }
        if (id >= Instruction.FPR_BASE && id < Instruction.FPR_BASE + Config.NUM_FORMAL_PARAM_REGISTERS) {
            return setFpr(id - Instruction.FPR_BASE, value);
        }
        if (id >= Instruction.PR_BASE && id < Instruction.PR_BASE + Config.NUM_PROC_REGISTERS) {
            return setPr(id - Instruction.PR_BASE, value);
        }
        if (id >= 0 && id < Config.NUM_DATA_REGISTERS) {
            return setDr(id, value);
        }
        this.instructionFailed("Invalid register ID: " + id);
        return false;
    }
    
}