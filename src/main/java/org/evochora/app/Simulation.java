package org.evochora.app;

import org.evochora.runtime.isa.IWorldModifyingInstruction;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class Simulation {
    private static final Logger LOG = LoggerFactory.getLogger(Simulation.class);
    private final World world;
    private final List<Organism> organisms;
    private int currentTick = 0;
    public boolean paused = true;
    private final List<Organism> newOrganismsThisTick = new ArrayList<>();
    private int nextOrganismId = 0;

    public Simulation(World world) {
        this.world = world;
        this.organisms = new ArrayList<>();
        // LOG.info("Eine neue Simulation wurde für die Welt erstellt: {}", world.getName()); // Beispiel für Logging
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

        // Phase 1: Planning
        List<Instruction> plannedInstructions = new ArrayList<>();
        for (Organism organism : this.organisms) {
            if (!organism.isDead()) {
                Instruction instruction = organism.planTick(this.world);
                instruction.setExecutedInTick(false);
                instruction.setConflictStatus(Instruction.ConflictResolutionStatus.NOT_APPLICABLE);
                plannedInstructions.add(instruction);
            }
        }

        // Phase 2: Conflict Resolution
        resolveConflicts(plannedInstructions);

        // Phase 3: Execution & Logging
        for (Instruction instruction : plannedInstructions) {
            // Die getOrganism() Methode wird hier korrekt aufgerufen.
            Organism organism = instruction.getOrganism();
            if (instruction.isExecutedInTick()) {
                organism.processTickAction(instruction, this);
            }
            // Ersetzt die alte Methode durch strukturiertes Logging
            if (organism.isLoggingEnabled()) {
                LOG.debug("Tick[{}], Organism[{}], Instruction[{}], Status[{}]",
                        currentTick,
                        organism.getId(),
                        instruction.getName(),
                        instruction.getConflictStatus());
            }
        }

        this.organisms.addAll(newOrganismsThisTick);
        this.currentTick++;
    }

    private void resolveConflicts(List<Instruction> allPlannedInstructions) {
        Map<List<Integer>, List<IWorldModifyingInstruction>> actionsByCoordinate = new HashMap<>();

        for (Instruction instruction : allPlannedInstructions) {
            if (instruction instanceof IWorldModifyingInstruction modInstruction) {
                List<int[]> targetCoords = modInstruction.getTargetCoordinates();
                if (targetCoords != null && !targetCoords.isEmpty()) {
                    for (int[] coord : targetCoords) {
                        List<Integer> coordAsList = Arrays.stream(coord).boxed().collect(Collectors.toList());
                        actionsByCoordinate.computeIfAbsent(coordAsList, k -> new ArrayList<>()).add(modInstruction);
                    }
                } else {
                    // This instruction modifies the world but couldn't determine its target.
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

        for (Map.Entry<List<Integer>, List<IWorldModifyingInstruction>> entry : actionsByCoordinate.entrySet()) {
            List<IWorldModifyingInstruction> actionsAtCoord = entry.getValue();
            if (actionsAtCoord.isEmpty()) continue;

            if (actionsAtCoord.size() > 1) {
                actionsAtCoord.sort(Comparator.comparingInt(action -> ((Instruction)action).getOrganism().getId()));

                IWorldModifyingInstruction winningAction = actionsAtCoord.get(0);
                ((Instruction)winningAction).setExecutedInTick(true);
                ((Instruction)winningAction).setConflictStatus(Instruction.ConflictResolutionStatus.WON_EXECUTION);

                for (int i = 1; i < actionsAtCoord.size(); i++) {
                    IWorldModifyingInstruction losingAction = actionsAtCoord.get(i);
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
    public World getWorld() { return world; }
    public int getCurrentTick() { return currentTick; }
    public void addNewOrganism(Organism organism) {
        this.newOrganismsThisTick.add(organism);
    }
}