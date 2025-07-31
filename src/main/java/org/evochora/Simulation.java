// src/main/java/org/evochora/Simulation.java
package org.evochora;

import org.evochora.organism.actions.Action;
import java.util.ArrayList;
import java.util.List;

public class Simulation {
    private final World world;
    private final List<Organism> organisms;
    private int currentTick = 0;
    private int nextOrganismId = 0; // Dieser Zähler verwaltet die IDs
    public boolean paused = true;

    public Simulation(World world) {
        this.world = world;
        this.organisms = new ArrayList<>();
    }

    /**
     * Gibt die nächste verfügbare, einzigartige ID für einen neuen Organismus zurück.
     * Diese Methode ist package-private, sodass nur Klassen im selben Paket (wie Organism) sie aufrufen können.
     * @return Die nächste einzigartige ID.
     */
    int getNextOrganismId() {
        return nextOrganismId++;
    }

    /**
     * Fügt einen bereits vollständig erstellten Organismus zur Simulation hinzu.
     * @param organism Der neue Organismus.
     */
    public void addOrganism(Organism organism) {
        this.organisms.add(organism);
    }

    public void tick() {
        List<Action> plannedActions = new ArrayList<>();
        for (Organism organism : this.organisms) {
            if (!organism.isDead()) {
                Action action = organism.planTick(this.world);
                if (action != null) {
                    plannedActions.add(action);
                }
            }
        }

        for (Action action : plannedActions) {
            action.getOrganism().executeAction(action, this.world);
        }

        this.currentTick++;
    }

    // Getter für den Renderer
    public List<Organism> getOrganisms() { return organisms; }
    public World getWorld() { return world; }
    public int getCurrentTick() { return currentTick; }
}