// src/main/java/org/evochora/organism/actions/NopAction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class NopAction extends Action {
    public NopAction(Organism organism) { super(organism); }
    public static Action plan(Organism organism, World world) { return new NopAction(organism); }
    public static List<Integer> assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        return Collections.emptyList();
    }
    @Override
    public void execute(Simulation simulation) { /* Tut nichts */ }
}