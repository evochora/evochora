package org.evochora.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.evochora.runtime.isa.IEnvironmentModifyingInstruction;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.OrganismRandom;
import org.evochora.runtime.model.SplitMix64;
import org.evochora.runtime.model.GenomeHasher;
import org.evochora.runtime.spi.DeathContext;
import org.evochora.runtime.spi.IBirthHandler;
import org.evochora.runtime.spi.IDeathHandler;
import org.evochora.runtime.spi.IInstructionInterceptor;
import org.evochora.runtime.spi.InterceptionContext;
import org.evochora.runtime.spi.IRandomProvider;
import org.evochora.runtime.spi.ITickPlugin;
import org.evochora.runtime.thermodynamics.ThermodynamicPolicyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.typesafe.config.Config;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Manages the core simulation loop, including organism lifecycle, instruction execution,
 * and environment interaction. It orchestrates the simulation tick by tick, handling
 * instruction planning, conflict resolution, and execution.
 * <p>
 * A tick has snapshot semantics: all organisms act simultaneously against the environment as it
 * was at the start of the tick, and environment writes take effect afterwards in organism order.
 * The number of threads used for a tick changes only its speed, never its result.
 */
public class Simulation {
    private static final Logger LOG = LoggerFactory.getLogger(Simulation.class);
    private final Environment environment;
    private final ThermodynamicPolicyManager policyManager;
    private final Config organismConfig;
    private final VirtualMachine vm;
    private final List<Organism> organisms;
    private long currentTick = 0L;
    /**
     * A flag to pause or resume the simulation.
     */
    public boolean paused = true;
    private final List<Organism> newOrganismsThisTick = new ArrayList<>();
    private final List<ITickPlugin> tickPlugins = new ArrayList<>();
    private final List<IInstructionInterceptor> instructionInterceptors = new ArrayList<>();
    private final List<IDeathHandler> deathHandlers = new ArrayList<>();
    private final List<IBirthHandler> birthHandlers = new ArrayList<>();
    private final InterceptionContext interceptContext = new InterceptionContext();  // Used when the calling thread runs wave 1 alone
    private final InterceptionContext[] parallelInterceptContexts;  // Used by the worker pool, one per thread
    private final DeathContext deathContext = new DeathContext();  // Main thread only, reused across ticks
    private final TickWorkerPool workerPool;
    private final int effectiveParallelism;
    private int[] scalingOrganisms = {};
    private int[] scalingMaxThreads = {};
    private int nextOrganismId = 1;
    private int organismsSinceYield = 0;

    /**
     * When set, the virtual machine collects each executed instruction's pre-execution
     * register values into its execution record. Only this map hangs on the flag — the
     * record itself (opcode, raw arguments, energy cost, entropy delta) is kept on every
     * tick. The map exists solely for observers of sampled ticks (state serialization
     * reads it, nothing else does); on every other tick it would cost a map of boxed
     * register values per instruction for nothing. Defaults to on, so callers that never
     * sample keep the full annotation.
     */
    private boolean captureExecutionDetails = true;
    private final LongOpenHashSet allGenomesEverSeen = new LongOpenHashSet();
    private IRandomProvider randomProvider;

    /** The run's seed, taken from the random provider when it is installed. */
    private long seed;

    /**
     * Seed of the current tick, derived from the run seed and the tick number at the start of
     * every tick. Organisms fold it with their ID into the seed of their own random stream
     * ({@link OrganismRandom#beginTick(long)}), so that every organism's randomness is a pure
     * function of seed, tick and ID.
     */
    private long tickSeed;

    /**
     * Constructs a new Simulation instance.
     *
     * @param environment The simulation environment.
     * @param policyManager The manager for thermodynamic policies.
     * @param organismConfig Configuration for organisms (energy limits, etc.).
     * @param parallelism Thread parallelism for the Plan and Execute phases.
     *                    0 = auto ({@code max(1, availableProcessors - 2)}),
     *                    1 = single-threaded (sequential code path, useful for debugging),
     *                    N &gt; 1 = exactly N threads via {@link TickWorkerPool}.
     *                    Determinism is guaranteed in every mode.
     */
    public Simulation(Environment environment, ThermodynamicPolicyManager policyManager, Config organismConfig, int parallelism) {
        this.environment = environment;
        this.policyManager = policyManager;
        this.organismConfig = organismConfig;
        this.organisms = new ArrayList<>();
        this.vm = new VirtualMachine(this);
        this.effectiveParallelism = resolveParallelism(parallelism);
        this.workerPool = (effectiveParallelism > 1) ? new TickWorkerPool(effectiveParallelism) : null;
        if (workerPool != null) {
            this.parallelInterceptContexts = new InterceptionContext[effectiveParallelism];
            for (int i = 0; i < effectiveParallelism; i++) {
                parallelInterceptContexts[i] = new InterceptionContext();
            }
        } else {
            this.parallelInterceptContexts = null;
        }
    }

    /**
     * Creates a Simulation instance for resuming from a previously saved checkpoint.
     * <p>
     * This factory method creates a Simulation pre-initialized with state from a checkpoint,
     * allowing the simulation to continue from where it was interrupted. Organisms must be
     * added after construction via {@link #addOrganism(Organism)}.
     * <p>
     * Note: The random provider and tick plugins must be set separately after calling this method.
     *
     * @param environment Pre-populated environment with restored cell state
     * @param currentTick The tick number to resume from
     * @param totalOrganismsCreated Total number of organisms created in the original run
     *                              (used to calculate next organism ID)
     * @param allGenomesEverSeen Set of all genome hashes ever observed (for cumulative tracking).
     *                           May be {@code null} or empty for new simulations or old checkpoints.
     * @param policyManager Thermodynamic policy manager (from Metadata config)
     * @param organismConfig Organism configuration (from Metadata config)
     * @param parallelism Thread parallelism for the Plan phase (see constructor for semantics)
     * @return Simulation ready for organism addition and resumption
     */
    public static Simulation forResume(
            Environment environment,
            long currentTick,
            long totalOrganismsCreated,
            LongOpenHashSet allGenomesEverSeen,
            ThermodynamicPolicyManager policyManager,
            Config organismConfig,
            int parallelism) {

        Simulation sim = new Simulation(environment, policyManager, organismConfig, parallelism);
        sim.currentTick = currentTick;
        sim.nextOrganismId = (int) totalOrganismsCreated + 1;
        if (allGenomesEverSeen != null && !allGenomesEverSeen.isEmpty()) {
            sim.allGenomesEverSeen.addAll(allGenomesEverSeen);
        }
        return sim;
    }

    public ThermodynamicPolicyManager getPolicyManager() {
        return policyManager;
    }

    public Config getOrganismConfig() {
        return organismConfig;
    }

    /**
     * Adds a new organism to the simulation.
     * @param organism The organism to add.
     */
    public void addOrganism(Organism organism) {
        this.organisms.add(organism);
    }

    /**
     * Sets the random number provider for the simulation. The provider's seed also becomes the
     * run seed from which every organism's randomness is derived; a simulation cannot tick
     * without one.
     * @param provider The random provider to use; must not be null.
     */
    public void setRandomProvider(IRandomProvider provider) {
        this.randomProvider = Objects.requireNonNull(provider, "random provider");
        this.seed = provider.seed();
    }

    /**
     * Gets the random number provider for the simulation.
     * @return The current random provider.
     */
    public IRandomProvider getRandomProvider() {
        return this.randomProvider;
    }

    /**
     * Gets the seed of the current tick.
     * <p>
     * It is computed once at the start of {@link #tick()} and is constant while the tick runs, so
     * worker threads may read it freely during the parallel wave.
     *
     * @return The seed of the current tick.
     */
    public long getTickSeed() {
        return this.tickSeed;
    }

    /**
     * Chooses whether the next ticks collect each instruction's pre-execution register
     * values into its execution record. The record itself (opcode, raw arguments, costs)
     * is kept regardless of this flag. Samplers enable this for the tick they are about
     * to capture and disable it otherwise.
     * <p>
     * Must be called between ticks, never while a tick runs: the flag is read from
     * every thread of the parallel wave.
     *
     * @param capture whether execution records carry pre-execution register values
     */
    public void setCaptureExecutionDetails(boolean capture) {
        this.captureExecutionDetails = capture;
    }

    /**
     * Reports whether execution records currently carry pre-execution register values.
     *
     * @return {@code true} when register values are collected
     */
    public boolean isCaptureExecutionDetails() {
        return this.captureExecutionDetails;
    }

    /**
     * Adds a tick plugin to the simulation.
     * Plugins are executed in the order they are added, at the beginning of each tick.
     * @param plugin The tick plugin to add.
     */
    public void addTickPlugin(ITickPlugin plugin) {
        this.tickPlugins.add(plugin);
    }

    /**
     * Returns the list of tick plugins.
     * @return An unmodifiable view of the tick plugins list.
     */
    public List<ITickPlugin> getTickPlugins() {
        return java.util.Collections.unmodifiableList(this.tickPlugins);
    }

    /**
     * Adds an instruction interceptor to the simulation.
     * Interceptors are called in the order they are added, during the Plan phase,
     * after operand resolution but before conflict resolution.
     * @param interceptor The instruction interceptor to add.
     */
    public void addInstructionInterceptor(IInstructionInterceptor interceptor) {
        this.instructionInterceptors.add(interceptor);
    }

    /**
     * Returns the list of instruction interceptors.
     * @return An unmodifiable view of the instruction interceptors list.
     */
    public List<IInstructionInterceptor> getInstructionInterceptors() {
        return java.util.Collections.unmodifiableList(this.instructionInterceptors);
    }

    /**
     * Adds a death handler to the simulation.
     * Death handlers are called in the order they are added, when an organism dies,
     * before ownership is cleared.
     * @param handler The death handler to add.
     */
    public void addDeathHandler(IDeathHandler handler) {
        this.deathHandlers.add(handler);
    }

    /**
     * Returns the list of death handlers.
     * @return An unmodifiable view of the death handlers list.
     */
    public List<IDeathHandler> getDeathHandlers() {
        return java.util.Collections.unmodifiableList(this.deathHandlers);
    }

    /**
     * Adds a birth handler to the simulation.
     * Birth handlers are called in the order they are added, once per newborn organism,
     * in the synchronous post-Execute phase before genome hash computation.
     * @param handler The birth handler to add.
     */
    public void addBirthHandler(IBirthHandler handler) {
        this.birthHandlers.add(handler);
    }

    /**
     * Returns the list of birth handlers.
     * @return An unmodifiable view of the birth handlers list.
     */
    public List<IBirthHandler> getBirthHandlers() {
        return java.util.Collections.unmodifiableList(this.birthHandlers);
    }

    /**
     * Returns the next available unique ID for an organism.
     * @return A unique organism ID.
     */
    public int getNextOrganismId() {
        return nextOrganismId++;
    }

    /**
     * Returns the total number of organisms created so far.
     * This corresponds to the highest ID assigned.
     * @return Total organisms created.
     */
    public int getTotalOrganismsCreatedCount() {
        return nextOrganismId - 1;
    }

    /**
     * Registers a genome hash as having been observed in this simulation.
     * Called from SimulationEngine (initial placement) and the post-Execute birth phase in tick().
     *
     * @param hash The genome hash to register. Zero hashes are ignored.
     */
    public void registerGenomeHash(long hash) {
        if (hash != 0L) {
            allGenomesEverSeen.add(hash);
        }
    }

    /**
     * Returns the total count of unique genomes ever observed in this simulation.
     *
     * @return The count of unique genome hashes.
     */
    public int getTotalUniqueGenomesCount() {
        return allGenomesEverSeen.size();
    }

    /**
     * Returns the set of all genome hashes ever observed.
     * Used for snapshot serialization during data pipeline capture.
     * <p>
     * Returns the internal set directly (no copy) since Simulation is single-threaded.
     *
     * @return The set of all genome hashes ever seen.
     */
    public LongOpenHashSet getAllGenomesEverSeen() {
        return allGenomesEverSeen;
    }

    /**
     * Returns the logger for this class.
     * @return The SLF4J logger.
     */
    public Logger getLogger() {
        return LOG;
    }

    /**
     * Executes a single simulation tick: tick plugins run first, then all organisms plan and
     * execute under snapshot semantics (see {@link #planResolveExecute()}), then birth handlers
     * run for the organisms born in this tick.
     * <p>
     * If the calling thread is interrupted while the tick runs, the tick stops where it is: some
     * organisms have acted, others have not. The simulation is then in a state no complete tick
     * ever produces and must be discarded, not continued or persisted — which is what the engine
     * does, because it stops sampling as soon as a stop is requested.
     */
    public void tick() {
        if (Thread.currentThread().isInterrupted()) return;
        if (randomProvider == null) {
            throw new IllegalStateException(
                    "Simulation has no random provider; install one with setRandomProvider before ticking "
                    + "- the run seed and all organism randomness derive from it");
        }

        tickSeed = SplitMix64.mix(seed ^ SplitMix64.mix(currentTick));

        // Execute tick plugins before Plan-Resolve-Execute cycle
        for (ITickPlugin plugin : tickPlugins) {
            try {
                plugin.execute(this);
            } catch (Exception e) {
                LOG.warn("Tick plugin '{}' failed at tick {}: {}",
                        plugin.getClass().getSimpleName(), currentTick, e.getMessage());
            }
        }

        planResolveExecute();

        // Post-Execute: birth handlers + genome hash for newborns
        for (Organism newborn : newOrganismsThisTick) {
            for (IBirthHandler handler : birthHandlers) {
                try {
                    handler.onBirth(newborn, environment);
                } catch (Exception e) {
                    LOG.warn("Birth handler '{}' failed for organism {}: {}",
                            handler.getClass().getSimpleName(), newborn.getId(), e.getMessage());
                }
            }
            long hash = GenomeHasher.computeGenomeHash(
                    environment, newborn.getId(), newborn.getInitialPosition());
            newborn.setGenomeHash(hash);
            registerGenomeHash(hash);
        }

        this.organisms.addAll(newOrganismsThisTick);
        newOrganismsThisTick.clear();
        this.currentTick++;
    }

    /**
     * Runs the Plan-Resolve-Execute cycle of one tick under snapshot semantics.
     * <p>
     * Every organism plans and, if its instruction is {@link Instruction#isParallelExecuteSafe
     * parallel-safe} (it changes only the organism's own state), executes it immediately against
     * the environment as it was at the start of the tick (wave 1). Environment-modifying
     * instructions are collected, pass conflict resolution and execute afterwards in organism
     * order (wave 2). Deaths are handled after both waves in organism order.
     * <p>
     * The thread count only decides who performs wave 1: with more than one active thread the
     * worker pool processes disjoint organism ranges concurrently, otherwise the calling thread
     * processes all organisms in one loop without touching the pool. Both produce the same state,
     * because wave 1 never writes to the environment and so cannot influence another organism's
     * wave 1. The active thread count is resolved per tick from the configured parallelism and the
     * {@code parallelism-scaling} thresholds.
     */
    private void planResolveExecute() {
        int size = organisms.size();
        Instruction[] planned = new Instruction[size];
        boolean[] diedInWave1 = new boolean[size];

        int activeThreads = (workerPool != null && size > 1) ? resolveActiveParallelism(size) : 1;
        if (activeThreads > 1) {
            InterceptionContext[] contexts = instructionInterceptors.isEmpty() ? null : parallelInterceptContexts;
            Thread mainThread = Thread.currentThread();
            workerPool.dispatch(size, activeThreads, (from, to) -> {
                InterceptionContext context = contexts != null ? contexts[TickWorkerPool.getThreadIndex()] : null;
                planAndExecuteLocal(from, to, context, mainThread, planned, diedInWave1);
            });
        } else {
            InterceptionContext context = instructionInterceptors.isEmpty() ? null : interceptContext;
            ParallelWave.enter();
            try {
                planAndExecuteLocal(0, size, context, Thread.currentThread(), planned, diedInWave1);
            } finally {
                ParallelWave.leave();
            }
        }

        // Wave 2: environment-modifying instructions, conflict-resolved, in organism order
        List<Instruction> wave2 = new ArrayList<>();
        for (Instruction instruction : planned) {
            if (instruction != null && !Instruction.isParallelExecuteSafe(instruction.getFullOpcodeId())) {
                wave2.add(instruction);
            }
        }
        resolveConflicts(wave2);

        boolean[] diedInWave2 = new boolean[wave2.size()];
        for (int i = 0; i < wave2.size(); i++) {
            executeSingleInstruction(wave2.get(i));
            if (wave2.get(i).getOrganism().isDead()) {
                diedInWave2[i] = true;
            }
        }

        // Death handling in organism order: wave 1 first, then wave 2
        for (int i = 0; i < planned.length; i++) {
            if (diedInWave1[i]) handleDeath(planned[i].getOrganism());
        }
        for (int i = 0; i < wave2.size(); i++) {
            if (diedInWave2[i]) handleDeath(wave2.get(i).getOrganism());
        }
    }

    /**
     * Wave 1 for the organisms at indices {@code [from, to)}: plans each organism's instruction,
     * runs the interceptors, executes the instruction immediately if it is parallel-safe and
     * otherwise leaves it for wave 2.
     * <p>
     * Called by the worker pool with disjoint ranges, or by the calling thread with the full range.
     * Touches only organism-local state and the per-call arrays at the organism's own index, so
     * concurrent calls on disjoint ranges do not interfere.
     *
     * @param from first organism index (inclusive)
     * @param to last organism index (exclusive)
     * @param context the interception context of the executing thread, or {@code null} when no
     *                interceptors are registered
     * @param mainThread the thread that drives the simulation; an interrupt on it aborts the wave
     * @param planned receives each organism's planned instruction at the organism's index
     * @param diedInWave1 set at the organism's index when it dies during wave 1
     */
    private void planAndExecuteLocal(int from, int to, InterceptionContext context, Thread mainThread,
                                     Instruction[] planned, boolean[] diedInWave1) {
        // Yield periodically so that other threads (control API, data pipeline) get scheduled
        // during long stretches of ticking. On the calling thread the counter carries over from
        // tick to tick, so small populations yield too; workers use a counter per chunk.
        boolean onMainThread = Thread.currentThread() == mainThread;
        int processed = onMainThread ? organismsSinceYield : 0;
        for (int i = from; i < to; i++) {
            if (mainThread.isInterrupted()) return;
            if ((++processed & 0xFFF) == 0) Thread.yield();
            Organism organism = organisms.get(i);
            if (organism.isDead()) continue;

            Instruction instruction = vm.plan(organism);

            if (context != null) {
                context.reset(organism, instruction);
                for (IInstructionInterceptor interceptor : instructionInterceptors) {
                    try {
                        interceptor.intercept(context);
                    } catch (ParallelWaveViolation e) {
                        // The run is irreproducible from here on; never downgrade this to a warning.
                        throw e;
                    } catch (Exception e) {
                        LOG.warn("Interceptor '{}' failed for organism {} at tick {}: {}",
                                interceptor.getClass().getSimpleName(), organism.getId(),
                                currentTick, e.getMessage());
                    }
                }
                instruction = context.getInstruction();
            }

            instruction.setConflictStatus(Instruction.ConflictResolutionStatus.NOT_APPLICABLE);
            if (Instruction.isParallelExecuteSafe(instruction.getFullOpcodeId())) {
                instruction.setProcessedInTick(true);
                executeSingleInstruction(instruction);
                if (organism.isDead()) {
                    diedInWave1[i] = true;
                }
            } else {
                instruction.setProcessedInTick(false);
            }

            planned[i] = instruction;
        }
        if (onMainThread) {
            organismsSinceYield = processed;
        }
    }

    /**
     * Executes a single instruction: runs {@code vm.execute()}, advances past NOP cells,
     * and applies error penalty if a post-execution failure occurred.
     *
     * @param instruction The instruction to execute
     */
    private void executeSingleInstruction(Instruction instruction) {
        if (!instruction.isProcessedInTick()) return;
        Organism organism = instruction.getOrganism();

        vm.execute(instruction);

        boolean failedInExecution = organism.isInstructionFailed();
        organism.skipNopCells(environment);

        // Apply error penalty for post-execution failures (e.g., max-skip)
        // not already penalized inside vm.execute()
        if (!failedInExecution && organism.isInstructionFailed()) {
            int penalty = organismConfig.getInt("error-penalty-cost");
            organism.takeEr(penalty);
            if (organism.getEr() <= 0) {
                organism.kill("Ran out of energy");
            }
        }
    }

    /**
     * Handles organism death: invokes all registered death handlers, then clears
     * the organism's ownership from the environment.
     *
     * @param organism The organism that has died
     */
    private void handleDeath(Organism organism) {
        deathContext.reset(environment, organism.getId());
        for (IDeathHandler handler : deathHandlers) {
            try {
                handler.onDeath(deathContext);
            } catch (Exception e) {
                LOG.warn("Death handler '{}' failed for organism {}: {}",
                        handler.getClass().getSimpleName(), organism.getId(), e.getMessage());
            }
        }
        environment.clearOwnershipFor(organism.getId());
    }

    /**
     * Configures dynamic parallelism scaling based on organism count.
     * <p>
     * Each entry maps an organism count threshold to a maximum thread count.
     * During each tick, the highest threshold not exceeding the current organism
     * count determines the active thread count. Below the lowest threshold,
     * the simulation runs sequentially (P=1).
     * <p>
     * A {@code maxThreads} value of 0 means "use full parallelism"
     * ({@link #effectiveParallelism}). All values are capped by
     * {@link #effectiveParallelism}.
     * <p>
     * Entries are sorted by ascending organism count automatically.
     * If not set or empty, all ticks use full parallelism.
     *
     * @param organisms  array of organism count thresholds
     * @param maxThreads corresponding maximum thread counts (0 = full parallelism)
     * @throws IllegalArgumentException if arrays have different lengths
     */
    public void setParallelismScaling(int[] organisms, int[] maxThreads) {
        if (organisms.length != maxThreads.length) {
            throw new IllegalArgumentException("organisms and maxThreads arrays must have the same length");
        }
        for (int i = 0; i < organisms.length; i++) {
            if (organisms[i] < 0) throw new IllegalArgumentException("organisms[" + i + "] must be >= 0, got " + organisms[i]);
            if (maxThreads[i] < 0) throw new IllegalArgumentException("maxThreads[" + i + "] must be >= 0, got " + maxThreads[i]);
        }
        // Sort by ascending organism count
        Integer[] indices = new Integer[organisms.length];
        for (int i = 0; i < indices.length; i++) indices[i] = i;
        java.util.Arrays.sort(indices, java.util.Comparator.comparingInt(i -> organisms[i]));
        this.scalingOrganisms = new int[organisms.length];
        this.scalingMaxThreads = new int[maxThreads.length];
        for (int i = 0; i < indices.length; i++) {
            this.scalingOrganisms[i] = organisms[indices[i]];
            this.scalingMaxThreads[i] = maxThreads[indices[i]];
        }
    }

    /**
     * Resolves the active thread count for the current tick based on organism count
     * and the configured scaling thresholds.
     *
     * @param organismCount the current number of organisms
     * @return the number of active threads to use (1 = sequential, &gt; 1 = parallel)
     */
    private int resolveActiveParallelism(int organismCount) {
        if (scalingOrganisms.length == 0) {
            return effectiveParallelism;
        }
        // Find the highest threshold <= organismCount (scan from end)
        for (int i = scalingOrganisms.length - 1; i >= 0; i--) {
            if (organismCount >= scalingOrganisms[i]) {
                int maxThreads = scalingMaxThreads[i];
                return (maxThreads == 0) ? effectiveParallelism : Math.min(maxThreads, effectiveParallelism);
            }
        }
        // Below lowest threshold → sequential
        return 1;
    }

    /**
     * Resolves the configured parallelism value to an effective thread count.
     *
     * @param configured The configured value (0 = auto, 1 = sequential, N = explicit)
     * @return The effective parallelism (always &gt;= 1)
     */
    private static int resolveParallelism(int configured) {
        if (configured < 0) {
            throw new IllegalArgumentException("runtime.parallelism must be >= 0, got " + configured);
        }
        if (configured == 0) {
            return Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
        }
        return configured;
    }

    /**
     * Shuts down the worker pool used for parallel planning and execution.
     * <p>
     * Must be called when the simulation is no longer needed to release thread resources.
     * Safe to call multiple times or when no pool was created (parallelism &lt;= 1).
     * Must not be called concurrently with {@link #tick()}.
     */
    public void shutdown() {
        if (workerPool != null) {
            workerPool.shutdown();
        }
    }

    /**
     * Returns the effective parallelism level for the Plan phase.
     * Thread-safe: returns an immutable value set at construction time.
     *
     * @return The number of worker threads (1 = sequential, &gt; 1 = parallel)
     */
    public int getEffectiveParallelism() {
        return effectiveParallelism;
    }

    /**
     * Resolves conflicts between organisms attempting to modify the same environment cell.
     * <p>
     * At every contested cell the contender with the smallest tick priority wins; the priority is
     * the organism's {@link OrganismRandom#tickStreamSeed()}, a pure function of seed, tick and
     * organism ID, so winners reshuffle every tick and no organism can hold a cell permanently.
     * Ties (bit-equal priorities) fall back to the lower organism ID. A loser stays marked for the
     * virtual machine, which books it as a failed instruction and keeps its instruction pointer
     * for a retry.
     * <p>
     * Every environment-modifying instruction targets at most one cell. An instruction reporting
     * several target cells would need a defined all-or-nothing semantics across cells and is
     * rejected until such an instruction exists.
     *
     * @param instructions The environment-modifying instructions planned for the current tick.
     */
    private void resolveConflicts(List<Instruction> instructions) {
        Int2ObjectOpenHashMap<List<Instruction>> contendersByFlatIndex = new Int2ObjectOpenHashMap<>();

        for (Instruction instruction : instructions) {
            // Every instruction is processed by the VM; losers are booked as failures there.
            instruction.setProcessedInTick(true);
            if (instruction instanceof IEnvironmentModifyingInstruction modInstruction) {
                List<int[]> targetCoords = modInstruction.getTargetCoordinates();
                // Without a target cell (e.g. invalid arguments) the instruction runs, detects the
                // error itself and fails gracefully.
                if (targetCoords == null || targetCoords.isEmpty()) {
                    continue;
                }
                if (targetCoords.size() > 1) {
                    throw new IllegalStateException(instruction.getName()
                            + " reports " + targetCoords.size() + " target cells; conflict resolution is defined for one");
                }
                int flatIndex = this.environment.properties.toFlatIndex(targetCoords.get(0));
                contendersByFlatIndex.computeIfAbsent(flatIndex, k -> new ArrayList<>()).add(instruction);
            }
        }

        for (var entry : contendersByFlatIndex.int2ObjectEntrySet()) {
            List<Instruction> contenders = entry.getValue();

            Instruction winner = contenders.get(0);
            for (int i = 1; i < contenders.size(); i++) {
                if (hasHigherPriority(contenders.get(i), winner)) {
                    winner = contenders.get(i);
                }
            }

            for (Instruction contender : contenders) {
                contender.setConflictStatus(contender == winner
                        ? Instruction.ConflictResolutionStatus.WON_EXECUTION
                        : Instruction.ConflictResolutionStatus.LOST_PRIORITY);
            }
        }
    }

    /**
     * Compares two contenders for the same cell: smaller tick priority wins, lower organism ID
     * breaks ties.
     */
    private static boolean hasHigherPriority(Instruction candidate, Instruction incumbent) {
        Organism candidateOrganism = candidate.getOrganism();
        Organism incumbentOrganism = incumbent.getOrganism();
        long candidatePriority = candidateOrganism.getRandom().tickStreamSeed();
        long incumbentPriority = incumbentOrganism.getRandom().tickStreamSeed();
        if (candidatePriority != incumbentPriority) {
            return candidatePriority < incumbentPriority;
        }
        return candidateOrganism.getId() < incumbentOrganism.getId();
    }

    /**
     * Returns the list of all organisms in the simulation.
     * @return A list of organisms.
     */
    public List<Organism> getOrganisms() { return organisms; }

    /**
     * Removes all dead organisms from the organisms list.
     * Called by SimulationEngine after dead organisms have been serialized
     * for their final appearance in the data pipeline.
     */
    public void pruneDeadOrganisms() {
        organisms.removeIf(Organism::isDead);
    }

    /**
     * Returns the simulation environment.
     * @return The environment.
     */
    public Environment getEnvironment() { return environment; }

    /**
     * Returns the virtual machine instance used by the simulation.
     * @return The virtual machine.
     */
    public VirtualMachine getVirtualMachine() { return vm; }

    /**
     * Returns the current simulation tick count.
     * @return The current tick.
     */
    public long getCurrentTick() { return currentTick; }

    /**
     * Adds a new organism that will be introduced in the next tick.
     * @param organism The new organism to add.
     */
    public void addNewOrganism(Organism organism) {
        this.newOrganismsThisTick.add(organism);
    }

}