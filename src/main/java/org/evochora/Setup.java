// src/main/java/org/evochora/Setup.java
package org.evochora;

import org.evochora.organism.Organism;
import org.evochora.organism.prototypes.*;

import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.evochora.assembler.AssemblyProgram; // GEÄNDERT: Neuer Importpfad

import javax.lang.model.type.ErrorType;
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

        // HIER IST DIE ZENTRALE STELLE FÜR DIE TESTER-LOGIK
        // Um das Testprogramm auszuführen, aktivieren Sie diese Zeile:
        //ErrorTester testerProgram = new ErrorTester();
        //placeProgram(simulation, testerProgram, new int[]{10, 5});


        // Um das Testprogramm auszuführen, aktivieren Sie diese Zeile:
        InstructionLengthCounter testerProgram = new InstructionLengthCounter();
        placeProgram(simulation, testerProgram, new int[]{10, 5});

        // HIER IST DIE ZENTRALE STELLE FÜR DIE TESTER-LOGIK
        // Um das Testprogramm auszuführen, aktivieren Sie diese Zeile:
        //CompleteInstructionTester testerProgram = new CompleteInstructionTester();
        //placeProgram(simulation, testerProgram, new int[]{10, 5});

        /*
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

        */
    }

    /**
     * Eine private Hilfsmethode, die den kompletten Setup-Prozess für ein assembliertes Programm durchführt.
     */
    private static void placeProgram(Simulation simulation, AssemblyProgram program, int[] startPos) {
        // 1. Assembliere den Code, um das Layout und die zusätzlichen Welt-Objekte zu erhalten.
        Map<int[], Integer> layout = program.assemble();
        Map<int[], Symbol> worldObjects = program.getInitialWorldObjects();

        // 2. Platziere den Organismus physisch in der Welt.
        Organism org = placeOrganismWithLayout(simulation, startPos, layout, worldObjects, simulation.getLogger());

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
            Map<int[], Symbol> worldObjects,
            Logger logger)
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
        Organism org = Organism.create(simulation, startPos, Config.INITIAL_ORGANISM_ENERGY, logger);
        simulation.addOrganism(org);
        return org;
    }
}