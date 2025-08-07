// src/main/java/org/evochora/assembler/AssemblyProgram.java
package org.evochora.assembler;

import org.evochora.Config;
import org.evochora.organism.Instruction;
import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;
import org.evochora.assembler.Disassembler;
import org.evochora.assembler.DisassembledInstruction;
import org.evochora.assembler.ProgramMetadata;
import org.evochora.assembler.Assembler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.Arrays;
import java.util.Collections;

public abstract class AssemblyProgram {

    protected static final Map<String, ProgramMetadata> programIdToMetadata = new HashMap<>();
    protected static final Map<Integer, String> organismIdToProgramId = new HashMap<>();

    protected ProgramMetadata metadata;
    protected Map<int[], Symbol> initialWorldObjects;
    private final List<RoutineEntry> routines = new ArrayList<>();
    private boolean isDebugEnabled = false;
    private int[] programOrigin = new int[Config.WORLD_DIMENSIONS];

    private record RoutineEntry(String name, AssemblyRoutine routine, int[] relativePosition, Map<String, String> registerMap) {}

    public AssemblyProgram() {
        Arrays.fill(this.programOrigin, 0);
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

    public abstract String getProgramCode();

    // NEU: Methode mit Register-Mapping
    public AssemblyProgram includeRoutine(String name, AssemblyRoutine routine, int[] relativePosition, Map<String, String> registerMap) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Der Name für die Routine darf nicht null oder leer sein.");
        }
        if (relativePosition.length != Config.WORLD_DIMENSIONS) {
            throw new IllegalArgumentException("Die relative Position muss " + Config.WORLD_DIMENSIONS + "-dimensional sein.");
        }
        this.routines.add(new RoutineEntry(name.toUpperCase(), routine, relativePosition, registerMap));
        return this;
    }

    // Bestehende Methode ohne Register-Mapping (für Routinen, die keine benötigen)
    public AssemblyProgram includeRoutine(String name, AssemblyRoutine routine, int[] relativePosition) {
        return includeRoutine(name, routine, relativePosition, Collections.emptyMap());
    }

    public String getAssemblyCode() {
        StringBuilder finalCode = new StringBuilder();

        for (RoutineEntry entry : this.routines) {
            finalCode.append(entry.routine().getFormattedCode(entry.name(), entry.relativePosition(), entry.registerMap()));
        }

        finalCode.append("\n# --- Hauptprogramm --- \n");
        String originCoords = Arrays.stream(this.programOrigin).mapToObj(String::valueOf).collect(Collectors.joining("|"));
        finalCode.append(".ORG ").append(originCoords).append("\n");
        finalCode.append(this.getProgramCode());

        return finalCode.toString();
    }

    public AssemblyProgram enableDebug() {
        this.isDebugEnabled = true;
        return this;
    }

    public Map<int[], Integer> assemble() {
        if (this.metadata != null) {
            return this.metadata.machineCodeLayout();
        }

        Assembler assembler = new Assembler();
        String programName = this.getClass().getSimpleName();

        this.metadata = assembler.assemble(getAssemblyCode(), programName, this.isDebugEnabled);

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