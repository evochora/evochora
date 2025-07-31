// src/main/java/org/evochora/Simulation.java
package org.evochora;

import org.evochora.organism.*;
import org.evochora.world.World;

import java.util.*;
import java.util.function.BiFunction;

public class Simulation {
    private final World world;
    private final List<Organism> organisms;
    private int currentTick = 0;
    private int nextOrganismId = 0;
    public boolean paused = true;
    private final List<Organism> newOrganismsThisTick = new ArrayList<>();

    private final Map<Integer, BiFunction<Organism, World, Action>> actionPlannerRegistry;

    public Simulation(World world) {
        this.world = world;
        this.organisms = new ArrayList<>();

        this.actionPlannerRegistry = new HashMap<>();
        actionPlannerRegistry.put(Config.OP_NOP, NopAction::plan);
        actionPlannerRegistry.put(Config.OP_SETL, SetlAction::plan);
        actionPlannerRegistry.put(Config.OP_SETR, SetrAction::plan);
        actionPlannerRegistry.put(Config.OP_SETV, SetvAction::plan);
        actionPlannerRegistry.put(Config.OP_ADD, AddAction::plan);
        actionPlannerRegistry.put(Config.OP_SUB, SubAction::plan);
        actionPlannerRegistry.put(Config.OP_NAND, NandAction::plan);
        actionPlannerRegistry.put(Config.OP_IF, IfAction::plan);
        actionPlannerRegistry.put(Config.OP_IFLT, IfAction::plan);
        actionPlannerRegistry.put(Config.OP_IFGT, IfAction::plan);
        actionPlannerRegistry.put(Config.OP_JUMP, JumpAction::plan);
        actionPlannerRegistry.put(Config.OP_TURN, TurnAction::plan);
        actionPlannerRegistry.put(Config.OP_SEEK, SeekAction::plan);
        actionPlannerRegistry.put(Config.OP_SYNC, SyncAction::plan);
        actionPlannerRegistry.put(Config.OP_PEEK, PeekAction::plan);
        actionPlannerRegistry.put(Config.OP_POKE, PokeAction::plan);
        actionPlannerRegistry.put(Config.OP_SCAN, ScanAction::plan);
        actionPlannerRegistry.put(Config.OP_NRG, NrgAction::plan);
        actionPlannerRegistry.put(Config.OP_FORK, ForkAction::plan);
        actionPlannerRegistry.put(Config.OP_DIFF, DiffAction::plan);
    }

    public void addOrganism(Organism organism) {
        this.organisms.add(organism);
    }

    public int getNextOrganismId() {
        return nextOrganismId++;
    }

    public void tick() {
        newOrganismsThisTick.clear();

        List<Action> plannedActions = new ArrayList<>();
        for (Organism organism : this.organisms) {
            if (!organism.isDead()) {
                Action action = organism.planTick(this.world, this.actionPlannerRegistry);
                if (action != null) {
                    plannedActions.add(action);
                }
            }
        }

        List<Action> finalActions = resolveConflicts(plannedActions);

        for (Action action : finalActions) {
            action.getOrganism().executeAction(action, this);
        }

        this.organisms.addAll(newOrganismsThisTick);
        this.organisms.removeIf(Organism::isDead);

        this.currentTick++;
    }

    private List<Action> resolveConflicts(List<Action> plannedActions) {
        Map<String, Action> pokeTargets = new HashMap<>();
        List<Action> finalActions = new ArrayList<>();

        for (Action action : plannedActions) {
            if (action instanceof PokeAction pokeAction) {
                String targetKey = Arrays.toString(pokeAction.getTargetCoordinate());
                if (pokeTargets.containsKey(targetKey)) {
                    if (pokeAction.getOrganism().getId() < pokeTargets.get(targetKey).getOrganism().getId()) {
                        pokeTargets.put(targetKey, pokeAction);
                    }
                } else {
                    pokeTargets.put(targetKey, pokeAction);
                }
            } else {
                finalActions.add(action);
            }
        }

        finalActions.addAll(pokeTargets.values());
        return finalActions;
    }

    // Getter
    public List<Organism> getOrganisms() { return organisms; }
    public World getWorld() { return world; }
    public int getCurrentTick() { return currentTick; }
    public void addNewOrganism(Organism organism) {
        this.newOrganismsThisTick.add(organism);
    }
}