package org.evochora.compiler.internal.legacy;

import org.evochora.app.setup.Config;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.model.Organism;
import org.evochora.runtime.model.Environment;

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
import java.util.stream.Stream;

public abstract class AssemblyProgram {

    protected static final Map<String, ProgramMetadata> programIdToMetadata = new HashMap<>();
    protected static final Map<Integer, String> organismIdToProgramId = new HashMap<>();

    protected ProgramMetadata metadata;
    protected Map<int[], Molecule> initialWorldObjects;
    private boolean isDebugEnabled = false;
    private int[] programOrigin = new int[Config.WORLD_DIMENSIONS];
    private final Map<String, String> routineLibraries = new HashMap<>();
    private final String mainProgramFileName;

    public AssemblyProgram(String mainProgramFileName) {
        this.mainProgramFileName = mainProgramFileName;
        Arrays.fill(this.programOrigin, 0);
        //loadStandardRoutineLibraries();
    }

    public AssemblyProgram() {
        this.mainProgramFileName = this.getClass().getSimpleName() + ".java";
        Arrays.fill(this.programOrigin, 0);
        //loadStandardRoutineLibraries();
    }

    private void loadStandardRoutineLibraries() {
        try {
            String routinesPath = "org/evochora/organism/prototypes/routines.old/";
            URL resourceUrl = Thread.currentThread().getContextClassLoader().getResource(routinesPath);
            if (resourceUrl == null) {
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

    private void addRoutineLibraryFromFile(Path filePath) {
        try {
            String content = Files.readString(filePath);
            String fileName = filePath.getFileName().toString();
            this.routineLibraries.put(fileName, content);
        } catch (IOException e) {
            System.err.println("Fehler beim Lesen der Routine-Bibliothek: " + filePath);
            e.printStackTrace();
        }
    }

    public void setProgramOrigin(int[] origin) {
        this.programOrigin = origin;
    }

    public int[] getProgramOrigin() {
        return Arrays.copyOf(this.programOrigin, this.programOrigin.length);
    }

    public abstract String getProgramCode();

    public List<AnnotatedLine> getAnnotatedCode() {
        List<AnnotatedLine> allLines = new ArrayList<>();
        for (Map.Entry<String, String> entry : routineLibraries.entrySet()) {
            String fileName = entry.getKey();
            String content = entry.getValue();
            int lineNum = 1;
            for (String line : content.split("\\r?\\n")) {
                allLines.add(new AnnotatedLine(line, lineNum++, fileName));
            }
        }
        int lineNum = 1;
        // KORREKTUR: Verwendet den echten Dateinamen.
        for (String line : getProgramCode().split("\\r?\\n")) {
            allLines.add(new AnnotatedLine(line, lineNum++, this.mainProgramFileName));
        }
        return allLines;
    }


    public AssemblyProgram enableDebug() {
        this.isDebugEnabled = true;
        return this;
    }

    public Map<int[], Integer> assemble() {
        if (this.metadata != null) return this.metadata.machineCodeLayout();
        Assembler assembler = new Assembler();
        String programName = this.mainProgramFileName;
        this.metadata = assembler.assemble(getAnnotatedCode(), programName, this.isDebugEnabled);

        if (this.metadata == null) throw new IllegalStateException("Assembler hat null zurückgegeben.");
        programIdToMetadata.put(this.metadata.programId(), this.metadata);
        this.initialWorldObjects = this.metadata.initialWorldObjects();
        return this.metadata.machineCodeLayout();
    }

    // Getter und statische Methoden bleiben unverändert
    public Map<int[], Molecule> getInitialWorldObjects() {
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
        Environment environment = organism.getSimulation().getEnvironment();
        int[] ip = organism.getIp();
        int[] currentDv = organism.getDv();
        String programId = getProgramIdForOrganism(organism);
        ProgramMetadata metadata = (programId != null) ? programIdToMetadata.get(programId) : null;
        Disassembler disassembler = new Disassembler();
        if (metadata != null) {
            return disassembler.disassemble(metadata, ip, currentDv, environment);
        } else {
            return disassembler.disassembleGeneric(ip, environment);
        }
    }

    public static DisassembledInstruction getDisassembledInstructionDetailsForLastTick(Organism organism) {
        Environment environment = organism.getSimulation().getEnvironment();
        int[] ip = organism.getIpBeforeFetch();
        int[] currentDv = organism.getDvBeforeFetch();
        String programId = getProgramIdForOrganism(organism);
        ProgramMetadata metadata = (programId != null) ? programIdToMetadata.get(programId) : null;
        Disassembler disassembler = new Disassembler();
        if (metadata != null) {
            return disassembler.disassemble(metadata, ip, currentDv, environment);
        } else {
            return disassembler.disassembleGeneric(ip, environment);
        }
    }

    /**
     * TODO: [Phase 2] Temporäre Brückenmethode, damit Tests, die die neue Compiler-API
     *  verwenden, weiterhin das alte, statische Metadaten-System füttern können.
     *  Wird entfernt, sobald die Runtime direkt mit dem ProgramArtifact arbeitet.
     */
    public static void registerProgram(ProgramArtifact artifact, Organism organism) {
        ProgramMetadata legacyMetadata = ProgramMetadata.fromArtifact(artifact);
        programIdToMetadata.put(artifact.programId(), legacyMetadata);
        organismIdToProgramId.put(organism.getId(), artifact.programId());
    }
}