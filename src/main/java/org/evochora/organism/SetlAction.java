// src/main/java/org/evochora/organism/actions/SetlAction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;
import java.util.List;
import java.util.Map;

public class SetlAction extends Action {
    private final int registerIndex;
    private final int literalValue;
    public SetlAction(Organism o, int r, int v) { super(o); this.registerIndex = r; this.literalValue = v; }

    public static Action plan(Organism organism, World world) {
        return new SetlAction(organism, organism.fetchArgument(world), organism.fetchArgument(world));
    }
    public static List<Integer> assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("SETL erwartet 2 Argumente: %REG WERT");
        int regId = registerMap.get(args[0].toUpperCase());
        int literal = Integer.parseInt(args[1]);
        return List.of(regId, literal);
    }
    @Override
    public void execute(Simulation simulation) {
        if (!organism.setDr(registerIndex, literalValue)) {
            organism.instructionFailed();
        }
    }
}