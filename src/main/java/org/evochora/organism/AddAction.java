// src/main/java/org/evochora/organism/AddAction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class AddAction extends Action {
    private final int reg1, reg2;
    public AddAction(Organism o, int r1, int r2) { super(o); this.reg1 = r1; this.reg2 = r2; }

    public static Action plan(Organism organism, World world) {
        int[] tempIp = organism.getIp();
        return new AddAction(organism, organism.fetchArgument(tempIp, world), organism.fetchArgument(tempIp, world));
    }
    public static List<Integer> assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("ADD erwartet 2 Argumente: %REG1 %REG2");
        return List.of(registerMap.get(args[0].toUpperCase()), registerMap.get(args[1].toUpperCase()));
    }
    @Override
    public void execute(Simulation simulation) {
        Object val1 = organism.getDr(reg1); Object val2 = organism.getDr(reg2);
        if (val1 instanceof Integer v1 && val2 instanceof Integer v2) organism.setDr(reg1, v1 + v2);
        else if (val1 instanceof int[] v1 && val2 instanceof int[] v2 && v1.length == v2.length) {
            int[] result = new int[v1.length];
            for (int i = 0; i < v1.length; i++) result[i] = v1[i] + v2[i];
            organism.setDr(reg1, result);
        } else organism.instructionFailed();
    }
}