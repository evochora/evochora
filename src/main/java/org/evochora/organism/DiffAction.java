// src/main/java/org/evochora/organism/DiffAction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class DiffAction extends Action {
    private final int reg;

    public DiffAction(Organism o, int r) {
        super(o);
        this.reg = r;
    }

    public static List<Integer> assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) throw new IllegalArgumentException("DIFF erwartet 1 Argument: %REG_TARGET");
        return List.of(registerMap.get(args[0].toUpperCase()));
    }

    public static Action plan(Organism organism, World world) {
        int[] tempIp = Arrays.copyOf(organism.getIp(), organism.getIp().length); // Kopie, um das IP nicht zu verändern
        return new DiffAction(organism, organism.fetchArgument(tempIp, world));
    }

    @Override
    public void execute(Simulation simulation) {
        // Die Position am Anfang der Ausführung, bevor der IP vorgerückt wurde
        int[] lastIp = organism.getIpBeforeFetch();

        // Überprüfen, ob lastIp gültig ist (sollte es immer sein, aber zur Sicherheit)
        if (lastIp == null || lastIp.length != organism.getIp().length) {
            // GEÄNDERT: instructionFailed mit spezifischem Grund aufrufen
            organism.instructionFailed("DIFF: Cannot determine previous IP for difference calculation.");
            return;
        }

        int[] delta = new int[organism.getIp().length];
        for (int i = 0; i < organism.getIp().length; i++) {
            delta[i] = organism.getIp()[i] - lastIp[i];
        }

        if (!organism.setDr(reg, delta)) {
            // setDr setzt bereits instructionFailed und den Grund, aber hier könnte man noch spezifischer sein
            // GEÄNDERT: instructionFailed mit spezifischem Grund aufrufen (falls setDr den Fehler nicht detailliert genug setzt)
            organism.instructionFailed("DIFF: Failed to set result to DR " + reg + ". Possible invalid register index.");
        }
    }
}