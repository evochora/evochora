// src/main/java/org/evochora/organism/actions/SetvAction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.world.World;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SetvAction extends Action {
    private final int destReg;
    private final int[] values;

    public SetvAction(Organism organism, int destReg, int[] values) {
        super(organism);
        this.destReg = destReg;
        this.values = values;
    }

    public static List<Integer> assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("SETV erwartet 2 Argumente: %REG WERT1|WERT2|...");
        List<Integer> machineCode = new ArrayList<>();
        machineCode.add(registerMap.get(args[0].toUpperCase()));
        String[] vectorComponents = args[1].split("\\|");
        if (vectorComponents.length != Config.WORLD_DIMENSIONS) throw new IllegalArgumentException("SETV: Falsche Vektor-Dimensionalität.");
        for (String component : vectorComponents) {
            machineCode.add(Integer.parseInt(component.strip()));
        }
        return machineCode;
    }

    public static Action plan(Organism organism, World world) {
        int reg = organism.fetchArgument(world);
        int[] vals = new int[Config.WORLD_DIMENSIONS];
        for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
            vals[i] = organism.fetchArgument(world);
        }
        return new SetvAction(organism, reg, vals);
    }

    @Override
    public void execute(Simulation simulation) {
        if (!organism.setDr(destReg, values)) {
            organism.instructionFailed();
        }
    }
}