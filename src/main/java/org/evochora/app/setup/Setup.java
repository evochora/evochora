package org.evochora.app.setup;

import org.evochora.compiler.diagnostics.CompilerLogger;
import org.evochora.app.Simulation;
import org.evochora.compiler.internal.legacy.AssemblerException;
import org.evochora.compiler.internal.legacy.AssemblyProgram;
import org.evochora.compiler.CompilerRunner;
import org.evochora.compiler.api.CompilationException;
import org.evochora.runtime.model.Environment;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.slf4j.Logger;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class Setup {

    public static void run(Simulation simulation) {

        CompilerLogger.setLevel(CompilerLogger.TRACE);

        // Lade den visuellen Stdlib-Tester
        //placeProgramFromFile(simulation, "StdlibTest.s", new int[]{10, 10});

        // Lade den Stdlib-Tester
        //placeProgramFromFile(simulation, "StdlibTest.s", new int[]{2, 2});

        // Lade den Tactics-Tester
        //placeProgramFromFile(simulation, "TacticsTest.s", new int[]{2, 2});


        // Lade
        placeProgramFromFileViaCompiler(simulation, "InstructionTester.s", new int[]{5, 1});

        // Lade den EnergySeeker
        //EnergySeeker energySeeker = new EnergySeeker();
        //placeProgram(simulation, energySeeker, new int[]{10, 5});
    }

    private static void placeProgramFromFile(Simulation simulation, String filename, int[] startPos) {
        try {
            String prototypesPath = "org/evochora/organism/prototypes/";
            URL resourceUrl = Thread.currentThread().getContextClassLoader().getResource(prototypesPath + filename);
            if (resourceUrl == null) {
                System.err.println("FEHLER: Prototypen-Datei nicht gefunden: " + filename);
                return;
            }

            Path path = Paths.get(resourceUrl.toURI());
            final String programCode = Files.readString(path);

            // KORREKTUR: Wir übergeben den Dateinamen an das AssemblyProgram
            AssemblyProgram program = new AssemblyProgram(filename) {
                @Override
                public String getProgramCode() {
                    return programCode;
                }
            };

            program.setProgramOrigin(startPos);
            // program.enableDebug();

            Map<int[], Integer> layout = program.assemble();
            Map<int[], Molecule> worldObjects = program.getInitialWorldObjects();

            Organism org = placeOrganismWithLayout(simulation, program.getProgramOrigin(), layout, worldObjects, simulation.getLogger());
            program.assignOrganism(org);

        } catch (Exception e) {
            if (e instanceof AssemblerException) {
                System.err.println(((AssemblerException) e).getMessage());
            } else {
                System.err.println("FATALER FEHLER beim Laden von " + filename + ":");
                e.printStackTrace();
            }
        }
    }

    /**
     * Alternative: Programm via neuem Compiler kompilieren und in die Runtime injizieren.
     */
    public static void placeProgramFromFileViaCompiler(Simulation simulation, String filename, int[] startPos) {
        try {
            String prototypesPath = "org/evochora/organism/prototypes/";
            URL resourceUrl = Thread.currentThread().getContextClassLoader().getResource(prototypesPath + filename);
            if (resourceUrl == null) {
                System.err.println("FEHLER: Prototypen-Datei nicht gefunden: " + filename);
                return;
            }
            Path path = Paths.get(resourceUrl.toURI());
            final String programCode = Files.readString(path);
            java.util.List<String> lines = java.util.Arrays.asList(programCode.split("\r?\n"));
            CompilerRunner.loadIntoEnvironment(simulation, lines, filename, startPos);
        } catch (CompilationException ce) {
            System.err.println("Compilerfehler: " + ce.getMessage());
        } catch (Exception e) {
            System.err.println("FATALER FEHLER beim Laden (Compiler) von " + filename + ":");
            e.printStackTrace();
        }
    }

    /**
     * Eine private Hilfsmethode, die den kompletten Setup-Prozess für ein assembliertes Programm durchführt.
     */
    private static void placeProgram(Simulation simulation, AssemblyProgram program, int[] startPos) {
        // 1. Assembliere den Code, um das Layout und die zusätzlichen Welt-Objekte zu erhalten.
        Map<int[], Integer> layout = program.assemble();
        Map<int[], Molecule> worldObjects = program.getInitialWorldObjects();

        // 2. Platziere den Organismus physisch in der Welt.
        Organism org = placeOrganismWithLayout(simulation, startPos, layout, worldObjects, simulation.getLogger());

        // 3. Verknüpfe die laufende Instanz mit den Metadaten des assemblierten Programms.
        program.assignOrganism(org);
    }

    private static Organism placeOrganismWithLayout(
            Simulation simulation, int[] startPos, Map<int[], Integer> layout,
            Map<int[], Molecule> worldObjects, Logger logger) {

        Environment environment = simulation.getEnvironment();

        for (Map.Entry<int[], Integer> entry : layout.entrySet()) {
            int[] relativePos = entry.getKey();
            int[] absolutePos = new int[startPos.length];
            for (int i = 0; i < startPos.length; i++) {
                absolutePos[i] = startPos[i] + relativePos[i];
            }
            environment.setMolecule(org.evochora.runtime.model.Molecule.fromInt(entry.getValue()), absolutePos);
        }

        for (Map.Entry<int[], Molecule> entry : worldObjects.entrySet()) {
            int[] relativePos = entry.getKey();
            int[] absolutePos = new int[startPos.length];
            for (int i = 0; i < startPos.length; i++) {
                absolutePos[i] = startPos[i] + relativePos[i];
            }
            environment.setMolecule(entry.getValue(), absolutePos);
        }

        Organism org = Organism.create(simulation, startPos, Config.INITIAL_ORGANISM_ENERGY, logger);
        simulation.addOrganism(org);
        return org;
    }
}