// src/main/java/org/evochora/organism/Action.java
package org.evochora.organism;

import org.evochora.Simulation;

public abstract class Action {
    protected final Organism organism;

    public Action(Organism organism) {
        this.organism = organism;
    }

    public final Organism getOrganism() {
        return this.organism;
    }

    public abstract void execute(Simulation simulation);

    // Konvention für statische Methoden, die jede Unterklasse implementieren muss.
    // public static Action plan(Organism organism, World world)
    // public static List<Integer> assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap)
}