// src/main/java/org/evochora/organism/PokeAction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class PokeAction extends Action implements IWorldModifyingAction { // GEÄNDERT: Implementiert IWorldModifyingAction
    private final int srcReg;
    private final int vecReg;
    private final int[] targetCoordinate; // Speichert die Zielkoordinate

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
        int[] tempIp = Arrays.copyOf(organism.getIp(), organism.getIp().length); // Kopie, um das IP nicht zu verändern
        int src = organism.fetchArgument(tempIp, world);
        int vec = organism.fetchArgument(tempIp, world);

        Object v = organism.getDr(vec);
        if (v instanceof int[] vi) {
            // FINALE PRÜFUNG: Erzwinge strikte Lokalität.
            if (!organism.isUnitVector(vi)) { // organism.isUnitVector setzt bereits den Fehlergrund
                // Der Fehlergrund wird im Organismus gesetzt. Hier nur eine NopAction zurückgeben.
                return new NopAction(organism);
            }
            int[] target = organism.getTargetCoordinate(organism.getDp(), vi, world);
            return new PokeAction(organism, src, vec, target);
        }
        // GEÄNDERT: instructionFailed mit spezifischem Grund aufrufen
        organism.instructionFailed("POKE: Invalid DR type for vector (Reg " + vec + "). Expected int[], found " + (v != null ? v.getClass().getSimpleName() : "null") + ".");
        return new NopAction(organism); // Rückgabe einer NopAction bei Planungsfehler
    }

    @Override
    public void execute(Simulation simulation) {
        World world = simulation.getWorld();
        Object vec = organism.getDr(vecReg); // Erneuter Zugriff auf DR, falls sich der Wert geändert hat
        Object val = organism.getDr(srcReg); // Erneuter Zugriff auf DR, falls sich der Wert geändert hat

        // Erneute Überprüfung der Typen und des Einheitsvektors zur Laufzeit,
        // falls sich die Registerinhalte seit der Planungsphase geändert haben sollten.
        if (!(vec instanceof int[] v) || !organism.isUnitVector(v)) {
            // instructionFailed wurde bereits in plan gesetzt, oder durch isUnitVector(). Hier nur Return.
            return;
        }

        if (val instanceof Integer i) {
            // NEU: Nur ausführen, wenn die Aktion den Konflikt gewonnen hat oder es keinen Konflikt gab.
            if (this.getConflictStatus() == ConflictResolutionStatus.WON_EXECUTION || this.getConflictStatus() == ConflictResolutionStatus.NOT_APPLICABLE) {
                if (world.getSymbol(targetCoordinate).isEmpty()) {
                    world.setSymbol(Symbol.fromInt(i), targetCoordinate);
                } else {
                    // GEÄNDERT: instructionFailed mit spezifischem Grund aufrufen
                    organism.instructionFailed("POKE: Target cell is not empty at " + Arrays.toString(targetCoordinate) + ". Current content: " + world.getSymbol(targetCoordinate).toInt() + ".");
                    // NEU: Setze Konfliktstatus, da Zelle bereits belegt war
                    this.setConflictStatus(ConflictResolutionStatus.LOST_TARGET_OCCUPIED);
                }
            } else {
                // Wenn die Aktion den Konflikt verloren hat, tut sie nichts, aber setzt instructionFailed nicht
                // Das Logging wird dies über den conflictStatus festhalten
            }
        } else {
            // GEÄNDERT: instructionFailed mit spezifischem Grund aufrufen
            organism.instructionFailed("POKE: Invalid DR type for source value (Reg " + srcReg + "). Expected Integer, found " + (val != null ? val.getClass().getSimpleName() : "null") + ".");
        }
    }

    @Override
    public List<int[]> getTargetCoordinates() {
        return List.of(targetCoordinate);
    }

    // Getter und Setter für conflictStatus sind in Action.java implementiert
}