// src/main/java/org/evochora/organism/actions/IfAction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.world.World;

public class IfAction extends Action {
    private final int reg1;
    private final int reg2;
    private final int type;

    public IfAction(Organism organism, int reg1, int reg2, int type) {
        super(organism);
        this.reg1 = reg1;
        this.reg2 = reg2;
        this.type = type;
    }

    public static Action plan(Organism organism, World world) {
        int r1 = organism.fetchArgument(world);
        int r2 = organism.fetchArgument(world);
        int opType = world.getSymbol(organism.getIp()).toInt();
        return new IfAction(organism, r1, r2, opType);
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