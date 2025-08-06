// src/main/java/org/evochora/assembler/AssemblyProgram.java
package org.evochora.assembler;

import org.evochora.organism.Organism;
import org.evochora.world.Symbol;
import org.evochora.world.World;

import java.util.HashMap;
import java.util.Map;

public abstract class AssemblyProgram {

    protected static final Map<String, ProgramMetadata> programIdToMetadata = new HashMap<>();
    protected static final Map<Integer, String> organismIdToProgramId = new HashMap<>();

    protected ProgramMetadata metadata;
    protected Map<int[], Symbol> initialWorldObjects;

    // NEU: Ein Flag, um den Debug-Modus für diese spezifische Assemblierung zu steuern
    private boolean isDebugEnabled = false;

    public abstract String getAssemblyCode();

    // NEU: Eine "fluent" Methode, um den Debug-Modus von außen zu aktivieren.
    // Beispiel: new MyTestProgram().enableDebug().assemble();
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

        // GEÄNDERT: Übergibt den Programmnamen und den Debug-Status an den Assembler
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