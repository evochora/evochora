// src/main/java/org/evochora/Simulation.java
package org.evochora;

import org.evochora.organism.Action;
import org.evochora.organism.IWorldModifyingAction;
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

    private final Map<Integer, BiFunction<Organism, World, Action>> actionPlannerRegistry;
    private final Logger logger;
    private int nextOrganismId = 0;

    public Simulation(World world) {
        this.world = world;
        this.organisms = new ArrayList<>();

        this.actionPlannerRegistry = new HashMap<>();
        actionPlannerRegistry.put(Config.OP_NOP, org.evochora.organism.NopAction::plan);
        actionPlannerRegistry.put(Config.OP_SETL, org.evochora.organism.SetlAction::plan);
        actionPlannerRegistry.put(Config.OP_SETR, org.evochora.organism.SetrAction::plan);
        actionPlannerRegistry.put(Config.OP_SETV, org.evochora.organism.SetvAction::plan);
        actionPlannerRegistry.put(Config.OP_ADD, org.evochora.organism.AddAction::plan);
        actionPlannerRegistry.put(Config.OP_SUB, org.evochora.organism.SubAction::plan);
        actionPlannerRegistry.put(Config.OP_NAND, org.evochora.organism.NandAction::plan);
        actionPlannerRegistry.put(Config.OP_IF, org.evochora.organism.IfAction::plan);
        actionPlannerRegistry.put(Config.OP_IFLT, org.evochora.organism.IfAction::plan);
        actionPlannerRegistry.put(Config.OP_IFGT, org.evochora.organism.IfAction::plan);
        actionPlannerRegistry.put(Config.OP_JUMP, org.evochora.organism.JumpAction::plan);
        actionPlannerRegistry.put(Config.OP_TURN, org.evochora.organism.TurnAction::plan);
        actionPlannerRegistry.put(Config.OP_SEEK, org.evochora.organism.SeekAction::plan);
        actionPlannerRegistry.put(Config.OP_SYNC, org.evochora.organism.SyncAction::plan);
        actionPlannerRegistry.put(Config.OP_PEEK, org.evochora.organism.PeekAction::plan);
        actionPlannerRegistry.put(Config.OP_POKE, org.evochora.organism.PokeAction::plan);
        actionPlannerRegistry.put(Config.OP_SCAN, org.evochora.organism.ScanAction::plan);
        actionPlannerRegistry.put(Config.OP_NRG, org.evochora.organism.NrgAction::plan);
        actionPlannerRegistry.put(Config.OP_FORK, org.evochora.organism.ForkAction::plan);
        actionPlannerRegistry.put(Config.OP_DIFF, org.evochora.organism.DiffAction::plan);

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

        // Phase 1: Planning - Alle Organismen planen ihre Aktionen
        List<Action> plannedActions = new ArrayList<>();
        for (Organism organism : this.organisms) {
            if (!organism.isDead()) {
                Action action = organism.planTick(this.world, this.actionPlannerRegistry);
                // Setze initialen Status für Logging
                action.setExecutedInTick(false); // Standardmäßig nicht ausgeführt
                action.setConflictStatus(Action.ConflictResolutionStatus.NOT_APPLICABLE); // Standard

                plannedActions.add(action);
            }
        }

        // Phase 2: Conflict Resolution - Bestimme, welche weltverändernden Aktionen ausgeführt werden dürfen
        resolveConflicts(plannedActions);

        // Phase 3: Execution & Logging - Führe die Aktionen aus und logge Details
        for (Action action : plannedActions) {
            // Die Ausführungslogik (Energieabzug, IP-Vorrücken, spezifische Aktion) wird jetzt
            // in Organism.processTickAction gekapselt und nur dort aufgerufen,
            // wenn die Aktion tatsächlich ausgeführt werden soll.
            if (action.isExecutedInTick()) {
                action.getOrganism().processTickAction(action, this); // HINZUGEFÜGT: Aufruf der neuen Methode
            }
            this.logger.logTickDetails(action.getOrganism(), action);
        }

        this.organisms.addAll(newOrganismsThisTick);
        this.organisms.removeIf(Organism::isDead);

        this.currentTick++;
    }

    private void resolveConflicts(List<Action> allPlannedActions) {
        // Map, um Aktionen nach betroffenen Koordinaten zu gruppieren
        Map<List<Integer>, List<IWorldModifyingAction>> actionsByCoordinate = new HashMap<>();

        for (Action action : allPlannedActions) {
            if (action instanceof IWorldModifyingAction modAction) {
                // Sammle alle weltverändernden Aktionen
                List<int[]> targetCoords = modAction.getTargetCoordinates();
                // Hier prüfen, ob getTargetCoordinates() null oder leer ist, falls Aktion schon fehlerhaft
                if (targetCoords == null || targetCoords.isEmpty()) {
                    modAction.setExecutedInTick(false);
                    modAction.setConflictStatus(Action.ConflictResolutionStatus.LOST_OTHER_REASON); // z.B. Invalid Target Coord
                    continue; // Diese Aktion überspringen
                }

                for (int[] coord : targetCoords) {
                    List<Integer> coordAsList = Arrays.stream(coord).boxed().collect(Collectors.toList());
                    actionsByCoordinate.computeIfAbsent(coordAsList, k -> new ArrayList<>()).add(modAction);
                }
            } else {
                // Aktionen, die NICHT weltverändernd sind, werden immer ausgeführt.
                // Ihr `executedInTick` Flag wird direkt hier auf true gesetzt.
                action.setExecutedInTick(true);
            }
        }

        // Löse Konflikte für jede Koordinate
        for (Map.Entry<List<Integer>, List<IWorldModifyingAction>> entry : actionsByCoordinate.entrySet()) {
            List<IWorldModifyingAction> actionsAtCoord = entry.getValue();

            if (actionsAtCoord.isEmpty()) continue;

            // Sortiere Aktionen, um Determinismus zu gewährleisten (z.B. niedrigste Organismus-ID gewinnt)
            actionsAtCoord.sort(Comparator.comparingInt(action -> action.getOrganism().getId()));

            // Die erste Aktion nach dem Sortieren ist der Gewinner für diese Koordinate
            IWorldModifyingAction winningAction = actionsAtCoord.get(0);

            // Setze den Status für die gewinnende Aktion
            winningAction.setExecutedInTick(true);
            winningAction.setConflictStatus(Action.ConflictResolutionStatus.WON_EXECUTION);

            // Setze den Status für die verlierenden Aktionen
            for (int i = 1; i < actionsAtCoord.size(); i++) {
                IWorldModifyingAction losingAction = actionsAtCoord.get(i);
                losingAction.setExecutedInTick(false); // Wird nicht ausgeführt
                losingAction.setConflictStatus(Action.ConflictResolutionStatus.LOST_LOWER_ID_WON); // Grund: niedrigere ID hat gewonnen
            }
        }
    }


    // Getter
    public List<Organism> getOrganisms() { return organisms; }
    public World getWorld() { return world; }
    public int getCurrentTick() { return currentTick; }
    public void addNewOrganism(Organism organism) {
        this.newOrganismsThisTick.add(organism);
    }
}