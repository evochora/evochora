// src/main/java/org/evochora/Setup.java
package org.evochora;

import org.evochora.organism.Organism;
import org.evochora.organism.prototypes.*;

import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.evochora.assembler.AssemblyProgram; // GEÄNDERT: Neuer Importpfad

import java.util.Map;

public class Setup {

    /**
     * Die zentrale Methode, um die Welt mit den gewünschten Organismen zu bevölkern.
     * Kommentiere die Aufrufe aus oder ein, um verschiedene Szenarien zu testen.
     * @param simulation die Simulationsinstanz
     */
    public static void run(Simulation simulation) {

        // Um den CompleteInstructionTester auszuführen, aktivieren Sie diese Zeile:
        //InstructionTester testerProgram = new InstructionTester();
        //testerProgram.enableDebug();
        //placeProgram(simulation, testerProgram, new int[]{5, 1});

        // Um ErrorTester auszuführen, aktivieren Sie diese Zeile:
        EnergySeeker energySeeker = new EnergySeeker();
        energySeeker.enableDebug();
        placeProgram(simulation, energySeeker, new int[]{1, 1});

        // Um ErrorTester auszuführen, aktivieren Sie diese Zeile:
        //ErrorTest errorTest = new ErrorTest();
        //errorTest.enableDebug();
        //placeProgram(simulation, errorTest, new int[]{10, 5});
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