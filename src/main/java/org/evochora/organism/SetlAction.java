// src/main/java/org/evochora/organism/SetlAction.java
package org.evochora.organism;

import org.evochora.organism.Action;
import org.evochora.organism.Organism;
import org.evochora.Simulation;
import org.evochora.world.World;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SetlAction extends Action {
    private final int registerIndex;
    private final int literalValue;

    public SetlAction(Organism o, int r, int v) { super(o); this.registerIndex = r; this.literalValue = v; }

    public static List<Integer> assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("SETL erwartet 2 Argumente: %REG WERT");
        int regId = registerMap.get(args[0].toUpperCase());
        int literal = Integer.parseInt(args[1]);
        return List.of(regId, literal);
    }

    public static Action plan(Organism organism, World world) {
        int[] tempIp = Arrays.copyOf(organism.getIp(), organism.getIp().length);
        // Das erste Argument (Register-Index) ist immer positiv.
        int regIdx = organism.fetchArgument(tempIp, world);
        // Das zweite Argument (der Literal-Wert) kann negativ sein.
        int literal = organism.fetchSignedArgument(tempIp, world);
        return new SetlAction(organism, regIdx, literal);
    }

    @Override
    public void execute(Simulation simulation) {
        if (!organism.setDr(registerIndex, literalValue)) {
            organism.instructionFailed();
        }
    }
}