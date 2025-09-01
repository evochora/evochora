package org.evochora.server.indexer;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.Config;
import org.evochora.runtime.model.Molecule;
import org.evochora.server.contracts.debug.PreparedTickState;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.server.contracts.raw.SerializableProcFrame;
import org.evochora.server.indexer.ArtifactValidator.ArtifactValidity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds internal state information for organisms including registers, stacks, and call stack.
 * Handles register value formatting, stack formatting, and call stack entry construction.
 */
public class InternalStateBuilder {
    
    private static final Logger log = LoggerFactory.getLogger(InternalStateBuilder.class);
    
    /**
     * Builds the complete internal state for an organism.
     * 
     * @param organism The organism to build internal state for
     * @param artifact The program artifact containing source information
     * @param validity The validity status of the artifact
     * @return The complete internal state
     */
    public PreparedTickState.InternalState buildInternalState(RawOrganismState organism, ProgramArtifact artifact, ArtifactValidity validity) {
        // Data Registers (DR) - dynamic count
        List<PreparedTickState.RegisterValue> dataRegisters = buildRegisterValues(organism.drs(), "DR");
        
        // Procedure Registers (PR) - dynamic count
        List<PreparedTickState.RegisterValue> procRegisters = buildRegisterValues(organism.prs(), "PR");
        
        // Floating Point Registers (FPR) - dynamic count
        List<PreparedTickState.RegisterValue> fpRegisters = buildRegisterValues(organism.fprs(), "FPR");
        
        // Location Registers (LR) - dynamic count
        List<PreparedTickState.RegisterValue> locationRegisters = buildRegisterValues(organism.lrs(), "LR");
        
        // Data Stack (DS) - as string list
        List<String> dataStack = organism.dataStack() != null ? 
            organism.dataStack().stream().map(this::formatValue).toList() : new ArrayList<>();
        
        // Location Stack (LS) - as string list
        List<String> locationStack = organism.locationStack() != null ? 
            organism.locationStack().stream().map(this::formatVector).toList() : new ArrayList<>();
        
        // Call Stack (CS) - as structured data
        List<PreparedTickState.CallStackEntry> callStack = buildCallStack(organism, artifact);
        
        // DPS from organism
        List<List<Integer>> dps = organism.dps() != null ? organism.dps().stream().map(this::toList).toList() : new ArrayList<>();
        
        return new PreparedTickState.InternalState(
            dataRegisters,      // dataRegisters
            procRegisters,      // procRegisters  
            fpRegisters,        // fpRegisters
            locationRegisters,  // locationRegisters
            dataStack,          // dataStack
            locationStack,      // locationStack
            callStack,          // callStack
            dps                 // dps
        );
    }
    
    /**
     * Builds the call stack for an organism.
     * 
     * @param organism The organism
     * @param artifact The program artifact
     * @return List of call stack entries
     */
    private List<PreparedTickState.CallStackEntry> buildCallStack(RawOrganismState organism, ProgramArtifact artifact) {
        if (organism.callStack() == null || organism.callStack().isEmpty()) {
            return Collections.emptyList();
        }

        return organism.callStack().stream()
                .map(frame -> buildCallStackEntry(frame, organism, artifact))
                .collect(Collectors.toList());
    }
    
    /**
     * Builds a single call stack entry.
     * 
     * @param frame The procedure frame
     * @param organism The organism
     * @param artifact The program artifact
     * @return The call stack entry
     */
    private PreparedTickState.CallStackEntry buildCallStackEntry(SerializableProcFrame frame, RawOrganismState organism, ProgramArtifact artifact) {
        // 1. Procedure name
        String procName = frame.procName();
        
        // 2. Absolute return IP as coordinates
        int[] returnCoordinates = frame.absoluteReturnIp() != null && frame.absoluteReturnIp().length >= 2 ? 
            frame.absoluteReturnIp() : new int[]{0, 0};
        
        // 3. Parameter bindings
        List<PreparedTickState.ParameterBinding> parameters = new ArrayList<>();
        
        if (frame.fprBindings() != null && !frame.fprBindings().isEmpty()) {
            // Sort FPR bindings by index for consistent display
            List<Map.Entry<Integer, Integer>> sortedBindings = frame.fprBindings().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .collect(Collectors.toList());
            
            for (Map.Entry<Integer, Integer> binding : sortedBindings) {
                int fprIndex = binding.getKey() - org.evochora.runtime.isa.Instruction.FPR_BASE; // Subtract FPR base
                int drId = binding.getValue();
                
                // Get current register value
                String registerValue = "";
                if (drId >= 0 && drId < organism.drs().size()) {
                    Object drValue = organism.drs().get(drId);
                    if (drValue != null) {
                        registerValue = formatValue(drValue);
                    }
                }
                
                // Resolve parameter name (if available)
                String paramName = null;
                if (artifact != null && artifact.procNameToParamNames().containsKey(frame.procName().toUpperCase())) {
                    List<String> paramNames = artifact.procNameToParamNames().get(frame.procName().toUpperCase());
                    if (fprIndex < paramNames.size()) {
                        paramName = paramNames.get(fprIndex);
                    }
                }
                
                // Create ParameterBinding
                parameters.add(new PreparedTickState.ParameterBinding(drId, registerValue, paramName));
            }
        }
        
        return new PreparedTickState.CallStackEntry(procName, returnCoordinates, parameters, frame.fprBindings());
    }
    
    /**
     * Creates a list of RegisterValue objects for the actually available registers.
     * Works dynamically with the number of available registers.
     * 
     * @param rawRegisters The raw register values
     * @param prefix The register prefix (DR, PR, FPR, LR)
     * @return List of formatted register values
     */
    private List<PreparedTickState.RegisterValue> buildRegisterValues(List<Object> rawRegisters, String prefix) {
        List<PreparedTickState.RegisterValue> result = new ArrayList<>();
        
        if (rawRegisters == null || rawRegisters.isEmpty()) {
            return result; // Return empty list
        }
        
        for (int i = 0; i < rawRegisters.size(); i++) {
            String registerId = prefix + i;
            String alias = ""; // No aliases for now
            String value = "";
            
            Object rawValue = rawRegisters.get(i);
            if (rawValue != null) {
                value = formatValue(rawValue);
            }
            
            result.add(new PreparedTickState.RegisterValue(registerId, alias, value));
        }
        
        return result;
    }
    
    /**
     * Formats a value object for display.
     * 
     * @param obj The object to format
     * @return The formatted string
     */
    private String formatValue(Object obj) {
        if (obj instanceof Integer i) {
            Molecule m = Molecule.fromInt(i);
            return String.format("%s:%d", MoleculeTypeUtils.typeIdToName(m.type()), m.toScalarValue());
        } else if (obj instanceof int[] v) {
            return formatVector(v);
        } else if (obj instanceof java.util.List<?> list) {
            // After JSON deserialization, arrays are read as List<Integer>
            return formatListAsVector(list);
        }
        return "null";
    }
    
    /**
     * Formats a vector array for display.
     * 
     * @param vector The vector to format
     * @return The formatted string
     */
    private String formatVector(int[] vector) {
        if (vector == null) return "[]";
        return "[" + Arrays.stream(vector).mapToObj(String::valueOf).collect(Collectors.joining("|")) + "]";
    }
    
    /**
     * Formats a list as a vector for display.
     * 
     * @param list The list to format
     * @return The formatted string
     */
    private String formatListAsVector(java.util.List<?> list) {
        if (list == null) return "[]";
        return "[" + list.stream().map(String::valueOf).collect(Collectors.joining("|")) + "]";
    }
    

    
    /**
     * Converts an int array to a List<Integer>.
     * 
     * @param arr The array to convert
     * @return The list
     */
    private List<Integer> toList(int[] arr) {
        if (arr == null) return Collections.emptyList();
        return Arrays.stream(arr).boxed().collect(Collectors.toList());
    }
}
