// src/main/java/org/evochora/organism/PeekAction.java
package org.evochora.organism;

import org.evochora.Config;
import org.evochora.Simulation;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class PeekAction extends Action implements IWorldModifyingAction { // GEÄNDERT: Implementiert IWorldModifyingAction
    private final int targetReg, vecReg;
    private final int[] targetCoordinate; // HINZUGEFÜGT: Speichert die Zielkoordinate

    public PeekAction(Organism o, int tr, int vr, int[] tc) { // GEÄNDERT: tc Parameter hinzugefügt
        super(o);
        this.targetReg = tr;
        this.vecReg = vr;
        this.targetCoordinate = tc; // HINZUGEFÜGT
    }

    public static List<Integer> assemble(String[] args, Map<String, Integer> registerMap, Map<String, Integer> labelMap) {
        if (args.length != 2) throw new IllegalArgumentException("PEEK erwartet 2 Argumente: %REG_TARGET %REG_VEC");
        return List.of(registerMap.get(args[0].toUpperCase()), registerMap.get(args[1].toUpperCase()));
    }

    public static Action plan(Organism organism, World world) {
        int[] tempIp = Arrays.copyOf(organism.getIp(), organism.getIp().length); // Kopie, um das IP nicht zu verändern
        int targetReg = organism.fetchArgument(tempIp, world);
        int vecReg = organism.fetchArgument(tempIp, world);

        Object vec = organism.getDr(vecReg);
        if (vec instanceof int[] v) {
            // Prüfe auf Einheitsvektor bereits in plan
            if (!organism.isUnitVector(v)) { // organism.isUnitVector setzt bereits den Fehlergrund
                // Der Fehlergrund wird im Organismus gesetzt. Hier nur eine NopAction zurückgeben.
                return new NopAction(organism);
            }
            int[] target = organism.getTargetCoordinate(organism.getDp(), v, world);
            return new PeekAction(organism, targetReg, vecReg, target); // HINZUGEFÜGT: target übergeben
        } else {
            // GEÄNDERT: instructionFailed mit spezifischem Grund aufrufen
            organism.instructionFailed("PEEK: Invalid DR type for vector (Reg " + vecReg + "). Expected int[], found " + (vec != null ? vec.getClass().getSimpleName() : "null") + ".");
            return new NopAction(organism); // Rückgabe einer NopAction bei Planungsfehler
        }
    }

    @Override
    public void execute(Simulation simulation) {
        World world = simulation.getWorld();
        // Das vecReg wurde bereits in plan ausgewertet, wir nutzen die gespeicherte targetCoordinate
        // Erneute Prüfung auf Vektor-Typ und Einheitsvektor ist nicht zwingend nötig, da schon in plan geprüft.
        // Allerdings könnte sich das DR in der Zwischenzeit geändert haben. Für strengere Typsicherheit:
        Object vec = organism.getDr(vecReg);
        if (!(vec instanceof int[] v) || !organism.isUnitVector(v)) {
            // instructionFailed wurde bereits in plan gesetzt. Hier nur Return.
            return;
        }

        Symbol s = world.getSymbol(targetCoordinate);

        // NEU: Nur ausführen, wenn die Aktion den Konflikt gewonnen hat oder es keinen Konflikt gab.
        if (this.getConflictStatus() == ConflictResolutionStatus.WON_EXECUTION || this.getConflictStatus() == ConflictResolutionStatus.NOT_APPLICABLE) {
            if (s.isEmpty()) {
                // GEÄNDERT: instructionFailed mit spezifischem Grund aufrufen, da PEEK leere Zelle nicht beeinflussen kann
                organism.instructionFailed("PEEK: Target cell is empty at " + Arrays.toString(targetCoordinate) + ". Cannot extract value/energy.");
                // NEU: Setze Konfliktstatus, da PEEK nichts finden konnte
                this.setConflictStatus(ConflictResolutionStatus.LOST_TARGET_EMPTY);
                return;
            }

            if (s.type() == Config.TYPE_ENERGY) {
                int energyToTake = Math.min(s.value(), Config.MAX_ORGANISM_ENERGY - organism.getEr());
                organism.addEr(energyToTake);
                world.setSymbol(new Symbol(Config.TYPE_ENERGY, s.value() - energyToTake), targetCoordinate);
            } else {
                organism.setDr(targetReg, s.toInt()); // Speichert den vollen Wert inkl. Typ-Header in DR
                // GEÄNDERT: PEEK verändert die Zelle, indem es den Inhalt löscht/ersetzt
                // Setze die Zelle auf leere Daten, damit sie nicht doppelt gepiekst werden kann
                world.setSymbol(new Symbol(Config.TYPE_DATA, 0), targetCoordinate);
            }
        } else {
            // Wenn die Aktion den Konflikt verloren hat, tut sie nichts, aber setzt instructionFailed nicht
            // Das Logging wird dies über den conflictStatus festhalten
        }
    }

    @Override
    public List<int[]> getTargetCoordinates() {
        return List.of(targetCoordinate);
    }

    // Getter und Setter für conflictStatus sind in Action.java implementiert
}