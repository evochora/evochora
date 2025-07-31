// src/main/java/org/evochora/organism/actions/NopAction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;

public class NopAction extends Action {
    public NopAction(Organism organism) {
        super(organism);
    }

    public static Action plan(Organism organism, World world) {
        return new NopAction(organism);
    }

    @Override
    public void execute(Simulation simulation) {
        // Tut nichts. Der IP wird am Ende des Ticks automatisch weitergerückt.
    }
}