package org.evochora.runtime.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.evochora.runtime.Config;
import org.evochora.runtime.Simulation;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.RegisterBank;
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
     * @param entropyDelta Change in entropy during execution.
     * @param registerValuesBefore Register values before instruction execution (for annotation display).
     *                             Maps register ID to register value (only for registers used as arguments).
     *                             {@code null} means the values were not collected: they are gathered
     *                             only on sampled ticks, so the frozen record of an organism that died
     *                             between two samples carries none. An empty map means the values were
     *                             collected but the instruction had no register arguments.
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
    private int generation = 0; // Replications between a founding organism and this one
    private long parentGenomeHash = 0L; // Genome of the parent at this organism's birth
    private final Object[] registers;
    private final Deque<Object> dataStack;
    private final Deque<int[]> locationStack;
    private final Deque<ProcFrame> callStack;
    /** Sentinel labelHash for Main-level persistent state. Outside the 20-bit labelHash range. */
    public static final int MAIN_LEVEL_LABEL_HASH = -1;
    /** Which procedure's persistent register state is currently active in the flat array. */
    private int currentProcLabelHash = MAIN_LEVEL_LABEL_HASH;
    /** Per-procedure backing store for PERSISTENT registers, keyed by labelHash. */
    private final Map<Integer, Object[]> persistentRegisterState = new HashMap<>();
    private boolean isDead = false;
    /** Set to true on first write to any STACK_SAVED register. Skips snapshot/restore when false. */
    private boolean stackSavedDirty = false;
    /** Set to true on first write to any PERSISTENT register. Skips persistent state ops when false. */
    private boolean persistentDirty = false;

    private boolean instructionFailed = false;
    private boolean previousInstructionFailed = false;
    private String failureReason = null;
    private Deque<ProcFrame> failureCallStack;

    /**
     * Represents a single frame on the call stack, created by a CALL instruction.
     * It stores the necessary state to return to the caller correctly.
     *
     * @param labelHash The label hash identifying this procedure. It serves the persistent register
     *                  state, and it is what observers resolve a procedure name from when one is
     *                  displayed — the frame itself carries no name.
     * @param absoluteReturnIp The absolute return IP.
     * @param absoluteCallIp The absolute address of the CALL instruction that created this frame.
     * @param savedRegisters Compact array of all STACK_SAVED register values in RegisterBank enum order.
     * @param parameterBindings Maps formal register IDs (FDR/FLR) to source register IDs for parameter binding visualization.
     */
    public record ProcFrame(
            int labelHash,
            int[] absoluteReturnIp,
            int[] absoluteCallIp,
            Object[] savedRegisters,
            java.util.Map<Integer, Integer> parameterBindings
    ) {}
    private boolean skipIpAdvance = false;
    private int[] ipBeforeFetch;
    private int[] dvBeforeFetch;
    private InstructionExecutionData lastInstructionExecution = null;
    private final Simulation simulation;
    private final int[] initialPosition;
    private final OrganismRandom random;
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
     * @param simulation The simulation instance this organism belongs to.
     */
    Organism(int id, int[] startIp, int initialEnergy, Simulation simulation) {
        this.id = id;
        this.ip = startIp;
        this.dps = new ArrayList<>(Config.NUM_DATA_POINTERS);
        for (int i = 0; i < Config.NUM_DATA_POINTERS; i++) {
            this.dps.add(Arrays.copyOf(startIp, startIp.length));
        }
        
        // Load limits and constants from simulation config. max-energy and max-entropy are
        // required and fail without a value. max-skips-per-tick is the one exception: it
        // defaults, so that a test can build an organism configuration without it. Every
        // deployment gets the value from reference.conf, so the default never applies in a run.
        com.typesafe.config.Config orgConfig = simulation.getOrganismConfig();
        this.maxEnergy = orgConfig.getInt("max-energy");
        this.maxEntropy = orgConfig.getInt("max-entropy");
        this.nopOpcodeId = Instruction.getInstructionIdByName("NOP");
        this.maxSkipsPerTick = orgConfig.hasPath("max-skips-per-tick")
                ? orgConfig.getInt("max-skips-per-tick") : 100;

        this.er = initialEnergy;
        this.sr = 0;
        this.mr = 0;
        this.simulation = simulation;
        this.dv = new int[startIp.length];
        this.dv[0] = 1; // Default direction: +X
        this.registers = new Object[RegisterBank.TOTAL_REGISTER_COUNT];
        for (RegisterBank bank : RegisterBank.values()) {
            for (int i = 0; i < bank.count; i++) {
                registers[bank.slotOffset() + i] = bank.isLocation ? new int[startIp.length] : 0;
            }
        }
        this.persistentRegisterState.put(MAIN_LEVEL_LABEL_HASH, snapshotPersistentRegisters());
        this.locationStack = new ArrayDeque<>(Config.LOCATION_STACK_MAX_DEPTH);
        this.dataStack = new ArrayDeque<>(Config.STACK_MAX_DEPTH);
        this.callStack = new ArrayDeque<>(Config.CALL_STACK_MAX_DEPTH);
        this.activeDpIndex = 0;
        this.ipBeforeFetch = Arrays.copyOf(startIp, startIp.length);
        this.dvBeforeFetch = Arrays.copyOf(this.dv, this.dv.length);
        this.initialPosition = Arrays.copyOf(startIp, startIp.length);
        this.random = new OrganismRandom(id);
    }

    /**
     * Factory method to create a new Organism with a unique ID from the simulation.
     *
     * @param simulation The simulation instance.
     * @param startIp The initial coordinate of the Instruction Pointer.
     * @param initialEnergy The starting energy.
     * @return A newly created Organism.
     */
    public static Organism create(Simulation simulation, int[] startIp, int initialEnergy) {
        int newId = simulation.getNextOrganismId();
        return new Organism(newId, startIp, initialEnergy, simulation);
    }

    /**
     * Entry point for restoring an organism from serialized state.
     * <p>
     * This is used during simulation resume to reconstruct organisms from
     * persisted checkpoint data. The two values without a meaningful default are passed here;
     * the rest is supplied through the builder. Three of those are required as well — the
     * instruction pointer, the direction vector and the initial position — and
     * {@link RestoreBuilder#build(Simulation)} rejects state that is missing them.
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
        this.generation = b.generation;
        this.parentGenomeHash = b.parentGenomeHash;
        this.deathTick = b.deathTick;

        // Deep copy data pointers
        this.dps = new ArrayList<>(b.dps.size());
        for (int[] dp : b.dps) {
            this.dps.add(Arrays.copyOf(dp, dp.length));
        }
        this.activeDpIndex = b.activeDpIndex;

        // Build flat register array
        this.registers = new Object[RegisterBank.TOTAL_REGISTER_COUNT];
        int dims = b.ip.length;
        if (b.flatRegisters != null) {
            System.arraycopy(b.flatRegisters, 0, this.registers, 0, b.flatRegisters.length);
        }
        // Fill any unset slots with defaults
        for (int i = 0; i < this.registers.length; i++) {
            if (this.registers[i] == null) {
                RegisterBank bank = RegisterBank.SLOT_TO_BANK[i];
                this.registers[i] = bank != null && bank.isLocation ? new int[dims] : 0;
            }
        }

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

        // Persistent register state
        this.currentProcLabelHash = b.currentProcLabelHash;
        if (b.persistentRegisterState != null) {
            this.persistentRegisterState.putAll(b.persistentRegisterState);
        } else {
            this.persistentRegisterState.put(MAIN_LEVEL_LABEL_HASH, snapshotPersistentRegisters());
        }

        this.stackSavedDirty = b.stackSavedDirty;
        this.persistentDirty = b.persistentDirty;

        // Derived fields from simulation
        this.simulation = simulation;
        // Load limits and constants from simulation config. max-energy and max-entropy are
        // required and fail without a value. max-skips-per-tick is the one exception: it
        // defaults, so that a test can build an organism configuration without it. Every
        // deployment gets the value from reference.conf, so the default never applies in a run.
        com.typesafe.config.Config orgConfig = simulation.getOrganismConfig();
        this.maxEnergy = orgConfig.getInt("max-energy");
        this.maxEntropy = orgConfig.getInt("max-entropy");
        this.nopOpcodeId = Instruction.getInstructionIdByName("NOP");
        this.maxSkipsPerTick = orgConfig.hasPath("max-skips-per-tick")
                ? orgConfig.getInt("max-skips-per-tick") : 100;

        // Preserve original birth position from checkpoint data
        this.initialPosition = Arrays.copyOf(b.initialPosition, b.initialPosition.length);

        this.random = new OrganismRandom(this.id);

        // Per-tick state is reset
        this.skipIpAdvance = false;
        this.ipBeforeFetch = Arrays.copyOf(this.ip, this.ip.length);
        this.dvBeforeFetch = Arrays.copyOf(this.dv, this.dv.length);
        this.lastInstructionExecution = null;
    }

    /**
     * Thrown when restored state does not describe an organism this build could have produced.
     * <p>
     * Kept apart from the plain {@link IllegalStateException} a wrongly called builder raises,
     * because the two call for opposite responses: this one says the state is unusable, and a caller
     * reading it from somewhere — a checkpoint, say — can report that and refuse to go on. The other
     * says the calling code is wrong, and turning it into a statement about the data would send the
     * search in the wrong direction.
     */
    public static class InvalidRestoreState extends IllegalStateException {
        /**
         * Creates the exception for one concrete mismatch between the restored state and this build.
         *
         * @param message what about the state does not fit this build
         */
        public InvalidRestoreState(String message) {
            super(message);
        }
    }

    /**
     * Collects the state of an organism that is being reconstructed rather than born.
     * <p>
     * Obtained from {@link Organism#restore(int, long)}, which takes the two values that have no
     * meaningful default. Every other value keeps a default until a setter supplies it, and each
     * setter returns this builder so the calls can be chained. The instruction pointer, the
     * direction vector and the initial position have no usable default: {@link #build(Simulation)}
     * rejects state in which they are missing, along with any value that does not fit this build's
     * register banks, data pointer count, coordinate dimension or stack limits.
     * <p>
     * Arrays, lists and deques handed to the setters are held by reference until the build. The
     * build copies the coordinate arrays — instruction pointer, direction vector, data pointers,
     * initial position — and the containers of the stacks, the register array and the persistent
     * register store. It does not copy the objects those containers hold: stacked location values,
     * procedure frames and persistent register snapshots remain shared with the caller.
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
        private int generation = 0;
        private long parentGenomeHash = 0L;
        private long deathTick = -1L;
        private List<int[]> dps = new ArrayList<>();
        private int activeDpIndex = 0;
        private Object[] flatRegisters = null;
        private Deque<Object> dataStack = new ArrayDeque<>();
        private Deque<int[]> locationStack = new ArrayDeque<>();
        private Deque<ProcFrame> callStack = new ArrayDeque<>();
        private boolean isDead = false;
        private int[] initialPosition;
        private boolean instructionFailed = false;
        private String failureReason = null;
        private Deque<ProcFrame> failureCallStack = null;
        private Map<Integer, Object[]> persistentRegisterState = null;
        private int currentProcLabelHash = MAIN_LEVEL_LABEL_HASH;
        private boolean stackSavedDirty = false;
        private boolean persistentDirty = false;

        private RestoreBuilder(int id, long birthTick) {
            this.id = id;
            this.birthTick = birthTick;
        }

        /**
         * Sets the parent organism ID.
         *
         * @param parentId the ID of the organism this one was forked from, or {@code null} for a
         *                 founding organism, which is the default
         * @return this builder
         */
        public RestoreBuilder parentId(Integer parentId) {
            this.parentId = parentId;
            return this;
        }

        /**
         * Sets the program ID.
         *
         * @param programId identifier of the compiled program the organism runs, which a child
         *                  inherits unchanged from its parent; the empty string, the default,
         *                  means no program is associated
         * @return this builder
         */
        public RestoreBuilder programId(String programId) {
            this.programId = programId;
            return this;
        }

        /**
         * Sets the instruction pointer coordinates.
         * <p>
         * The IP is an absolute position in the environment, and its length fixes the coordinate
         * dimension every other position of the restored organism is checked against. It must be
         * set: {@link #build} rejects a missing or empty IP.
         *
         * @param ip the absolute coordinate of the instruction pointer, one component per
         *           environment dimension
         * @return this builder
         */
        public RestoreBuilder ip(int[] ip) {
            this.ip = ip;
            return this;
        }

        /**
         * Sets the direction vector.
         * <p>
         * The DV must be set, and must have as many components as the IP. The runtime relies on it
         * being a unit vector — exactly one component ±1, the rest 0 — when it advances the IP.
         *
         * @param dv the direction along which the instruction pointer advances
         * @return this builder
         */
        public RestoreBuilder dv(int[] dv) {
            this.dv = dv;
            return this;
        }

        /**
         * Sets the energy register value.
         *
         * @param er the energy the organism holds; a living organism restored with a value at or
         *           below zero is killed on its first tick, and {@link #build} logs a warning for a
         *           negative one. No upper bound is enforced during restore
         * @return this builder
         */
        public RestoreBuilder energy(int er) {
            this.er = er;
            return this;
        }

        /**
         * Sets the entropy register value.
         *
         * @param sr the accumulated entropy, never negative in a state a running organism can
         *           reach — {@link #build} logs a warning for a negative one. An organism restored
         *           at or above the configured maximum entropy is killed on its next tick
         * @return this builder
         */
        public RestoreBuilder entropy(int sr) {
            this.sr = sr;
            return this;
        }

        /**
         * Sets the molecule marker register value.
         *
         * @param mr the marker stamped into every molecule the organism writes into the
         *           environment; only the low {@value Config#MARKER_BITS} bits carry meaning, and
         *           unlike {@link Organism#setMr(int)} the builder stores the value unmasked
         * @return this builder
         */
        public RestoreBuilder marker(int mr) {
            this.mr = mr;
            return this;
        }

        /**
         * Sets the original initial position (birth position) of the organism.
         * <p>
         * This is the position the organism falls back to when its instruction pointer stalls with
         * an empty call stack, so it must carry the recorded birth position rather than the
         * organism's current one. It must be set: {@link #build} rejects a missing or empty value.
         *
         * @param initialPosition the absolute coordinate at which the organism was born, with as
         *                        many components as the IP
         * @return this builder
         */
        public RestoreBuilder initialPosition(int[] initialPosition) {
            this.initialPosition = initialPosition;
            return this;
        }

        /**
         * Sets the genome hash.
         *
         * @param genomeHash the hash over the organism's code as it was computed at birth, or 0,
         *                   the default, when none was recorded
         * @return this builder
         */
        public RestoreBuilder genomeHash(long genomeHash) {
            this.genomeHash = genomeHash;
            return this;
        }

        /**
         * Sets the tick when the organism died (-1 if alive).
         *
         * @param deathTick the simulation tick at which the organism died, or -1, the default, for
         *                  one that is still alive. Setting it does not by itself mark the organism
         *                  dead — see {@link #dead(boolean)}
         * @return this builder
         */
        public RestoreBuilder deathTick(long deathTick) {
            this.deathTick = deathTick;
            return this;
        }

        /**
         * Sets the number of replications between a founding organism and this one.
         *
         * @param generation the generation counter, 0 for a founding organism. It cannot be derived
         *                   after the fact, because the parent may be long gone
         * @return this builder
         */
        public RestoreBuilder generation(int generation) {
            this.generation = generation;
            return this;
        }

        /**
         * Sets the genome hash the parent had when this organism was created.
         *
         * @param parentGenomeHash the parent's genome hash at this organism's birth, 0 for a
         *                         founding organism. Like the generation it is recorded rather than
         *                         derived, because the parent's genome is unobservable once the
         *                         parent is gone
         * @return this builder
         */
        public RestoreBuilder parentGenomeHash(long parentGenomeHash) {
            this.parentGenomeHash = parentGenomeHash;
            return this;
        }

        /**
         * Sets all data pointer coordinates.
         *
         * @param dps the absolute coordinates of the data pointers in index order, each with as
         *            many components as the IP. {@link #build} accepts either exactly
         *            {@link Config#NUM_DATA_POINTERS} entries or the empty default, and rejects any
         *            other count
         * @return this builder
         */
        public RestoreBuilder dataPointers(List<int[]> dps) {
            this.dps = dps;
            return this;
        }

        /**
         * Sets the active data pointer index.
         *
         * @param idx the index of the data pointer that instructions addressing "the" DP read and
         *            write; must address one of the supplied data pointers, or {@link #build}
         *            rejects it
         * @return this builder
         */
        public RestoreBuilder activeDpIndex(int idx) {
            this.activeDpIndex = idx;
            return this;
        }

        /**
         * Sets all register values from a flat array in RegisterBank slot order.
         *
         * @param regs one entry per register slot: an {@code Integer} for a data bank, an
         *             {@code int[]} for a location bank. The array must hold exactly
         *             {@link RegisterBank#TOTAL_REGISTER_COUNT} entries or {@link #build} rejects
         *             it; a {@code null} entry is filled with the bank's default at build time
         * @return this builder
         */
        public RestoreBuilder registers(Object[] regs) {
            this.flatRegisters = regs;
            return this;
        }

        /**
         * Sets the data stack contents.
         *
         * @param stack the stacked values, the deque's head being the top of the stack. A stack
         *              deeper than {@link Config#DS_MAX_DEPTH} is rejected by {@link #build}
         * @return this builder
         */
        public RestoreBuilder dataStack(Deque<Object> stack) {
            this.dataStack = stack;
            return this;
        }

        /**
         * Sets the location stack contents; a stack deeper than the limit is rejected by {@link #build}.
         *
         * @param stack the stacked coordinate values, the deque's head being the top of the stack.
         *              The limit is {@link Config#LOCATION_STACK_MAX_DEPTH}
         * @return this builder
         */
        public RestoreBuilder locationStack(Deque<int[]> stack) {
            this.locationStack = stack;
            return this;
        }

        /**
         * Sets the call stack contents.
         *
         * @param stack the frames of the procedures the organism is inside, the deque's head being
         *              the innermost one. A stack deeper than {@link Config#CALL_STACK_MAX_DEPTH}
         *              is rejected by {@link #build}
         * @return this builder
         */
        public RestoreBuilder callStack(Deque<ProcFrame> stack) {
            this.callStack = stack;
            return this;
        }

        /**
         * Sets whether the organism is dead.
         *
         * @param isDead {@code true} to restore an organism that has already died, which is what
         *               suppresses {@link #build}'s warning about non-positive energy
         * @return this builder
         */
        public RestoreBuilder dead(boolean isDead) {
            this.isDead = isDead;
            return this;
        }

        /**
         * Sets the instruction failure state.
         *
         * @param failed whether the organism's most recently executed instruction failed. The
         *               conditionals that branch on a failure read it in the tick after the one it
         *               was recorded in
         * @param reason the human-readable reason of that failure, or {@code null} when nothing
         *               failed
         * @return this builder
         */
        public RestoreBuilder failed(boolean failed, String reason) {
            this.instructionFailed = failed;
            this.failureReason = reason;
            return this;
        }

        /**
         * Sets the call stack at the time of failure.
         *
         * @param stack the frames captured when the failure was recorded, or {@code null} — the
         *              default — when no failure was recorded or the call stack was empty at the
         *              time. It is diagnostic only and never becomes the organism's live call stack
         * @return this builder
         */
        public RestoreBuilder failureCallStack(Deque<ProcFrame> stack) {
            this.failureCallStack = stack;
            return this;
        }

        /**
         * Sets the per-procedure persistent register backing store.
         *
         * @param state the parked PERSISTENT register snapshots keyed by procedure label hash, with
         *              {@link Organism#MAIN_LEVEL_LABEL_HASH} for the main level; each value holds
         *              {@link RegisterBank#PERSISTENT_SNAPSHOT_SIZE} entries. Left {@code null},
         *              the default, {@link #build} seeds the store with a main-level snapshot of
         *              the restored registers instead
         * @return this builder
         */
        public RestoreBuilder persistentRegisterState(Map<Integer, Object[]> state) {
            this.persistentRegisterState = state;
            return this;
        }

        /**
         * Sets the labelHash of the currently active procedure for persistent state.
         *
         * @param labelHash the label hash of the procedure whose PERSISTENT registers are the ones
         *                  held in the register array, or {@link Organism#MAIN_LEVEL_LABEL_HASH} —
         *                  the default — when execution is at main level. It has to agree with the
         *                  innermost frame of the restored call stack, since the next return parks
         *                  the live registers under this key
         * @return this builder
         */
        public RestoreBuilder currentProcLabelHash(int labelHash) {
            this.currentProcLabelHash = labelHash;
            return this;
        }

        /**
         * Sets whether any STACK_SAVED register has been written.
         *
         * @param dirty {@code true} if the organism has written a STACK_SAVED register at some
         *              point in its life. While it is {@code false} a call takes no snapshot of
         *              those registers, so restoring {@code false} for an organism that did write
         *              would change how its next call and return behave
         * @return this builder
         */
        public RestoreBuilder stackSavedDirty(boolean dirty) {
            this.stackSavedDirty = dirty;
            return this;
        }

        /**
         * Sets whether any PERSISTENT register has been written.
         *
         * @param dirty {@code true} if the organism has written a PERSISTENT register at some point
         *              in its life. While it is {@code false} calls and returns leave the
         *              per-procedure store untouched, so restoring {@code false} for an organism
         *              that did write would change how its next call and return behave
         * @return this builder
         */
        public RestoreBuilder persistentDirty(boolean dirty) {
            this.persistentDirty = dirty;
            return this;
        }

        /**
         * Builds the Organism instance.
         *
         * @param simulation The simulation this organism belongs to
         * @return Fully constructed Organism
         * @throws InvalidRestoreState if the state does not describe an organism this build
         *         could have produced
         * @throws IllegalStateException if no simulation is given
         */
        public Organism build(Simulation simulation) {
            // Validation
            if (simulation == null) {
                throw new IllegalStateException("Simulation cannot be null");
            }
            if (ip == null || ip.length == 0) {
                throw new InvalidRestoreState("IP must be set for restore");
            }
            if (dv == null || dv.length == 0) {
                throw new InvalidRestoreState("DV must be set for restore");
            }
            if (ip.length != dv.length) {
                throw new InvalidRestoreState("IP and DV must have same dimensions");
            }
            if (initialPosition == null || initialPosition.length == 0) {
                throw new InvalidRestoreState("Initial position must be set for restore");
            }
            if (er < 0 && !isDead) {
                LOG.warn("Organism {} restored with negative energy {} — will be killed on first tick",
                        id, er);
            }
            if (sr < 0) {
                LOG.warn("Organism {} restored with negative entropy {} — state may be corrupted",
                        id, sr);
            }
            validateStateInvariants();
            return new Organism(this, simulation);
        }

        /**
         * Rejects state that cannot describe an organism this build could have produced.
         * <p>
         * Values left unset still receive defaults — a caller may legitimately restore a minimal
         * organism. What is rejected is a value that <em>is</em> set but does not fit the build:
         * reshaping it would yield an organism different from the one the state described, and a
         * resumed run must equal an uninterrupted one.
         *
         * @throws InvalidRestoreState if a set value contradicts this build's register banks,
         *                               data pointer count, coordinate dimension or stack limits
         */
        private void validateStateInvariants() {
            if (flatRegisters != null && flatRegisters.length != RegisterBank.TOTAL_REGISTER_COUNT) {
                throw new InvalidRestoreState("Register array must hold "
                        + RegisterBank.TOTAL_REGISTER_COUNT + " values, got " + flatRegisters.length);
            }
            if (!dps.isEmpty()) {
                if (dps.size() != Config.NUM_DATA_POINTERS) {
                    throw new InvalidRestoreState("Data pointers must number "
                            + Config.NUM_DATA_POINTERS + ", got " + dps.size());
                }
                for (int[] dp : dps) {
                    if (dp == null || dp.length != ip.length) {
                        throw new InvalidRestoreState("Data pointer dimension must match the IP's "
                                + ip.length + ", got " + (dp == null ? "null" : dp.length));
                    }
                }
            }
            // Checked outside the block above, because an organism may be restored without data
            // pointers at all: nothing fills them in later, so it then holds none and must not be
            // asked for one. The lower bound of one admits that state with the index left at its
            // default; every other index is rejected.
            if (activeDpIndex < 0 || activeDpIndex >= Math.max(dps.size(), 1)) {
                throw new InvalidRestoreState("Active data pointer index " + activeDpIndex
                        + " lies outside the " + dps.size() + " data pointers");
            }
            requireStackWithinLimit("Data stack", dataStack.size(), Config.DS_MAX_DEPTH);
            requireStackWithinLimit("Location stack", locationStack.size(), Config.LOCATION_STACK_MAX_DEPTH);
            requireStackWithinLimit("Call stack", callStack.size(), Config.CALL_STACK_MAX_DEPTH);
        }

        /**
         * Rejects a stack deeper than the instruction set allows. Such a depth describes a state no
         * running organism can reach, because the instruction that would exceed the limit fails
         * instead of pushing.
         * <p>
         * A restorer reading a checkpoint checks the same limits before it gets here, so that its
         * message can name the checkpoint. This one guards the organism itself and therefore holds
         * for every caller. Sharing one helper between the two is not possible: it would have to live
         * in a package this one may depend on, and this package depends on nothing.
         *
         * @param name  the stack's name, for the message
         * @param depth the restored depth
         * @param limit the maximum depth the instruction set enforces
         * @throws InvalidRestoreState if the depth exceeds the limit
         */
        private void requireStackWithinLimit(String name, int depth, int limit) {
            if (depth > limit) {
                throw new InvalidRestoreState(
                        name + " depth " + depth + " exceeds the limit of " + limit);
            }
        }
    }

    /**
     * Resets the organism's per-tick state and positions its random stream at the start of the
     * current tick. Called by the VirtualMachine before planning a new instruction.
     */
    public void resetTickState() {
        this.random.beginTick(simulation.getTickSeed());
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
     * to the frame's return address, also restoring procedure-local data registers (PDRs)
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
            if (frame.savedRegisters() != null) {
                restoreStackSavedRegisters(frame.savedRegisters());
            } else if (stackSavedDirty) {
                resetStackSavedRegisters();
            }
            // Always track which procedure is active (same as ProcedureCallHandler.executeReturn)
            int callerLabelHash = callStack.isEmpty() ? MAIN_LEVEL_LABEL_HASH : callStack.peek().labelHash();
            if (persistentDirty) {
                persistentRegisterState.put(currentProcLabelHash, snapshotPersistentRegisters());
                Object[] callerState = persistentRegisterState.get(callerLabelHash);
                if (callerState != null) {
                    restorePersistentRegisters(callerState);
                } else {
                    resetPersistentRegisters();
                }
            }
            currentProcLabelHash = callerLabelHash;
            setIp(frame.absoluteReturnIp());
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
    
    /**
     * The ceiling {@link #addEr(int)} clamps the Energy Register to. Energy offered beyond it is
     * not stored, so a caller that moves energy out of the environment has to take only the
     * difference to the current {@link #getEr()}.
     *
     * @return the maximum energy this organism can hold, read from the organism configuration when
     *         the organism was created and unchanged for its lifetime.
     */
    public int getMaxEnergy() { return maxEnergy; }
    /**
     * The entropy level at which the organism is killed: the VirtualMachine ends the life of an
     * organism whose Entropy Register reaches or exceeds this value at the end of a tick. Unlike
     * the energy ceiling, nothing clamps the register to it.
     *
     * @return the entropy limit, read from the organism configuration when the organism was created
     *         and unchanged for its lifetime.
     */
    public int getMaxEntropy() { return maxEntropy; }

    /**
     * Sets a flag to prevent the VM from automatically advancing the IP at the end of the tick.
     *
     * @param skip {@code true} to skip the IP advance.
     */
    public void setSkipIpAdvance(boolean skip) { this.skipIpAdvance = skip; }

    /**
     * Taken from a counter the simulation raises for every organism it creates, so the value is
     * unique for the whole run and is not reused after the organism dies. Cells in the environment
     * carry it as their owner, which is what {@link #isCellAccessible(int)} compares against.
     *
     * @return The unique ID of the organism.
     */
    public int getId() { return id; }
    /**
     * Names the organism this one was forked from. It is a plain number, not a reference: the
     * parent may have died and been removed long before this organism is looked at, which is why
     * the facts inherited from it are copied at birth rather than read back later.
     *
     * @return The ID of the parent organism, or {@code null} if it has no parent.
     */
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

    /**
     * Together with {@link #getDeathTick()} this bounds the organism's lifespan in ticks. An
     * organism reconstructed from a checkpoint keeps its original birth tick, so the value does not
     * say when it entered the current process.
     *
     * @return The simulation tick number at which the organism was born.
     */
    public long getBirthTick() { return birthTick; }
    /** Sets the birth tick of the organism.
     * @param birthTick The birth tick.
     */
    public void setBirthTick(long birthTick) { 
        this.birthTick = birthTick;
    }
    /**
     * Names the compiled program the organism was seeded with. A child takes the value over from
     * its parent unchanged, so it identifies the program a lineage descends from and not the code
     * the organism carries at the moment — mutation leaves it alone. It is the empty string for an
     * organism that was never associated with a program.
     *
     * @return The program ID associated with this organism.
     */
    public String getProgramId() { return programId; }
    /** Sets the program ID for this organism.
     * @param programId The program ID.
     */
    public void setProgramId(String programId) { 
        this.programId = programId;
    }
    /**
     * The absolute position of the molecule the organism will execute next. It moves within a tick:
     * an instruction that jumps writes it, and the VirtualMachine advances it past the executed
     * instruction afterwards unless {@link #shouldSkipIpAdvance()} says otherwise. Because a copy
     * is handed out, writing into the returned array does not move the pointer — use
     * {@link #setIp(int[])}.
     *
     * @return A copy of the current Instruction Pointer (IP) coordinate.
     */
    public int[] getIp() { return Arrays.copyOf(ip, ip.length); }
    /**
     * The absolute position the currently executing instruction was read from.
     * {@link #resetTickState()} refreshes it from the IP before each instruction, so an instruction
     * can still find its own opcode and arguments after it has moved the IP.
     *
     * @return A copy of the IP coordinate as it was at the beginning of the tick.
     */
    public int[] getIpBeforeFetch() { return Arrays.copyOf(ipBeforeFetch, ipBeforeFetch.length); }
    /**
     * The direction that was in force when the current instruction was read. Argument fetching, IP
     * advancing and NOP skipping all follow this vector rather than the live DV, so an instruction
     * that turns the organism still reads its own arguments along the direction from before the
     * turn.
     *
     * @return A copy of the DV as it was at the beginning of the tick.
     */
    public int[] getDvBeforeFetch() { return Arrays.copyOf(dvBeforeFetch, dvBeforeFetch.length); }
    /**
     * What the organism executed in the current tick, recorded for observers and not read back by
     * execution. {@link #resetTickState()} clears it before each instruction, and it stays unset
     * for an instruction that lost a write conflict against another organism.
     *
     * @return The instruction execution data from the last executed instruction, or null if no instruction was executed.
     */
    public InstructionExecutionData getLastInstructionExecution() { return lastInstructionExecution; }
    /** Sets the instruction execution data for the last executed instruction.
     * @param data The instruction execution data to store.
     */
    public void setLastInstructionExecution(InstructionExecutionData data) { this.lastInstructionExecution = data; }
    /**
     * The energy the organism has left. Executing instructions costs energy and interacting with
     * the environment gains it; an organism whose energy has fallen to zero or below at the end of
     * a tick is killed. {@link #addEr(int)} clamps against {@link #getMaxEnergy()}, while
     * {@link #takeEr(int)} does not clamp, so the value can be seen negative within a tick.
     *
     * @return The current energy level (ER).
     */
    public int getEr() { return er; }
    /**
     * The disorder the organism has accumulated. It is never negative, because both
     * {@link #addSr(int)} and {@link #takeSr(int)} clamp at zero, and reaching
     * {@link #getMaxEntropy()} kills the organism at the end of the tick.
     *
     * @return The current entropy level (SR).
     */
    public int getSr() { return sr; }
    /**
     * The marker the organism stamps into every molecule it writes into the environment; erasing a
     * cell writes marker 0 instead of this value. A fork hands the child exactly those cells of the
     * parent that carry the parent's marker at that moment, so the marker is how a replicating
     * organism keeps the copy it is building apart from its own body. It occupies
     * {@value Config#MARKER_BITS} bits, hence the range 0 to {@value Config#MARKER_VALUE_MASK}.
     *
     * @return The current molecule marker (MR).
     */
    public int getMr() { return mr; }

    /**
     * Sets the molecule marker (MR). The value is masked to 4 bits (0-15).
     * @param value The new marker value.
     */
    public void setMr(int value) {
        this.mr = value & Config.MARKER_VALUE_MASK;
    }

    /**
     * Identifies the code the organism was born with, so that observers can group organisms sharing
     * a genome without comparing their cells. It is computed once, over the cells the organism owns
     * at birth, and is not recomputed as that code is later mutated in place.
     *
     * @return The genome hash computed at birth, or 0L if not set.
     */
    public long getGenomeHash() { return genomeHash; }

    /**
     * Sets the genome hash. Called by FORK instruction and SimulationEngine after placing molecules.
     * @param hash The computed genome hash.
     */
    public void setGenomeHash(long hash) {
        this.genomeHash = hash;
    }

    /**
     * Places the organism's death in time, recorded by {@link #kill(String)} from the simulation's
     * current tick. It is the counterpart of {@link #getBirthTick()} and stays at its sentinel for
     * as long as the organism lives.
     *
     * @return The tick when this organism died, or -1L if still alive.
     */
    public long getDeathTick() { return deathTick; }

    /**
     * Number of replications between a founding organism and this one.
     * <p>
     * Set once at birth from the parent. A consumer cannot derive it later: the parent may have
     * been removed from the simulation long before this organism is observed.
     *
     * @return the generation, 0 for a founding organism
     */
    public int getGeneration() { return generation; }

    /**
     * Genome hash the parent had when this organism was created.
     * <p>
     * Set once at birth, and 0 for a founding organism. Recorded rather than derived for the same
     * reason as the generation: the parent's genome is not observable once the parent is gone.
     *
     * @return the parent's genome hash at this organism's birth, 0 if there was no parent
     */
    public long getParentGenomeHash() { return parentGenomeHash; }

    /**
     * Records the ancestry facts a child inherits from its parent at the moment of replication.
     *
     * @param parent the organism this one was forked from
     */
    public void inheritFrom(Organism parent) {
        this.parentId = parent.getId();
        this.generation = parent.getGeneration() + 1;
        this.parentGenomeHash = parent.getGenomeHash();
    }

    /**
     * All registers of every bank in one array, addressed by slot rather than by register ID; a
     * data bank's slot holds an {@code Integer}, a location bank's an {@code int[]}. The array is a
     * copy, so replacing an entry leaves the organism unchanged, but the values in it are the
     * organism's own: the coordinate array of a location register must not be written into.
     *
     * @return A copy of the flat register array in RegisterBank slot order.
     */
    public Object[] getRegisters() { return Arrays.copyOf(registers, registers.length); }

    /**
     * A dead organism is no longer executed, and it stays dead: nothing in the runtime clears this
     * again. It may still be observed for a while before the simulation removes it.
     *
     * @return true if the organism is dead, false otherwise.
     */
    public boolean isDead() { return isDead; }
    /**
     * Whether anything has reported a failure for the instruction being executed in this tick.
     * {@link #resetTickState()} clears the flag before each instruction, and the first failure
     * within a tick is the one that is kept: a later report neither changes the flag nor overwrites
     * {@link #getFailureReason()}.
     *
     * @return true if the current instruction has failed.
     */
    public boolean isInstructionFailed() { return instructionFailed; }
    /**
     * Carries over the failure flag of the preceding tick, which {@link #resetTickState()} copies
     * across just before it clears the current one. An instruction cannot observe its own outcome,
     * so a conditional that branches on a failure necessarily reacts to the tick before it.
     *
     * @return true if the previous tick's instruction failed. Used by IFER/INER conditionals.
     */
    public boolean wasPreviousInstructionFailed() { return previousInstructionFailed; }
    /**
     * Human-readable text describing why the instruction failed, meant for diagnostics and never
     * readable by the organism itself. It belongs to the same tick as
     * {@link #isInstructionFailed()}: {@link #resetTickState()} clears it, and it is {@code null}
     * while nothing has failed.
     *
     * @return The reason for the last instruction failure.
     */
    public String getFailureReason() { return failureReason; }
    /**
     * The direction the instruction pointer travels in: a unit vector, exactly one component ±1 and
     * the rest 0, with one component per environment dimension. Because a copy is handed out,
     * writing into the returned array does not turn the organism — use {@link #setDv(int[])}.
     *
     * @return A copy of the current Direction Vector (DV).
     */
    public int[] getDv() { return Arrays.copyOf(dv, dv.length); }
    /**
     * The simulation this organism lives in, and its route to the environment, the current tick and
     * the organism configuration. It is fixed at creation and never {@code null}.
     *
     * @return The simulation instance.
     */
    public Simulation getSimulation() { return simulation; }
    /**
     * The absolute coordinate the organism was born at. It is fixed for the organism's whole life,
     * and it is where the instruction pointer is sent when execution stalls with an empty call
     * stack, which makes it the anchor of the organism's own code region.
     *
     * @return A copy of the organism's initial starting position.
     */
    public int[] getInitialPosition() { return Arrays.copyOf(this.initialPosition, this.initialPosition.length); }
    /**
     * The organism's general-purpose stack, handed out live rather than copied so that instructions
     * push and pop on it directly. The deque's head is the top of the stack. It does not enforce a
     * depth of its own: a caller that pushes has to check {@link Config#DS_MAX_DEPTH} first, the
     * limit that also bounds a restored stack.
     *
     * @return A reference to the Data Stack (DS).
     */
    public Deque<Object> getDataStack() { 
        return this.dataStack;
    }
    /**
     * The frames of the procedures the organism is currently inside, handed out live: a call pushes
     * onto this very deque and a return pops from it. The head is the innermost procedure, and an
     * empty stack means execution is at main level. As with the data stack, the depth limit
     * {@link Config#CALL_STACK_MAX_DEPTH} is the caller's to check.
     * <p>
     * A call also sets {@link #setCurrentProcLabelHash(int)} from the same label hash it puts in
     * the frame, and a return moves both back together. Pushing onto this deque directly bypasses
     * that: the hash then belongs to no frame, and the next return files the running procedure's
     * persistent registers under another procedure's name. Whoever pushes here keeps the two in
     * step themselves.
     *
     * @return A reference to the Call Stack (CS).
     */
    public Deque<ProcFrame> getCallStack() { 
        return this.callStack;
    }

    /**
     * Gets a reference to the Location Stack (LS).
     * <p>
     * It holds vectors of one component per world dimension, the head being the top. Whether a
     * value stands for an absolute position or for a relative displacement is decided by the
     * program alone: the instructions move these vectors between the stack, the location
     * registers, the data stack and the data pointers without distinguishing the two, and
     * {@link #setActiveDp(int[])} accepts whatever it is given.
     *
     * @return the live stack, not a copy; its depth is bounded by
     *         {@link org.evochora.runtime.Config#LOCATION_STACK_MAX_DEPTH}
     */
    public Deque<int[]> getLocationStack() {
        return this.locationStack;
    }

    /**
     * Gets the organism's random number source.
     * <p>
     * Instructions and interceptors that need randomness while executing for this organism must
     * use this source: its values depend only on seed, tick and organism ID, never on thread
     * scheduling.
     *
     * @return The {@link OrganismRandom} of this organism.
     */
    public OrganismRandom getRandom() { return this.random; }

    /**
     * Gets the call stack as it was at the moment an instruction failure occurred.
     *
     * @return A copy of the call stack at the time of failure.
     */
    public Deque<ProcFrame> getFailureCallStack() { return this.failureCallStack; }

    /**
     * Reads a value from any register using its full numeric ID.
     * Dispatches via flat array lookup — O(1) regardless of bank count.
     *
     * @param id the full register ID
     * @return the register value, or {@code null} if the ID is invalid
     */
    public Object readOperand(int id) {
        if (id < 0 || id >= RegisterBank.TABLE_SIZE) {
            this.instructionFailed("Invalid register ID: " + id);
            return null;
        }
        int slot = RegisterBank.ID_TO_SLOT[id];
        if (slot == -1) {
            this.instructionFailed("Invalid register ID: " + id);
            return null;
        }
        return registers[slot];
    }

    /**
     * Checks whether a register ID belongs to a location register bank.
     * Uses {@link RegisterBank#IS_LOCATION_BY_ID} for O(1) lookup.
     *
     * @param id the full register ID
     * @return {@code true} if the ID is in a location register bank
     */
    public static boolean isLocationBank(int id) {
        return RegisterBank.isLocationBank(id);
    }

    /**
     * Writes a value to a data register using its full numeric ID.
     * Location register writes are rejected — use {@link #writeLocationOperand(int, int[])} instead.
     * <p>
     * A {@code null} value cannot originate from executing code — every caller supplies an
     * {@code Integer} or an {@code int[]} — so it indicates a defect in the calling instruction.
     * It is rejected here rather than stored, because a null register surfaces far from its origin:
     * as a failure during state serialization, or as an undefined value on a later read.
     *
     * @param id the full ID of the register
     * @param value the value to write
     * @return {@code true} if the write was successful
     */
    public boolean writeOperand(int id, Object value) {
        if (value == null) {
            this.instructionFailed("Null value for register write");
            return false;
        }
        if (id < 0 || id >= RegisterBank.TABLE_SIZE) {
            this.instructionFailed("Invalid register ID: " + id);
            return false;
        }
        int slot = RegisterBank.ID_TO_SLOT[id];
        if (slot == -1) {
            this.instructionFailed("Invalid register ID: " + id);
            return false;
        }
        if (RegisterBank.IS_LOCATION_BY_ID[id]) {
            this.instructionFailed("Cannot write to location register via data instruction");
            return false;
        }
        if (!stackSavedDirty && RegisterBank.IS_STACK_SAVED_BY_ID[id]) stackSavedDirty = true;
        if (!persistentDirty && RegisterBank.IS_PERSISTENT_BY_ID[id]) persistentDirty = true;
        registers[slot] = value;
        return true;
    }

    /**
     * Writes a vector value to a location register using its full numeric ID.
     * Only accepts location register banks — data register writes are rejected.
     * <p>
     * A {@code null} value indicates a defect in the calling instruction and is rejected for the
     * same reason as in {@link #writeOperand(int, Object)}.
     *
     * @param id the full ID of the location register
     * @param value the vector value to write
     * @return {@code true} if the write was successful
     */
    public boolean writeLocationOperand(int id, int[] value) {
        if (value == null) {
            this.instructionFailed("Null value for location register write");
            return false;
        }
        if (id < 0 || id >= RegisterBank.TABLE_SIZE) {
            this.instructionFailed("Invalid register ID: " + id);
            return false;
        }
        int slot = RegisterBank.ID_TO_SLOT[id];
        if (slot == -1) {
            this.instructionFailed("Invalid register ID: " + id);
            return false;
        }
        if (!RegisterBank.IS_LOCATION_BY_ID[id]) {
            this.instructionFailed("Cannot write to non-location register via location instruction: " + id);
            return false;
        }
        if (!stackSavedDirty && RegisterBank.IS_STACK_SAVED_BY_ID[id]) stackSavedDirty = true;
        if (!persistentDirty && RegisterBank.IS_PERSISTENT_BY_ID[id]) persistentDirty = true;
        registers[slot] = value;
        return true;
    }

    /**
     * Creates a compact snapshot of all STACK_SAVED register values for a ProcFrame.
     * The layout follows RegisterBank enum declaration order.
     *
     * @return a new array of {@link RegisterBank#STACK_SAVED_SNAPSHOT_SIZE} entries. It holds the
     *         register values themselves rather than copies of them, so the coordinate array of a
     *         location register is shared with the register and must not be written into
     */
    public Object[] snapshotStackSavedRegisters() {
        List<RegisterBank> banks = RegisterBank.allSavedOnCall();
        Object[] snapshot = new Object[RegisterBank.STACK_SAVED_SNAPSHOT_SIZE];
        int offset = 0;
        for (RegisterBank bank : banks) {
            System.arraycopy(registers, bank.slotOffset(), snapshot, offset, bank.count);
            offset += bank.count;
        }
        return snapshot;
    }

    /**
     * Restores all STACK_SAVED register values from a compact ProcFrame snapshot.
     * The layout must match the one created by {@link #snapshotStackSavedRegisters()}.
     * <p>
     * The snapshot is written over the registers wholesale; values a callee left in them are lost.
     *
     * @param snapshot the values to write back, in slot order across the STACK_SAVED banks
     * @throws IllegalArgumentException if {@code snapshot} is {@code null} or does not hold exactly
     *         {@link RegisterBank#STACK_SAVED_SNAPSHOT_SIZE} entries, which means it was produced
     *         under a different register layout
     */
    public void restoreStackSavedRegisters(Object[] snapshot) {
        if (snapshot == null || snapshot.length != RegisterBank.STACK_SAVED_SNAPSHOT_SIZE) {
            throw new IllegalArgumentException(
                    "STACK_SAVED snapshot must contain " + RegisterBank.STACK_SAVED_SNAPSHOT_SIZE
                            + " values, got " + (snapshot == null ? "null" : snapshot.length));
        }
        List<RegisterBank> banks = RegisterBank.allSavedOnCall();
        int offset = 0;
        for (RegisterBank bank : banks) {
            System.arraycopy(snapshot, offset, registers, bank.slotOffset(), bank.count);
            offset += bank.count;
        }
    }

    /**
     * Resets all STACK_SAVED register values to their defaults (0 for data, zero-vector for location).
     * Used when a callee modified STACK_SAVED registers but no snapshot was taken at CALL time
     * (because the caller had never written to any STACK_SAVED register).
     */
    public void resetStackSavedRegisters() {
        for (RegisterBank bank : RegisterBank.allSavedOnCall()) {
            for (int i = 0; i < bank.count; i++) {
                registers[bank.slotOffset() + i] = bank.isLocation ? new int[ip.length] : 0;
            }
        }
    }

    /**
     * Creates a compact snapshot of all PERSISTENT register values.
     * The layout follows RegisterBank enum declaration order.
     *
     * @return a new array of {@link RegisterBank#PERSISTENT_SNAPSHOT_SIZE} entries. It holds the
     *         register values themselves rather than copies of them, so the coordinate array of a
     *         location register is shared with the register and must not be written into
     */
    public Object[] snapshotPersistentRegisters() {
        List<RegisterBank> banks = RegisterBank.allPersistent();
        Object[] snapshot = new Object[RegisterBank.PERSISTENT_SNAPSHOT_SIZE];
        int offset = 0;
        for (RegisterBank bank : banks) {
            System.arraycopy(registers, bank.slotOffset(), snapshot, offset, bank.count);
            offset += bank.count;
        }
        return snapshot;
    }

    /**
     * Restores all PERSISTENT register values from a compact snapshot.
     * The layout must match the one created by {@link #snapshotPersistentRegisters()}.
     * <p>
     * The snapshot is written over the registers wholesale; whatever they held before is lost, so a
     * caller leaving one procedure for another has to park the outgoing values first.
     *
     * @param snapshot the values to write back, in slot order across the PERSISTENT banks
     * @throws IllegalArgumentException if {@code snapshot} is {@code null} or does not hold exactly
     *         {@link RegisterBank#PERSISTENT_SNAPSHOT_SIZE} entries, which means it was produced
     *         under a different register layout
     */
    public void restorePersistentRegisters(Object[] snapshot) {
        if (snapshot == null || snapshot.length != RegisterBank.PERSISTENT_SNAPSHOT_SIZE) {
            throw new IllegalArgumentException(
                    "PERSISTENT snapshot must contain " + RegisterBank.PERSISTENT_SNAPSHOT_SIZE
                            + " values, got " + (snapshot == null ? "null" : snapshot.length));
        }
        List<RegisterBank> banks = RegisterBank.allPersistent();
        int offset = 0;
        for (RegisterBank bank : banks) {
            System.arraycopy(snapshot, offset, registers, bank.slotOffset(), bank.count);
            offset += bank.count;
        }
    }

    /**
     * Resets all PERSISTENT register slots to type-dependent defaults
     * (0 for data banks, zero-vector for location banks).
     */
    public void resetPersistentRegisters() {
        for (RegisterBank bank : RegisterBank.allPersistent()) {
            for (int i = 0; i < bank.count; i++) {
                registers[bank.slotOffset() + i] = bank.isLocation ? new int[ip.length] : 0;
            }
        }
    }

    /**
     * Returns the per-procedure persistent register backing store.
     *
     * @return the live map, keyed by procedure label hash with {@link #MAIN_LEVEL_LABEL_HASH} for
     *         the main level and each value a snapshot of
     *         {@link RegisterBank#PERSISTENT_SNAPSHOT_SIZE} entries. It is the organism's own map,
     *         so putting into it changes the organism. It holds the values of the procedures that
     *         are <em>not</em> currently executing; those of the active procedure live in the
     *         registers themselves and are parked here only on a call or a return
     */
    public Map<Integer, Object[]> getPersistentRegisterState() { return persistentRegisterState; }

    /**
     * Replaces the persistent register backing store (used during restore).
     *
     * @param state the entries to hold, replacing everything the store held before. The entries are
     *              copied into the organism's own map, but the snapshot arrays are not, so they
     *              stay shared with the caller's map
     */
    public void setPersistentRegisterState(Map<Integer, Object[]> state) {
        this.persistentRegisterState.clear();
        this.persistentRegisterState.putAll(state);
    }

    /**
     * Returns the labelHash of the currently active procedure for persistent state.
     *
     * @return the key under which the PERSISTENT registers currently held in the register array
     *         will be parked on the next call or return, or {@link #MAIN_LEVEL_LABEL_HASH} while
     *         execution is at main level. It tracks the innermost frame of the call stack whether
     *         or not any persistent register has ever been written
     */
    public int getCurrentProcLabelHash() { return currentProcLabelHash; }

    /**
     * Sets the labelHash of the currently active procedure for persistent state.
     *
     * @param labelHash the procedure being entered or returned to, or
     *                  {@link #MAIN_LEVEL_LABEL_HASH} for the main level. It has to be set in step
     *                  with the call stack, because the next call or return parks the live
     *                  PERSISTENT registers under whatever key it finds here
     */
    public void setCurrentProcLabelHash(int labelHash) { this.currentProcLabelHash = labelHash; }

    /**
     * Returns whether any STACK_SAVED register has been written during this organism's lifetime.
     *
     * @return {@code true} once such a write has happened; the flag is never cleared again. While
     *         it is {@code false}, a call stores no register snapshot in its frame and a return
     *         restores none, which is what spares an organism that uses no STACK_SAVED register the
     *         cost of saving them
     */
    public boolean isStackSavedDirty() { return stackSavedDirty; }

    /**
     * Returns whether any PERSISTENT register has been written during this organism's lifetime.
     *
     * @return {@code true} once such a write has happened; the flag is never cleared again. While
     *         it is {@code false}, calls and returns leave the per-procedure store untouched, which
     *         is what spares an organism that uses no PERSISTENT register the cost of maintaining
     *         it
     */
    public boolean isPersistentDirty() { return persistentDirty; }

}
