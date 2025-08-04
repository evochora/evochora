// src/main/java/org/evochora/organism/TurnAction.java
package org.evochora.organism;

import org.evochora.Simulation;
import org.evochora.world.World;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class TurnAction extends Action {
    private final int reg;

    public TurnAction(Organism o, int r) {
        super(o);
        this.reg = r;
    }

    public static List<Integer> assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 1) throw new IllegalArgumentException("TURN erwartet 1 Argument: %REG_VEC");
        return List.of(registerMap.get(args[0].toUpperCase()));
    }

    public static Action plan(Organism organism, World world) {
        int[] tempIp = Arrays.copyOf(organism.getIp(), organism.getIp().length); // Kopie, um das IP nicht zu verändern
        return new TurnAction(organism, organism.fetchArgument(tempIp, world));
    }

    @Override
    public void execute(Simulation simulation) {
        Object newDv = organism.getDr(reg);
        // Überprüfe, ob der Wert ein Array ist und die korrekte Dimensionalität hat
        if (newDv instanceof int[] v && v.length == organism.getDv().length) {
            // Einheitsvektorprüfung ist in organism.isUnitVector() eingebaut
            if (!organism.isUnitVector(v)) {
                return; // instructionFailed wird bereits in isUnitVector gesetzt
            }
            organism.setDv(v);
        } else {
            // GEÄNDERT: instructionFailed mit spezifischem Grund aufrufen
            organism.instructionFailed("TURN: Invalid DR type for new DV (Reg " + reg + "). Expected int[" + organism.getDv().length + "], found " + (newDv != null ? newDv.getClass().getSimpleName() : "null") + (newDv instanceof int[] ? " with length " + ((int[])newDv).length : "") + ".");
        }
    }
}