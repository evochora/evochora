// src/main/java/org/evochora/organism/actions/Action.java
package org.evochora.organism.actions;

import org.evochora.Organism;

public interface Action {
    /**
     * Gibt den Organismus zurück, der diese Aktion geplant hat.
     */
    Organism getOrganism();
}