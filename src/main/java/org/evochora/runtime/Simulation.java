package org.evochora.runtime;

import org.evochora.runtime.isa.IEnvironmentModifyingInstruction;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Organism;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class Simulation {
    private static final Logger LOG = LoggerFactory.getLogger(Simulation.class);
    private final Environment environment;
    private final org.evochora.runtime.VirtualMachine vm; // NEU
    private final List<Organism> organisms;
    private long currentTick = 0L;
    public boolean paused = true;
    private final List<Organism> newOrganismsThisTick = new ArrayList<>();
    private int nextOrganismId = 0;

    public Simulation(Environment environment) {
        this.environment = environment;
        this.organisms = new ArrayList<>();
            this.vm = new org.evochora.runtime.VirtualMachine(this); // NEU
        // LOG.info("Eine neue Simulation wurde für die Welt erstellt: {}", environment.getName()); // Beispiel für Logging
    }

    public void addOrganism(Organism organism) {
        this.organisms.add(organism);
    }

    public int getNextOrganismId() {
        return nextOrganismId++;
    }

    public Logger getLogger() {
        return LOG;
    }

    public void tick() {
        newOrganismsThisTick.clear();

        // Phase 1: Planung durch die VM
        List<Instruction> plannedInstructions = new ArrayList<>();
        for (Organism organism : this.organisms) {
            if (!organism.isDead()) {
                Instruction instruction = vm.plan(organism);
                instruction.setExecutedInTick(false); // Initialisiere Status für Konfliktauflösung
                instruction.setConflictStatus(Instruction.ConflictResolutionStatus.NOT_APPLICABLE);
                plannedInstructions.add(instruction);
            }
        }

        // Phase 2: Konfliktauflösung (bleibt vorerst in der Simulation)
        resolveConflicts(plannedInstructions);

        // Phase 3: Ausführung durch die VM
        for (Instruction instruction : plannedInstructions) {
            if (instruction.isExecutedInTick()) {
                vm.execute(instruction);
            }
            // Logging (detailed)
            Organism organism = instruction.getOrganism();
            if (organism.isLoggingEnabled()) {
                LOG.debug("Tick={} Org={} Instr={} Status={}",
                        currentTick,
                        organism.getId(),
                        instruction.getName(),
                        instruction.getConflictStatus());
                LOG.debug("  IP={} DP={} DV={} ER={}",
                        java.util.Arrays.toString(organism.getIp()),
                        java.util.Arrays.toString(organism.getDp()),
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
                    // This instruction modifies the environment but couldn't determine its target.
                    // It could be a stack-based instruction where the target is unknown until execution.
                    // For now, let it execute if there's only one organism to avoid stalls.
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


    public List<Organism> getOrganisms() { return organisms; }
    public Environment getEnvironment() { return environment; }
    public org.evochora.runtime.VirtualMachine getVirtualMachine() { return vm; } // NEU
    public long getCurrentTick() { return currentTick; }
    public void addNewOrganism(Organism organism) {
        this.newOrganismsThisTick.add(organism);
    }
}