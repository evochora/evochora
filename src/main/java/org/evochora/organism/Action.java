// src/main/java/org/evochora/organism/actions/Action.java
package org.evochora.organism.actions;

import org.evochora.AssemblyProgram;
import org.evochora.organism.Organism;
import org.evochora.Simulation;
import org.evochora.world.World;

import java.util.List;
import java.util.Map;

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

    /**
     * Statische Planungs-Methode (Konvention). Liest die Maschinencode-Argumente aus der Welt.
     * Jede Action-Klasse muss eine Methode mit dieser Signatur bereitstellen.
     * public static Action plan(Organism organism, World world)
     */

    /**
     * Statische Assembler-Methode (Konvention). Übersetzt String-Argumente in Maschinencode.
     * Jede Action-Klasse muss eine Methode mit dieser Signatur bereitstellen.
     * public static List<Integer> assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap)
     */
}