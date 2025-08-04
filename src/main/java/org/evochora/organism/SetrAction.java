// src/main/java/org/evochora/organism/SetrAction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;
import java.util.List;
import java.util.Map;

public class SetrAction extends Action {
    private final int destReg;
    private final int srcReg;
    public SetrAction(Organism o, int d, int s) { super(o); this.destReg = d; this.srcReg = s; }

    public static Action plan(Organism organism, World world) {
        int[] tempIp = organism.getIp(); // Kopie, um das IP nicht zu verändern
        int dest = organism.fetchArgument(tempIp, world);
        int src = organism.fetchArgument(tempIp, world);
        return new SetrAction(organism, dest, src);
    }
    public static List<Integer> assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("SETR erwartet 2 Argumente: %REG_DEST %REG_SRC");
        return List.of(registerMap.get(args[0].toUpperCase()), registerMap.get(args[1].toUpperCase()));
    }
    @Override
    public void execute(Simulation simulation) {
        Object value = organism.getDr(srcReg); // getDr setzt bereits instructionFailed und den Grund, falls srcReg ungültig
        if (value == null || !organism.setDr(destReg, value)) {
            // GEÄNDERT: instructionFailed mit spezifischem Grund aufrufen (falls setDr den Fehler nicht detailliert genug setzt)
            organism.instructionFailed("SETR: Failed to set value from DR " + srcReg + " to DR " + destReg + ". Possible invalid register index or null source value.");
        }
    }
}