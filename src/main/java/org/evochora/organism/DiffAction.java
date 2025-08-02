// src/main/java/org/evochora/organism/actions/DiffAction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;
import java.util.List;
import java.util.Map;

public class DiffAction extends Action {
    private final int reg;
    public DiffAction(Organism o, int r) { super(o); this.reg = r; }

    public static List<Integer> assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) throw new IllegalArgumentException("DIFF erwartet 1 Argument: %REG_TARGET");
        return List.of(registerMap.get(args[0].toUpperCase()));
    }

    public static Action plan(Organism organism, World world) {
        return new DiffAction(organism, organism.fetchArgument(world));
    }

    @Override
    public void execute(Simulation simulation) {
        int[] ip = organism.getIpBeforeFetch();
        int[] lastIp = organism.getLastIp();
        int[] delta = new int[ip.length];
        for (int i = 0; i < ip.length; i++) {
            delta[i] = ip[i] - lastIp[i];
        }
        if (!organism.setDr(reg, delta)) {
            organism.instructionFailed();
        }
    }
}