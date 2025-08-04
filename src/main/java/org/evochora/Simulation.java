// src/main/java/org/evochora/Simulation.java
package org.evochora;

import org.evochora.organism.IWorldModifyingInstruction;
import org.evochora.organism.Instruction;
import org.evochora.organism.NopInstruction;
import org.evochora.organism.Organism;
import org.evochora.world.World;

import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

public class Simulation {
    private final World world;
    private final List<Organism> organisms;
    private int currentTick = 0;
    public boolean paused = true;
    private final List<Organism> newOrganismsThisTick = new ArrayList<>();

    // Die Action-Registry wurde entfernt. Die Logik liegt jetzt in der Instruction-Klasse.

    private final Logger logger;
    private int nextOrganismId = 0;

    public Simulation(World world) {
        this.world = world;
        this.organisms = new ArrayList<>();
        this.logger = new Logger(world, this);
    }

    public void addOrganism(Organism organism) {
        this.organisms.add(organism);
    }

    public int getNextOrganismId() {
        return nextOrganismId++;
    }

    public Logger getLogger() {
        return logger;
    }

    public void tick() {
        newOrganismsThisTick.clear();

        // Phase 1: Planning
        List<Instruction> plannedInstructions = new ArrayList<>();
        for (Organism organism : this.organisms) {
            if (!organism.isDead()) {
                // KORRIGIERT: Der Organismus plant jetzt ohne die Registry als Parameter.
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
            if (instruction.isExecutedInTick()) {
                instruction.getOrganism().processTickAction(instruction, this);
            }
            this.logger.logTickDetails(instruction.getOrganism(), instruction);
        }

        this.organisms.addAll(newOrganismsThisTick);
        // Tote Organismen bleiben jetzt in der Liste und werden nur übersprungen.
        this.currentTick++;
    }

    private void resolveConflicts(List<Instruction> allPlannedInstructions) {
        Map<List<Integer>, List<IWorldModifyingInstruction>> actionsByCoordinate = new HashMap<>();

        for (Instruction instruction : allPlannedInstructions) {
            if (instruction instanceof IWorldModifyingInstruction modInstruction) {
                List<int[]> targetCoords = modInstruction.getTargetCoordinates();
                if (targetCoords == null || targetCoords.isEmpty()) {
                    modInstruction.setExecutedInTick(false);
                    modInstruction.setConflictStatus(Instruction.ConflictResolutionStatus.LOST_OTHER_REASON);
                    continue;
                }

                for (int[] coord : targetCoords) {
                    List<Integer> coordAsList = Arrays.stream(coord).boxed().collect(Collectors.toList());
                    actionsByCoordinate.computeIfAbsent(coordAsList, k -> new ArrayList<>()).add(modInstruction);
                }
            } else {
                instruction.setExecutedInTick(true);
            }
        }

        for (Map.Entry<List<Integer>, List<IWorldModifyingInstruction>> entry : actionsByCoordinate.entrySet()) {
            List<IWorldModifyingInstruction> actionsAtCoord = entry.getValue();

            if (actionsAtCoord.isEmpty()) continue;

            actionsAtCoord.sort(Comparator.comparingInt(action -> action.getOrganism().getId()));

            IWorldModifyingInstruction winningAction = actionsAtCoord.get(0);
            winningAction.setExecutedInTick(true);
            winningAction.setConflictStatus(Instruction.ConflictResolutionStatus.WON_EXECUTION);

            for (int i = 1; i < actionsAtCoord.size(); i++) {
                IWorldModifyingInstruction losingAction = actionsAtCoord.get(i);
                losingAction.setExecutedInTick(false);
                losingAction.setConflictStatus(Instruction.ConflictResolutionStatus.LOST_LOWER_ID_WON);
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