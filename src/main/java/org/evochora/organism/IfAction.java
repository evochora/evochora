// src/main/java/org/evochora/organism/IfAction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.organism.Action;
import org.evochora.organism.Organism;
import org.evochora.Simulation;
import org.evochora.world.World;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class IfAction extends Action {
    private final int reg1;
    private final int reg2;
    private final int type;

    public IfAction(Organism o, int r1, int r2, int t) { super(o); this.reg1 = r1; this.reg2 = r2; this.type = t; }

    public static List<Integer> assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("IF-Befehle erwarten 2 Argumente: %REG1 %REG2");
        return List.of(registerMap.get(args[0].toUpperCase()), registerMap.get(args[1].toUpperCase()));
    }

    public static Action plan(Organism organism, World world) {
        int opType = world.getSymbol(organism.getIp()).toInt();
        int[] tempIp = Arrays.copyOf(organism.getIp(), organism.getIp().length);
        return new IfAction(organism, organism.fetchArgument(tempIp, world), organism.fetchArgument(tempIp, world), opType);
    }

    @Override
    public void execute(Simulation simulation) {
        Object val1 = organism.getDr(reg1);
        Object val2 = organism.getDr(reg2);
        if (val1 instanceof Integer v1 && val2 instanceof Integer v2) {
            boolean conditionMet = false;
            if (type == Config.OP_IF && v1.equals(v2)) conditionMet = true;
            if (type == Config.OP_IFLT && v1 < v2) conditionMet = true;
            if (type == Config.OP_IFGT && v1 > v2) conditionMet = true;
            if (conditionMet) {
                organism.skipNextInstruction(simulation.getWorld());
            }
        } else {
            organism.instructionFailed();
        }
    }
}