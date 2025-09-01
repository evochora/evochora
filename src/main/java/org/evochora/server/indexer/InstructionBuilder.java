package org.evochora.server.indexer;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.isa.InstructionArgumentType;
import org.evochora.runtime.isa.InstructionArgumentTypeUtils;
import org.evochora.runtime.isa.InstructionSignature;
import org.evochora.runtime.services.Disassembler;
import org.evochora.runtime.services.DisassemblyData;
import org.evochora.server.contracts.debug.PreparedTickState;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.server.contracts.raw.RawTickState;
import org.evochora.server.indexer.ArtifactValidator.ArtifactValidity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Builds instruction information for organisms by performing disassembly and formatting.
 * Handles disassembly, argument formatting, and instruction type determination.
 */
public class InstructionBuilder {
    
    private static final Logger log = LoggerFactory.getLogger(InstructionBuilder.class);
    
    private final Disassembler disassembler;
    
    public InstructionBuilder() {
        this.disassembler = new Disassembler();
    }
    
    /**
     * Builds the next instruction information for an organism.
     * 
     * @param organism The organism to build instruction info for
     * @param artifact The program artifact containing source information
     * @param validity The validity status of the artifact
     * @param rawTickState The raw tick state for disassembly context
     * @param envProps The environment properties for disassembly
     * @return The next instruction information
     */
    public PreparedTickState.NextInstruction buildNextInstruction(
            RawOrganismState organism, 
            ProgramArtifact artifact, 
            ArtifactValidity validity, 
            RawTickState rawTickState,
            org.evochora.runtime.model.EnvironmentProperties envProps) {
        
        try {
            // Create RawTickStateReader for this organism
            if (envProps == null) {
                log.error("EnvironmentProperties not available for buildNextInstruction - organism {} - cannot provide proper instruction data", organism.id());
                return new PreparedTickState.NextInstruction(
                    0, "UNKNOWN", new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), 
                    new PreparedTickState.LastExecutionStatus("ERROR", "EnvironmentProperties not available")
                );
            }
            RawTickStateReader reader = new RawTickStateReader(rawTickState, envProps);
            
            // Use the disassembler
            DisassemblyData data = disassembler.disassemble(reader, organism.ip());
            
            if (data == null) {
                return new PreparedTickState.NextInstruction(
                    0, "UNKNOWN", new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), 
                    new PreparedTickState.LastExecutionStatus("ERROR", "Disassembly failed")
                );
            }
            
            // Determine the lastExecutionStatus based on validity and organism status
            PreparedTickState.LastExecutionStatus lastExecutionStatus = buildExecutionStatus(organism, validity);
            
            // Convert argPositions from int[][] to List<int[]>
            List<int[]> argPositions = Arrays.stream(data.argPositions())
                .map(pos -> pos.clone())
                .collect(Collectors.toList());
            
            // Format arguments based on their types
            List<Object> formattedArguments = formatArguments(data);
            
            // Create the NextInstruction with the new structure
            return new PreparedTickState.NextInstruction(
                data.opcodeId(),
                data.opcodeName(),
                formattedArguments,
                buildArgumentTypes(data),
                argPositions,
                lastExecutionStatus
            );
            
        } catch (Exception e) {
            log.warn("Failed to disassemble instruction for organism {}: {}", organism.id(), e.getMessage());
            return new PreparedTickState.NextInstruction(
                0, "UNKNOWN", new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                new PreparedTickState.LastExecutionStatus("ERROR", "Disassembly failed: " + e.getMessage())
            );
        }
    }
    
    /**
     * Determines the execution status based on artifact validity and organism status.
     * 
     * @param organism The organism
     * @param validity The artifact validity
     * @return The execution status
     */
    private PreparedTickState.LastExecutionStatus buildExecutionStatus(RawOrganismState organism, ArtifactValidity validity) {
        // First check if the last instruction failed
        if (organism.instructionFailed()) {
            return new PreparedTickState.LastExecutionStatus("FAILED", organism.failureReason());
        }
        
        // Otherwise based on artifact validity
        switch (validity) {
            case NONE:
                return new PreparedTickState.LastExecutionStatus("SUCCESS", null);
            case VALID:
                return new PreparedTickState.LastExecutionStatus("SUCCESS", null);
            case PARTIAL_SOURCE:
                return new PreparedTickState.LastExecutionStatus("SUCCESS", null);
            case INVALID:
                return new PreparedTickState.LastExecutionStatus("SUCCESS", null);
            default:
                return new PreparedTickState.LastExecutionStatus("SUCCESS", null);
        }
    }
    
    /**
     * Formats arguments based on their actual molecule types.
     * The backend only extracts molecule types, ISA interpretation is done by the frontend.
     * 
     * @param data The disassembly data
     * @return List of formatted arguments
     */
    private List<Object> formatArguments(DisassemblyData data) {
        List<Object> formattedArgs = new ArrayList<>();
        
        for (int argValue : data.argValues()) {
            // Extract the actual molecule type from the raw DB (like in Internal State)
            Molecule m = Molecule.fromInt(argValue);
            String formattedValue = String.format("%s:%d", typeIdToName(m.type()), m.toScalarValue());
            formattedArgs.add(formattedValue);
        }
        
        return formattedArgs;
    }
    
    /**
     * Builds argument types based on the ISA signature.
     * 
     * @param data The disassembly data
     * @return List of argument type strings
     */
    private List<String> buildArgumentTypes(DisassemblyData data) {
        List<String> types = new ArrayList<>();
        
        // Get the ISA signature for the opcode
        try {
            Optional<InstructionSignature> signatureOpt = Instruction.getSignatureById(data.opcodeId());
            if (signatureOpt.isPresent()) {
                InstructionSignature signature = signatureOpt.get();
                // Use the real ISA signature
                for (InstructionArgumentType argType : signature.argumentTypes()) {
                    types.add(InstructionArgumentTypeUtils.toDisplayString(argType));
                }
            } else {
                // Fallback: All arguments as UNKNOWN
                for (int i = 0; i < data.argValues().length; i++) {
                    types.add(InstructionArgumentTypeUtils.getDefaultDisplayString());
                }
            }
        } catch (Exception e) {
            // On errors: All arguments as UNKNOWN
            log.debug("Could not determine argument types for opcode {}: {}", data.opcodeId(), e.getMessage());
            for (int i = 0; i < data.argValues().length; i++) {
                types.add("UNKNOWN");
            }
        }
        
        return types;
    }
    
    /**
     * Converts a type ID to a human-readable name.
     * Uses the central MoleculeTypeUtils utility to avoid duplication.
     * 
     * @param typeId The type ID
     * @return The type name
     */
    private String typeIdToName(int typeId) {
        return org.evochora.server.indexer.MoleculeTypeUtils.typeIdToName(typeId);
    }
}
