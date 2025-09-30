package org.evochora.runtime;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.isa.IEnvironmentModifyingInstruction;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.spi.IRandomProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages the core simulation loop, including organism lifecycle, instruction execution,
 * and environment interaction. It orchestrates the simulation tick by tick, handling
 * instruction planning, conflict resolution, and execution.
 */
public class Simulation {
    private static final Logger LOG = LoggerFactory.getLogger(Simulation.class);
    private final Environment environment;
    private final VirtualMachine vm;
    private final List<Organism> organisms;
    private long currentTick = 0L;
    /**
     * A flag to pause or resume the simulation.
     */
    public boolean paused = true;
    private final List<Organism> newOrganismsThisTick = new ArrayList<>();
    private int nextOrganismId = 1;
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
     */
    public Simulation(Environment environment) {
        this.environment = environment;
        this.organisms = new ArrayList<>();
        this.vm = new VirtualMachine(this);
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
     * Returns the next available unique ID for an organism.
     * @return A unique organism ID.
     */
    public int getNextOrganismId() {
        return nextOrganismId++;
    }

    /**
     * Returns the logger for this class.
     * @return The SLF4J logger.
     */
    public Logger getLogger() {
        return LOG;
    }

    /**
     * Executes a single simulation tick. During a tick, each organism plans an instruction,
     * conflicts are resolved, and the winning instructions are executed.
     */
    public void tick() {
        newOrganismsThisTick.clear();

        List<Instruction> plannedInstructions = new ArrayList<>();
        for (Organism organism : this.organisms) {
            if (!organism.isDead()) {
                Instruction instruction = vm.plan(organism);
                instruction.setExecutedInTick(false);
                instruction.setConflictStatus(Instruction.ConflictResolutionStatus.NOT_APPLICABLE);
                plannedInstructions.add(instruction);
            }
        }

        resolveConflicts(plannedInstructions);

        for (Instruction instruction : plannedInstructions) {
            if (instruction.isExecutedInTick()) {
                vm.execute(instruction, this);
            }
            Organism organism = instruction.getOrganism();
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
                    if (this.organisms.size() == 1) {
                        instruction.setExecutedInTick(true);
                    } else {
                        instruction.setExecutedInTick(false);
                        instruction.setConflictStatus(Instruction.ConflictResolutionStatus.LOST_OTHER_REASON);
                    }
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