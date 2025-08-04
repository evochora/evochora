// src/main/java/org/evochora/organism/NandAction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class NandAction extends Action {
    private final int reg1;
    private final int reg2;

    public NandAction(Organism o, int r1, int r2) { super(o); this.reg1 = r1; this.reg2 = r2; }

    public static List<Integer> assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("NAND erwartet 2 Argumente: %REG1 %REG2");
        return List.of(registerMap.get(args[0].toUpperCase()), registerMap.get(args[1].toUpperCase()));
    }

    public static Action plan(Organism organism, World world) {
        int[] tempIp = Arrays.copyOf(organism.getIp(), organism.getIp().length); // Kopie, um das IP nicht zu verändern
        return new NandAction(organism, organism.fetchArgument(tempIp, world), organism.fetchArgument(tempIp, world));
    }

    @Override
    public void execute(Simulation simulation) {
        Object val1 = organism.getDr(reg1);
        Object val2 = organism.getDr(reg2);
        if (val1 instanceof Integer v1 && val2 instanceof Integer v2) {
            organism.setDr(reg1, ~(v1 & v2));
        } else {
            // GEÄNDERT: instructionFailed mit spezifischem Grund aufrufen
            organism.instructionFailed("NAND: Invalid DR types. Reg " + reg1 + " (" + (val1 != null ? val1.getClass().getSimpleName() : "null") + ") and Reg " + reg2 + " (" + (val2 != null ? val2.getClass().getSimpleName() : "null") + ") must be Integer.");
        }
    }
}