package org.evochora.assembler;

import org.evochora.Config;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class AssemblyProgram {

    protected static final Map<String, ProgramMetadata> programIdToMetadata = new HashMap<>();
    protected static final Map<Integer, String> organismIdToProgramId = new HashMap<>();

    protected ProgramMetadata metadata;
    protected Map<int[], Symbol> initialWorldObjects;
    private boolean isDebugEnabled = false;
    private int[] programOrigin = new int[Config.WORLD_DIMENSIONS];
    private final List<String> routineLibraries = new ArrayList<>();

    public AssemblyProgram() {
        Arrays.fill(this.programOrigin, 0);
        loadStandardRoutineLibraries();
    }

    /**
     * NEU: Sucht automatisch im Standard-Ressourcenverzeichnis nach .s-Dateien und lädt
     * deren Inhalt als potenzielle Routine-Bibliotheken.
     */
    private void loadStandardRoutineLibraries() {
        try {
            String routinesPath = "org/evochora/organism/prototypes/routines/";
            URL resourceUrl = Thread.currentThread().getContextClassLoader().getResource(routinesPath);
            if (resourceUrl == null) {
                // Das Verzeichnis existiert nicht, was in Ordnung ist, wenn keine Routinen vorhanden sind.
                return;
            }

            Path path = Paths.get(resourceUrl.toURI());
            try (Stream<Path> paths = Files.walk(path)) {
                paths.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".s") || p.toString().endsWith(".asm"))
                        .forEach(this::addRoutineLibraryFromFile);
            }
        } catch (URISyntaxException | IOException e) {
            System.err.println("Warnung: Konnte Standard-Routinen-Bibliotheken nicht laden: " + e.getMessage());
        }
    }

    /**
     * NEU: Hilfsmethode, um den Inhalt einer einzelnen Bibliotheksdatei zu laden.
     * @param filePath Der Pfad zur .s-Datei.
     */
    private void addRoutineLibraryFromFile(Path filePath) {
        try {
            this.routineLibraries.add(Files.readString(filePath));
        } catch (IOException e) {
            System.err.println("Fehler beim Lesen der Routine-Bibliothek: " + filePath);
            e.printStackTrace();
        }
    }

    public void setProgramOrigin(int[] origin) {
        if (origin.length != Config.WORLD_DIMENSIONS) {
            throw new IllegalArgumentException("Program origin must have " + Config.WORLD_DIMENSIONS + " dimensions.");
        }
        this.programOrigin = origin;
    }

    public int[] getProgramOrigin() {
        return Arrays.copyOf(this.programOrigin, this.programOrigin.length);
    }

    /**
     * Gibt den Assembler-Code des Hauptprogramms zurück.
     * Muss von abgeleiteten Klassen implementiert werden (oder von anonymen Klassen).
     */
    public abstract String getProgramCode();

    /**
     * NEU: Baut den vollständigen Code zusammen, indem zuerst alle geladenen
     * Routine-Bibliotheken und dann das Hauptprogramm angefügt werden.
     * @return Der komplette String, der an den Assembler übergeben wird.
     */
    public String getAssemblyCodeWithLibraries() {
        StringBuilder finalCode = new StringBuilder();

        // Füge alle geladenen Routine-Bibliotheken an den Anfang
        for (String libraryCode : this.routineLibraries) {
            finalCode.append(libraryCode).append("\n");
        }

        // Füge das Hauptprogramm an. Die .s-Datei selbst sollte mit .ORG 0|0 beginnen.
        finalCode.append("\n# --- Hauptprogramm --- \n");
        finalCode.append(this.getProgramCode());

        return finalCode.toString();
    }

    public AssemblyProgram enableDebug() {
        this.isDebugEnabled = true;
        return this;
    }

    /**
     * Startet den Assemblierungsprozess für den gesamten Code.
     * @return Eine Map, die das Speicherlayout des assemblierten Programms darstellt.
     */
    public Map<int[], Integer> assemble() {
        if (this.metadata != null) {
            return this.metadata.machineCodeLayout();
        }

        Assembler assembler = new Assembler();
        String programName = this.getClass().getSimpleName();
        // Übergibt den kombinierten Code (Bibliotheken + Hauptprogramm) an den Assembler.
        this.metadata = assembler.assemble(getAssemblyCodeWithLibraries(), programName, this.isDebugEnabled);

        if (this.metadata == null) {
            throw new IllegalStateException("Assembler hat null zurückgegeben. Assemblierung fehlgeschlagen.");
        }

        programIdToMetadata.put(this.metadata.programId(), this.metadata);
        this.initialWorldObjects = this.metadata.initialWorldObjects();
        return this.metadata.machineCodeLayout();
    }

    public Map<int[], Symbol> getInitialWorldObjects() {
        if (this.initialWorldObjects == null) {
            assemble();
        }
        return this.initialWorldObjects;
    }

    public void assignOrganism(Organism organism) {
        if (this.metadata == null || this.metadata.programId() == null) {
            throw new IllegalStateException("Muss zuerst .assemble() aufrufen, bevor ein Organismus zugewiesen wird.");
        }
        organismIdToProgramId.put(organism.getId(), this.metadata.programId());
    }

    public static String getProgramIdForOrganism(Organism organism) {
        return organismIdToProgramId.get(organism.getId());
    }

    public static ProgramMetadata getMetadataForProgram(String programId) {
        return programIdToMetadata.get(programId);
    }

    public static DisassembledInstruction getDisassembledInstructionDetailsForNextTick(Organism organism) {
        World world = organism.getSimulation().getWorld();
        int[] ip = organism.getIp();
        int[] currentDv = organism.getDv();

        String programId = getProgramIdForOrganism(organism);
        ProgramMetadata metadata = (programId != null) ? programIdToMetadata.get(programId) : null;

        Disassembler disassembler = new Disassembler();

        if (metadata != null) {
            return disassembler.disassemble(metadata, ip, currentDv, world);
        } else {
            return disassembler.disassembleGeneric(ip, world);
        }
    }

    public static DisassembledInstruction getDisassembledInstructionDetailsForLastTick(Organism organism) {
        World world = organism.getSimulation().getWorld();
        int[] ip = organism.getIpBeforeFetch();
        int[] currentDv = organism.getDvBeforeFetch();

        String programId = getProgramIdForOrganism(organism);
        ProgramMetadata metadata = (programId != null) ? programIdToMetadata.get(programId) : null;

        Disassembler disassembler = new Disassembler();

        if (metadata != null) {
            return disassembler.disassemble(metadata, ip, currentDv, world);
        } else {
            return disassembler.disassembleGeneric(ip, world);
        }
    }
}