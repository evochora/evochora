package org.evochora.runtime.internal.services;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Organism;

/**
 * Kapselt alle zur Laufzeit einer Instruktion benötigten Informationen und Abhängigkeiten.
 * Dieses Objekt wird von der VirtualMachine erstellt und an die ausführenden
 * Einheiten übergeben, um globale Zugriffe zu vermeiden.
 */
public class ExecutionContext {

    private final Organism organism;
    private final Environment environment;
    private final ProgramArtifact artifact;
    private final boolean isPerformanceMode;

    public ExecutionContext(Organism organism, Environment environment, ProgramArtifact artifact, boolean isPerformanceMode) {
        this.organism = organism;
        this.environment = environment;
        this.artifact = artifact;
        this.isPerformanceMode = isPerformanceMode;
    }

    public Organism getOrganism() {
        return organism;
    }

    public Environment getWorld() {
        return environment;
    }

    public ProgramArtifact getArtifact() {
        return artifact;
    }

    public boolean isPerformanceMode() {
        return isPerformanceMode;
    }
}
