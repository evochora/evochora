package org.evochora;

import org.evochora.assembler.AssemblerException;
import org.evochora.assembler.AssemblyProgram;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class Setup {

    /**
     * Die zentrale Methode, um die Welt mit den gewünschten Organismen zu bevölkern.
     * Kommentiere die Aufrufe aus oder ein, um verschiedene Szenarien zu testen.
     * Die Namen entsprechen den .s-Dateien im 'prototypes'-Verzeichnis.
     * @param simulation die Simulationsinstanz
     */
    public static void run(Simulation simulation) {
        // Um den InstructionTester auszuführen, aktivieren Sie diese Zeile:
        //placeProgramFromFile(simulation, "InstructionTester.s", new int[]{5, 1});
        placeProgramFromFile(simulation, "RoutineTester.s", new int[]{10, 10});

        // Um ErrorTester auszuführen, aktivieren Sie diese Zeile:
        // placeProgramFromFile(simulation, "ErrorTest.s", new int[]{10, 5});

        // Um den EnergySeeker auszuführen, aktivieren Sie diese Zeile:
        // placeProgramFromFile(simulation, "EnergySeeker.s", new int[]{1, 1});
    }

    /**
     * NEU: Lädt den Quellcode eines Organismus aus einer .s-Datei, assembliert ihn
     * und platziert ihn an der gewünschten Startposition in der Welt.
     * @param simulation Die Simulationsinstanz.
     * @param filename Der Dateiname der .s-Datei im 'prototypes'-Verzeichnis.
     * @param startPos Die absolute Startposition in der Welt.
     */
    private static void placeProgramFromFile(Simulation simulation, String filename, int[] startPos) {
        try {
            // Lade den Code aus der .s-Datei im prototypes-Verzeichnis
            String prototypesPath = "org/evochora/organism/prototypes/";
            URL resourceUrl = Thread.currentThread().getContextClassLoader().getResource(prototypesPath + filename);

            if (resourceUrl == null) {
                System.err.println("FEHLER: Prototypen-Datei nicht gefunden: " + filename);
                return;
            }

            Path path = Paths.get(resourceUrl.toURI());
            final String programCode = Files.readString(path);

            // Erstelle ein anonymes AssemblyProgram-Objekt, das den Code enthält.
            // Die Routinen-Bibliotheken werden vom Konstruktor automatisch geladen.
            AssemblyProgram program = new AssemblyProgram() {
                @Override
                public String getProgramCode() {
                    return programCode;
                }
            };

            // Setze die Startposition und führe die Assemblierung durch
            program.setProgramOrigin(startPos);
            program.enableDebug();

            Map<int[], Integer> layout = program.assemble();
            Map<int[], Symbol> worldObjects = program.getInitialWorldObjects();

            // Platziere den Organismus in der Welt
            Organism org = placeOrganismWithLayout(simulation, program.getProgramOrigin(), layout, worldObjects, simulation.getLogger());
            program.assignOrganism(org);

        } catch (Exception e) {
            System.err.println("FATALER FEHLER beim Laden oder Assemblieren von " + filename + ":");
            // Wir nutzen die formatierte Fehlermeldung der AssemblerException für eine saubere Ausgabe.
            if (e instanceof AssemblerException) {
                System.err.println(((AssemblerException) e).getFormattedMessage());
            } else {
                e.printStackTrace();
            }
        }
    }

    /**
     * Eine generische Hilfsmethode, um einen Organismus mit einem räumlichen Layout und zusätzlichen
     * Objekten (definiert durch .PLACE) in der Welt zu platzieren.
     * Diese Methode bleibt funktional unverändert.
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
            int[] absolutePos = new int[startPos.length];
            for (int i = 0; i < startPos.length; i++) {
                absolutePos[i] = startPos[i] + relativePos[i];
            }
            world.setSymbol(Symbol.fromInt(entry.getValue()), absolutePos);
        }

        // 2. Platziere die zusätzlichen Welt-Objekte, die durch .PLACE definiert wurden.
        for (Map.Entry<int[], Symbol> entry : worldObjects.entrySet()) {
            int[] relativePos = entry.getKey();
            int[] absolutePos = new int[startPos.length];
            for (int i = 0; i < startPos.length; i++) {
                absolutePos[i] = startPos[i] + relativePos[i];
            }
            world.setSymbol(entry.getValue(), absolutePos);
        }

        // 3. Erstelle den Organismus am Startpunkt des Codes.
        Organism org = Organism.create(simulation, startPos, Config.INITIAL_ORGANISM_ENERGY, logger);
        simulation.addOrganism(org);
        return org;
    }
}