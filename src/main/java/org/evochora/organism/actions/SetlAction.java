// src/main/java/org/evochora/organism/actions/SetlAction.java
package org.evochora.organism.actions;

import org.evochora.Organism;

public class SetlAction implements Action {
    private final Organism organism;
    private final int registerIndex;
    private final int literalValue;

    public SetlAction(Organism organism, int registerIndex, int literalValue) {
        this.organism = organism;
        this.registerIndex = registerIndex;
        this.literalValue = literalValue;
    }

    @Override
    public Organism getOrganism() {
        return this.organism;
    }

    public int getRegisterIndex() {
        return registerIndex;
    }

    public int getLiteralValue() {
        return literalValue;
    }
}