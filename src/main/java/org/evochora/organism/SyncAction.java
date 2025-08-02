// src/main/java/org/evochora/organism/actions/SyncAction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class SyncAction extends Action {
    public SyncAction(Organism o) { super(o); }

    public static List<Integer> assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        return Collections.emptyList();
    }

    public static Action plan(Organism organism, World world) {
        return new SyncAction(organism);
    }

    @Override
    public void execute(Simulation simulation) {
        organism.setDp(organism.getIp());
    }
}