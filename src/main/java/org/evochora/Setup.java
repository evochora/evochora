// src/main/java/org/evochora/Setup.java
package org.evochora;

import org.evochora.organism.Organism;
import org.evochora.organism.prototypes.AllOpcodesTester;
import org.evochora.organism.prototypes.DpMovementTester;
import org.evochora.organism.prototypes.LShapedOrganism;
import org.evochora.organism.prototypes.SetlTester;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class Setup {

    /**
     * Die zentrale Methode, um die Welt mit den gewünschten Organismen zu bevölkern.
     * Kommentiere die Aufrufe aus oder ein, um verschiedene Szenarien zu testen.
     * @param simulation die Simulationsinstanz
     */
    public static void run(Simulation simulation) {
        // Test 1: SETL
        SetlTester setlTestProgram = new SetlTester();
        placeProgram(simulation, setlTestProgram, new int[]{10, 5});

        // Test 2: DP Movement
        DpMovementTester dpTestProgram = new DpMovementTester();
        placeProgram(simulation, dpTestProgram, new int[]{10, 10});

        // Test 3: All other Opcodes
        AllOpcodesTester allOpcodesProgram = new AllOpcodesTester();
        placeProgram(simulation, allOpcodesProgram, new int[]{10, 15});

        // Test 4: L-förmiger Organismus
        LShapedOrganism lShapedProgram = new LShapedOrganism();
        placeProgram(simulation, lShapedProgram, new int[]{10, 20});
    }

    /**
     * Eine private Hilfsmethode, die den kompletten Setup-Prozess für ein assembliertes Programm durchführt.
     */
    private static void placeProgram(Simulation simulation, AssemblyProgram program, int[] startPos) {
        // 1. Assembliere den Code, um das Layout und die zusätzlichen Welt-Objekte zu erhalten.
        Map<int[], Integer> layout = program.assemble();
        Map<int[], Symbol> worldObjects = program.getInitialWorldObjects();

        // 2. Platziere den Organismus physisch in der Welt.
        Organism org = placeOrganismWithLayout(simulation, startPos, layout, worldObjects);

        // 3. Verknüpfe die laufende Instanz mit den Metadaten des assemblierten Programms.
        program.assignOrganism(org);
    }

    /**
     * Eine generische Hilfsmethode, um einen Organismus mit einem räumlichen Layout und zusätzlichen
     * Objekten (definiert durch .PLACE) in der Welt zu platzieren.
     */
    private static Organism placeOrganismWithLayout(
            Simulation simulation,
            int[] startPos,
            Map<int[], Integer> layout,
            Map<int[], Symbol> worldObjects)
    {
        World world = simulation.getWorld();

        // 1. Platziere den Programmcode gemäß dem vom Assembler berechneten Layout.
        for (Map.Entry<int[], Integer> entry : layout.entrySet()) {
            int[] relativePos = entry.getKey();
            int value = entry.getValue();

            int[] absolutePos = new int[startPos.length];
            for (int i = 0; i < startPos.length; i++) {
                absolutePos[i] = startPos[i] + relativePos[i];
            }

            // KORRIGIERTE LOGIK:
            // Wir verlassen uns vollständig auf Symbol.fromInt, um den korrekten Typ
            // aus dem 32-Bit-Integer zu extrahieren.
            world.setSymbol(Symbol.fromInt(value), absolutePos);
        }

        // 2. Platziere die zusätzlichen Welt-Objekte, die durch .PLACE definiert wurden.
        for (Map.Entry<int[], Symbol> entry : worldObjects.entrySet()) {
            int[] relativePos = entry.getKey();
            Symbol symbol = entry.getValue();
            int[] absolutePos = new int[startPos.length];
            for (int i = 0; i < startPos.length; i++) {
                absolutePos[i] = startPos[i] + relativePos[i];
            }
            world.setSymbol(symbol, absolutePos);
        }

        // 3. Erstelle den Organismus am Startpunkt des Codes.
        Organism org = Organism.create(simulation, startPos, Config.INITIAL_ORGANISM_ENERGY);
        simulation.addOrganism(org);
        return org;
    }
}