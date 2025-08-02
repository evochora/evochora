// src/main/java/org/evochora/organism/actions/SetrAction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;
import java.util.List;
import java.util.Map;

public class SetrAction extends Action {
    private final int destReg;
    private final int srcReg;

    public SetrAction(Organism organism, int destReg, int srcReg) {
        super(organism);
        this.destReg = destReg;
        this.srcReg = srcReg;
    }

    public static List<Integer> assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("SETR erwartet 2 Argumente: %REG_DEST %REG_SRC");
        int destId = registerMap.get(args[0].toUpperCase());
        int srcId = registerMap.get(args[1].toUpperCase());
        return List.of(destId, srcId);
    }

    public static Action plan(Organism organism, World world) {
        int dest = organism.fetchArgument(world);
        int src = organism.fetchArgument(world);
        return new SetrAction(organism, dest, src);
    }

    @Override
    public void execute(Simulation simulation) {
        Object value = organism.getDr(srcReg);
        if (value == null || !organism.setDr(destReg, value)) {
            organism.instructionFailed();
        }
    }
}