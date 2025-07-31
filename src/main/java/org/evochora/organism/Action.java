// src/main/java/org/evochora/organism/actions/Action.java
package org.evochora.organism;

import org.evochora.Simulation;

public abstract class Action {
    protected final Organism organism;

    public Action(Organism organism) {
        this.organism = organism;
    }

    /**
     * Gibt den Organismus zurück, der diese Aktion geplant hat.
     */
    public final Organism getOrganism() {
        return this.organism;
    }

    /**
     * Führt die geplante Aktion aus und verändert den Zustand der Simulation.
     * Jede konkrete Action-Klasse muss diese Methode implementieren.
     * @param simulation Die Haupt-Simulationsinstanz.
     */
    public abstract void execute(Simulation simulation);
}