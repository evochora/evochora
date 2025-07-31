// src/main/java/org/evochora/organism/actions/AddAction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;

public class AddAction extends Action {
    private final int reg1;
    private final int reg2;

    public AddAction(Organism organism, int reg1, int reg2) {
        super(organism);
        this.reg1 = reg1;
        this.reg2 = reg2;
    }

    public static Action plan(Organism organism, World world) {
        return new AddAction(organism, organism.fetchArgument(world), organism.fetchArgument(world));
    }

    @Override
    public void execute(Simulation simulation) {
        Object val1 = organism.getDr(reg1);
        Object val2 = organism.getDr(reg2);
        if (val1 instanceof Integer v1 && val2 instanceof Integer v2) {
            organism.setDr(reg1, v1 + v2);
        } else if (val1 instanceof int[] v1 && val2 instanceof int[] v2 && v1.length == v2.length) {
            int[] result = new int[v1.length];
            for (int i = 0; i < v1.length; i++) result[i] = v1[i] + v2[i];
            organism.setDr(reg1, result);
        } else {
            organism.instructionFailed();
        }
    }
}