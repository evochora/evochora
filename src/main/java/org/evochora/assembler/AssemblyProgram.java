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

    public abstract String getAssemblyCode();

    public Map<int[], Integer> assemble() {
        if (this.metadata != null) {
            return this.metadata.machineCodeLayout();
        }

        Assembler assembler = new Assembler();
        this.metadata = assembler.assemble(getAssemblyCode());

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

    // --- HIER SIND DIE ÄNDERUNGEN ---

    /**
     * Disassembliert die Instruktion für den NÄCHSTEN Tick (für den Footer).
     * Verwendet den aktuellen IP/DV.
     */
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

    /**
     * Disassembliert die Instruktion des LETZTEN Ticks (für das Log).
     * Verwendet ipBeforeFetch/dvBeforeFetch.
     */
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