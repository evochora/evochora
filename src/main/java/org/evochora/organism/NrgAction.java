// src/main/java/org/evochora/organism/NrgAction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class NrgAction extends Action {
    private final int targetReg;
    public NrgAction(Organism o, int tr) { super(o); this.targetReg = tr; }

    public static List<Integer> assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) throw new IllegalArgumentException("NRG erwartet 1 Argument: %REG_TARGET");
        return List.of(registerMap.get(args[0].toUpperCase()));
    }

    public static Action plan(Organism organism, World world) {
        int[] tempIp = Arrays.copyOf(organism.getIp(), organism.getIp().length); // Kopie, um das IP nicht zu verändern
        return new NrgAction(organism, organism.fetchArgument(tempIp, world));
    }

    @Override
    public void execute(Simulation simulation) {
        if (!organism.setDr(targetReg, organism.getEr())) {
            // GEÄNDERT: instructionFailed mit spezifischem Grund aufrufen
            organism.instructionFailed("NRG: Failed to set energy to DR " + targetReg + ". Possible invalid register index.");
        }
    }
}