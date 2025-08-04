// src/main/java/org/evochora/organism/SetvAction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.world.World;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SetvAction extends Action {
    private final int destReg;
    private final int[] values;

    public SetvAction(Organism o, int d, int[] v) { super(o); this.destReg = d; this.values = v; }

    public static List<Integer> assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("SETV erwartet 2 Argumente: %REG WERT1|WERT2|...");
        List<Integer> machineCode = new ArrayList<>();
        machineCode.add(registerMap.get(args[0].toUpperCase()));
        String[] vectorComponents = args[1].split("\\|");
        if (vectorComponents.length != Config.WORLD_DIMENSIONS) throw new IllegalArgumentException("SETV: Falsche Vektor-Dimensionalität. Erwartet " + Config.WORLD_DIMENSIONS + ", gefunden " + vectorComponents.length + ".");
        for (String component : vectorComponents) {
            machineCode.add(Integer.parseInt(component.strip()));
        }
        return machineCode;
    }

    public static Action plan(Organism organism, World world) {
        int[] tempIp = Arrays.copyOf(organism.getIp(), organism.getIp().length); // Kopie, um das IP nicht zu verändern
        int reg = organism.fetchArgument(tempIp, world);
        int[] vals = new int[Config.WORLD_DIMENSIONS];
        for (int i = 0; i < Config.WORLD_DIMENSIONS; i++) {
            vals[i] = organism.fetchSignedArgument(tempIp, world);
        }
        return new SetvAction(organism, reg, vals);
    }

    @Override
    public void execute(Simulation simulation) {
        if (!organism.setDr(destReg, values)) {
            // GEÄNDERT: instructionFailed mit spezifischem Grund aufrufen
            organism.instructionFailed("SETV: Failed to set vector " + Arrays.toString(values) + " to DR " + destReg + ". Possible invalid register index.");
        }
    }
}