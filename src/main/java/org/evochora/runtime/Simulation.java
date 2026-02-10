package org.evochora.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.isa.IEnvironmentModifyingInstruction;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Organism;
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

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Manages the core simulation loop, including organism lifecycle, instruction execution,
 * and environment interaction. It orchestrates the simulation tick by tick, handling
 * instruction planning, conflict resolution, and execution.
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
    private final InterceptionContext interceptContext = new InterceptionContext();  // Reusable, zero allocation
    private final DeathContext deathContext = new DeathContext();  // Reusable, zero allocation
    private int nextOrganismId = 1;
    private final LongOpenHashSet allGenomesEverSeen = new LongOpenHashSet();
    private IRandomProvider randomProvider;

    private Map<String, ProgramArtifact> programArtifacts = new HashMap<>();

    /**
     * Sets the program artifacts used in the simulation.
     * @param artifacts A map of program names to their compiled artifacts.
     */
    public void setProgramArtifacts(Map<String, ProgramArtifact> artifacts) {
        this.programArtifacts = artifacts;
    }

    /**
     * Retrieves the program artifacts used in the simulation.
     * @return A map of program artifacts.
     */
    public Map<String, ProgramArtifact> getProgramArtifacts() {
        return programArtifacts;
    }

    /**
     * Constructs a new Simulation instance.
     * @param environment The simulation environment.
     * @param policyManager The manager for thermodynamic policies.
     * @param organismConfig Configuration for organisms (energy limits, etc.).
     */
    public Simulation(Environment environment, ThermodynamicPolicyManager policyManager, Config organismConfig) {
        this.environment = environment;
        this.policyManager = policyManager;
        this.organismConfig = organismConfig;
        this.organisms = new ArrayList<>();
        this.vm = new VirtualMachine(this);
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
     * @return Simulation ready for organism addition and resumption
     */
    public static Simulation forResume(
            Environment environment,
            long currentTick,
            long totalOrganismsCreated,
            LongOpenHashSet allGenomesEverSeen,
            ThermodynamicPolicyManager policyManager,
            Config organismConfig) {

        Simulation sim = new Simulation(environment, policyManager, organismConfig);
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
     * Sets the random number provider for the simulation.
     * @param provider The random provider to use.
     */
    public void setRandomProvider(IRandomProvider provider) {
        this.randomProvider = provider;
    }

    /**
     * Gets the random number provider for the simulation.
     * @return The current random provider.
     */
    public IRandomProvider getRandomProvider() {
        return this.randomProvider;
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
     * Executes a single simulation tick. During a tick, tick plugins are executed first,
     * then each organism plans an instruction, conflicts are resolved, and the winning
     * instructions are executed.
     */
    public void tick() {
        newOrganismsThisTick.clear();

        // Execute tick plugins before Plan-Resolve-Execute cycle
        for (ITickPlugin plugin : tickPlugins) {
            try {
                plugin.execute(this);
            } catch (Exception e) {
                LOG.warn("Tick plugin '{}' failed at tick {}: {}",
                        plugin.getClass().getSimpleName(), currentTick, e.getMessage());
            }
        }

        // PHASE 1: Plan - each organism plans their next instruction
        // Operands are resolved in vm.plan() for conflict resolution and interception
        List<Instruction> plannedInstructions = new ArrayList<>();
        for (Organism organism : this.organisms) {
            if (organism.isDead()) continue;

            Instruction instruction = vm.plan(organism);

            // INSTRUCTION INCEPTION: Allow interceptors to inspect/modify planned instruction
            // Early-exit when no interceptors registered (zero overhead in common case)
            if (!instructionInterceptors.isEmpty()) {
                interceptContext.reset(organism, instruction);

                for (IInstructionInterceptor interceptor : instructionInterceptors) {
                    try {
                        interceptor.intercept(interceptContext);
                    } catch (Exception e) {
                        LOG.warn("Interceptor '{}' failed for organism {} at tick {}: {}",
                                interceptor.getClass().getSimpleName(), organism.getId(), currentTick, e.getMessage());
                    }
                }

                // Instruction may have been replaced by interceptor
                instruction = interceptContext.getInstruction();
            }

            instruction.setExecutedInTick(false);
            instruction.setConflictStatus(Instruction.ConflictResolutionStatus.NOT_APPLICABLE);
            plannedInstructions.add(instruction);
        }

        resolveConflicts(plannedInstructions);

        // PHASE 3: Execute - run winning instructions, then instant-skip NOPs
        for (Instruction instruction : plannedInstructions) {
            Organism organism = instruction.getOrganism();
            boolean wasAlive = !organism.isDead();

            if (instruction.isExecutedInTick()) {
                vm.execute(instruction);

                // Instant-skip: advance past any NOP/LABEL/empty cells
                boolean failedInExecution = organism.isInstructionFailed();
                organism.skipNopCells(environment);

                // Apply error penalty for post-execution failures (e.g., max-skip)
                // not already penalized inside vm.execute()
                if (!failedInExecution && organism.isInstructionFailed()) {
                    int penalty = organismConfig.getInt("error-penalty-cost");
                    organism.takeEr(penalty);
                }
            }

            // Handle organism death: call death handlers, then clear ownership
            if (wasAlive && organism.isDead()) {
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

            if (organism.isLoggingEnabled()) {
                LOG.debug("Tick={} Org={} Instr={} Status={}",
                        currentTick,
                        organism.getId(),
                        instruction.getName(),
                        instruction.getConflictStatus());
                LOG.debug("  IP={} DP={} DV={} ER={}",
                        java.util.Arrays.toString(organism.getIp()),
                        java.util.Arrays.toString(organism.getDp(0)), // CORRECTED
                        java.util.Arrays.toString(organism.getDv()),
                        organism.getEr());
                LOG.debug("  DR={} PR={} DS={} CS={}",
                        organism.getDrs(),
                        organism.getPrs(),
                        organism.getDataStack(),
                        organism.getCallStack());
            }
        }

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
        this.currentTick++;
    }

    /**
     * Resolves conflicts between organisms attempting to modify the same environment coordinates.
     * The winning instruction is determined based on organism ID.
     * @param allPlannedInstructions A list of all instructions planned for the current tick.
     */
    private void resolveConflicts(List<Instruction> allPlannedInstructions) {
        Map<List<Integer>, List<IEnvironmentModifyingInstruction>> actionsByCoordinate = new HashMap<>();

        for (Instruction instruction : allPlannedInstructions) {
            if (instruction instanceof IEnvironmentModifyingInstruction modInstruction) {
                List<int[]> targetCoords = modInstruction.getTargetCoordinates();
                if (targetCoords != null && !targetCoords.isEmpty()) {
                    for (int[] coord : targetCoords) {
                        List<Integer> coordAsList = Arrays.stream(coord).boxed().collect(Collectors.toList());
                        actionsByCoordinate.computeIfAbsent(coordAsList, k -> new ArrayList<>()).add(modInstruction);
                    }
                } else {
                    // FIX: Always execute if no targets are specified (e.g. invalid arguments).
                    // The instruction's execute() method will then run, detect the error, and fail gracefully.
                    instruction.setExecutedInTick(true);
                }
            } else {
                instruction.setExecutedInTick(true);
            }
        }

        for (Map.Entry<List<Integer>, List<IEnvironmentModifyingInstruction>> entry : actionsByCoordinate.entrySet()) {
            List<IEnvironmentModifyingInstruction> actionsAtCoord = entry.getValue();
            if (actionsAtCoord.isEmpty()) continue;

            if (actionsAtCoord.size() > 1) {
                actionsAtCoord.sort(Comparator.comparingInt(action -> ((Instruction)action).getOrganism().getId()));
                IEnvironmentModifyingInstruction winningAction = actionsAtCoord.get(0);
                ((Instruction)winningAction).setExecutedInTick(true);
                ((Instruction)winningAction).setConflictStatus(Instruction.ConflictResolutionStatus.WON_EXECUTION);

                for (int i = 1; i < actionsAtCoord.size(); i++) {
                    IEnvironmentModifyingInstruction losingAction = actionsAtCoord.get(i);
                    ((Instruction)losingAction).setExecutedInTick(false);
                    ((Instruction)losingAction).setConflictStatus(Instruction.ConflictResolutionStatus.LOST_LOWER_ID_WON);
                }
            } else {
                ((Instruction)actionsAtCoord.get(0)).setExecutedInTick(true);
                ((Instruction)actionsAtCoord.get(0)).setConflictStatus(Instruction.ConflictResolutionStatus.WON_EXECUTION);
            }
        }
    }

    /**
     * Returns the list of all organisms in the simulation.
     * @return A list of organisms.
     */
    public List<Organism> getOrganisms() { return organisms; }

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