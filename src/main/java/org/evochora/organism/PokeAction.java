// src/main/java/org/evochora/organism/PokeAction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.organism.Action;
import org.evochora.organism.Organism;
import org.evochora.Simulation;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class PokeAction extends Action {
    private final int srcReg;
    private final int vecReg;
    private final int[] targetCoordinate;

    public PokeAction(Organism organism, int srcReg, int vecReg, int[] targetCoordinate) {
        super(organism);
        this.srcReg = srcReg;
        this.vecReg = vecReg;
        this.targetCoordinate = targetCoordinate;
    }

    public static List<Integer> assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("POKE erwartet 2 Argumente: %REG_SRC %REG_VEC");
        return List.of(registerMap.get(args[0].toUpperCase()), registerMap.get(args[1].toUpperCase()));
    }

    public static Action plan(Organism organism, World world) {
        int[] tempIp = Arrays.copyOf(organism.getIp(), organism.getIp().length);
        int src = organism.fetchArgument(tempIp, world);
        int vec = organism.fetchArgument(tempIp, world);
        Object v = organism.getDr(vec);
        if (v instanceof int[] vi) {
            // NEUE PRÜFUNG: Ist es ein gültiger Einheitsvektor?
            if (!organism.isUnitVector(vi)) { // Prüfung hier hinzufügen
                organism.instructionFailed();
                return new NopAction(organism); // oder eine spezifische Fehleraktion
            }
            int[] target = organism.getTargetCoordinate(organism.getDp(), vi, world);
            return new PokeAction(organism, src, vec, target);
        }
        organism.instructionFailed();
        return new NopAction(organism);
    }

    @Override
    public void execute(Simulation simulation) {
        World world = simulation.getWorld();
        Object vec = organism.getDr(vecReg);
        Object val = organism.getDr(srcReg);

        if (vec instanceof int[] v && val instanceof Integer i) {
            // Die isUnitVector-Prüfung kann hier bleiben, um Redundanz zu gewährleisten,
            // oder entfernt werden, wenn wir davon ausgehen, dass plan diese bereits behandelt hat.
            // Ich würde sie hier behalten, da getDr(vecReg) in execute erneut aufgerufen wird
            // und sich der Wert im Register zwischen plan und execute ändern könnte,
            // auch wenn das unwahrscheinlich ist.
            if (!organism.isUnitVector(v)) { // Beibehalten
                organism.instructionFailed();
                return;
            }

            if (world.getSymbol(targetCoordinate).isEmpty()) {
                world.setSymbol(Symbol.fromInt(i), targetCoordinate);
            } else {
                organism.instructionFailed(); // Zielzelle nicht leer
            }
        } else {
            organism.instructionFailed();
        }
    }

    public int[] getTargetCoordinate() {
        return targetCoordinate;
    }
}