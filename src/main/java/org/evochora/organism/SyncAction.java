// src/main/java/org/evochora/organism/actions/SyncAction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;

public class SyncAction extends Action {
    public SyncAction(Organism organism) {
        super(organism);
    }

    public static Action plan(Organism organism, World world) {
        return new SyncAction(organism);
    }

    @Override
    public void execute(Simulation simulation) {
        // Ausnahme: SYNC darf den DP auf eine besetzte Zelle (den IP) setzen.
        organism.setDp(organism.getIp());
    }
}