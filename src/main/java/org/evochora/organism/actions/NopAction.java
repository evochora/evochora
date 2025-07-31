// src/main/java/org/evochora/organism/actions/NopAction.java
package org.evochora.organism.actions;

import org.evochora.Organism;

public class NopAction implements Action {
    private final Organism organism;

    public NopAction(Organism organism) {
        this.organism = organism;
    }

    @Override
    public Organism getOrganism() {
        return this.organism;
    }
}