// src/main/java/org/evochora/organism/SeekAction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SeekAction extends Action {
    private final int reg;
    public SeekAction(Organism o, int r) { super(o); this.reg = r; }

    public static List<Integer> assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) throw new IllegalArgumentException("SEEK erwartet 1 Argument: %REG_VEC");
        return List.of(registerMap.get(args[0].toUpperCase()));
    }

    public static Action plan(Organism organism, World world) {
        int[] tempIp = Arrays.copyOf(organism.getIp(), organism.getIp().length); // Kopie, um das IP nicht zu verändern
        return new SeekAction(organism, organism.fetchArgument(tempIp, world));
    }

    @Override
    public void execute(Simulation simulation) {
        Object vec = organism.getDr(reg);
        if (vec instanceof int[] v) {
            if (!organism.isUnitVector(v)) { // organism.isUnitVector setzt bereits den Fehlergrund
                return; // Fehler wurde bereits in isUnitVector gesetzt
            }

            int[] targetDp = organism.getTargetCoordinate(organism.getDp(), v, simulation.getWorld());
            if (simulation.getWorld().getSymbol(targetDp).isEmpty()) {
                organism.setDp(targetDp);
            } else {
                // GEÄNDERT: instructionFailed mit spezifischem Grund aufrufen
                organism.instructionFailed("SEEK: Target DP cell is not empty at " + Arrays.toString(targetDp) + ". Current content: " + simulation.getWorld().getSymbol(targetDp).toInt() + ".");
            }
        } else {
            // GEÄNDERT: instructionFailed mit spezifischem Grund aufrufen
            organism.instructionFailed("SEEK: Invalid DR type for vector (Reg " + reg + "). Expected int[], found " + (vec != null ? vec.getClass().getSimpleName() : "null") + ".");
        }
    }
}