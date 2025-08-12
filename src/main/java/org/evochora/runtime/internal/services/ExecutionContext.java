package org.evochora.runtime.internal.services;

import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.World;

/**
 * Kapselt alle zur Laufzeit einer Instruktion benötigten Informationen und Abhängigkeiten.
 * Dieses Objekt wird von der VirtualMachine erstellt und an die ausführenden
 * Einheiten übergeben, um globale Zugriffe zu vermeiden.
 */
public class ExecutionContext {

    private final Organism organism;
    private final World world;

    public ExecutionContext(Organism organism, World world) {
        this.organism = organism;
        this.world = world;
    }

    public Organism getOrganism() {
        return organism;
    }

    public World getWorld() {
        return world;
    }
}
