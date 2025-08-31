package org.evochora.server.indexer.annotation.handlers;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.runtime.isa.Instruction;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.server.contracts.raw.SerializableProcFrame;
import org.evochora.server.indexer.annotation.ITokenHandler;
import org.evochora.server.indexer.annotation.TokenAnalysisResult;
import org.evochora.server.indexer.annotation.enums.TokenType;

import java.util.Deque;
import java.util.List;

/**
 * Handles tokens that are procedure parameter names.
 * Shows the bound register and its value: [%DRx=TYPE:VALUE] or [%DRx=x|y|...].
 * Works anywhere in procedure bodies, not just on .PROC directive lines.
 * Resolves parameter bindings by walking the call stack to find actual DR/PR registers.
 */
public class ParameterTokenHandler implements ITokenHandler {
    
    @Override
    public boolean canHandle(String token, int lineNumber, ProgramArtifact artifact, TokenInfo tokenInfo) {
        // Use deterministic TokenInfo to check if this is a parameter token
        if (tokenInfo == null) {
            return false;
        }
        
        // Only handle VARIABLE type tokens that are parameters
        return tokenInfo.tokenType() == org.evochora.compiler.frontend.semantics.Symbol.Type.VARIABLE;
    }
    
    @Override
    public TokenAnalysisResult analyze(String token, int lineNumber, ProgramArtifact artifact, TokenInfo tokenInfo, RawOrganismState o) {
        // Check if this token is a parameter name for ANY procedure
        // We need to find which procedure this line belongs to
        String currentProcName = findCurrentProcedureName(lineNumber, artifact);
        if (currentProcName == null) {
            return null; // Not in any procedure
        }
        
        List<String> paramNames = artifact.procNameToParamNames().get(currentProcName.toUpperCase());
        if (paramNames == null) {
            return null; // Procedure has no parameters
        }
        
        // Check if this token is a parameter name
        int paramIndex = -1;
        for (int i = 0; i < paramNames.size(); i++) {
            if (paramNames.get(i).equalsIgnoreCase(token)) {
                paramIndex = i;
                break;
            }
        }
        
        if (paramIndex == -1) {
            return null; // Not a parameter name
        }
        
        // Get the current value of this parameter by resolving the binding chain
        if (o.callStack() != null && !o.callStack().isEmpty()) {
            // Resolve the binding chain to find the actual register this parameter is bound to
            int finalRegId = resolveBindingChain(o.callStack(), paramIndex);
            if (finalRegId >= 0) {
                // Get the value from the final register
                Object value = getRegisterValue(finalRegId, o);
                if (value != null) {
                    String canonicalRegName = idToCanonicalName(finalRegId);
                    String annotationText = String.format("[%s=%s]", canonicalRegName, formatValue(value));
                    return new TokenAnalysisResult(token, TokenType.PARAMETER_NAME, annotationText, "param");
                }
            }
        }
        
        // No value found - no annotation
        return null;
    }
    
    /**
     * Finds which procedure the given line belongs to by scanning backwards from the line.
     * This handles parameters anywhere in the procedure body, not just on .PROC lines.
     */
    private String findCurrentProcedureName(int lineNumber, ProgramArtifact artifact) {
        if (artifact.sources() == null || artifact.sourceMap() == null) {
            return null;
        }
        
        // Find the source file for this line
        String fileName = null;
        SourceInfo sourceInfo = artifact.sourceMap().get(lineNumber);
        if (sourceInfo != null) {
            fileName = sourceInfo.fileName();
        }
        
        if (fileName == null) {
            return null;
        }
        
        // Get the source lines for this file
        List<String> sourceLines = artifact.sources().get(fileName);
        if (sourceLines == null || lineNumber < 0 || lineNumber >= sourceLines.size()) {
            return null;
        }
        
        // Scan backwards from the current line to find the most recent .PROC directive
        for (int i = lineNumber; i >= 0; i--) {
            String line = sourceLines.get(i).trim();
            if (line.startsWith(".PROC")) {
                // Parse .PROC MY_PROC WITH PARAM1 PARAM2
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    return parts[1]; // The procedure name
                }
            }
            // Stop if we hit an .ENDP directive (end of procedure)
            if (line.startsWith(".ENDP")) {
                break;
            }
        }
        
        return null; // Not in any procedure
    }
    
    /**
     * Resolves the binding chain through the call stack to find the actual register.
     * Walks through FPR bindings to find the final DR/PR register.
     */
    private int resolveBindingChain(Deque<SerializableProcFrame> callStack, int initialFprIndex) {
        List<SerializableProcFrame> frames = new java.util.ArrayList<>(callStack);
        int currentRegId = Instruction.FPR_BASE + initialFprIndex;
        
        for (SerializableProcFrame frame : frames) {
            Integer mappedId = frame.fprBindings().get(currentRegId);
            if (mappedId != null) {
                currentRegId = mappedId;
                if (currentRegId < Instruction.FPR_BASE) {
                    return currentRegId; // Found a DR or PR
                }
            } else {
                // End of chain
                break;
            }
        }
        return currentRegId;
    }
    
    /**
     * Converts a register ID to its canonical name.
     */
    private String idToCanonicalName(int regId) {
        if (regId >= Instruction.FPR_BASE) return "%FPR" + (regId - Instruction.FPR_BASE);
        if (regId >= Instruction.PR_BASE) return "%PR" + (regId - Instruction.PR_BASE);
        if (regId >= 0) return "%DR" + regId;
        return "INVALID";
    }
    
    /**
     * Gets the runtime value of a register by ID.
     */
    private Object getRegisterValue(int regId, RawOrganismState o) {
        if (regId >= Instruction.FPR_BASE) {
            int index = regId - Instruction.FPR_BASE;
            return (o.fprs() != null && index < o.fprs().size()) ? o.fprs().get(index) : null;
        }
        if (regId >= Instruction.PR_BASE) {
            int index = regId - Instruction.PR_BASE;
            return (o.prs() != null && index < o.prs().size()) ? o.prs().get(index) : null;
        }
        if (regId >= 0 && o.drs() != null && regId < o.drs().size()) {
            return o.drs().get(regId);
        }
        return null;
    }
    
    /**
     * Formats a parameter value for display.
     * Handles molecules (TYPE:VALUE) and vectors (x|y|...).
     * Note: The register name is added by the caller, so we only format the value part.
     */
    private String formatValue(Object value) {
        if (value == null) return "null";
        
        if (value instanceof Integer i) {
            // Handle molecule values (TYPE:VALUE format)
            org.evochora.runtime.model.Molecule m = org.evochora.runtime.model.Molecule.fromInt(i);
            return String.format("%s:%d", typeIdToName(m.type()), m.toScalarValue());
        } else if (value instanceof int[] v) {
            // Handle vector values (x|y|... format, no brackets)
            return formatVector(v);
        } else if (value instanceof java.util.List<?> list) {
            // Handle vectors stored as List<Integer> after JSON deserialization
            return formatListAsVector(list);
        }
        
        return value.toString();
    }
    
    /**
     * Converts a type ID to its name.
     */
    private String typeIdToName(int typeId) {
        if (typeId == org.evochora.runtime.Config.TYPE_CODE) return "CODE";
        if (typeId == org.evochora.runtime.Config.TYPE_DATA) return "DATA";
        if (typeId == org.evochora.runtime.Config.TYPE_ENERGY) return "ENERGY";
        if (typeId == org.evochora.runtime.Config.TYPE_STRUCTURE) return "STRUCTURE";
        return "UNKNOWN";
    }
    
    /**
     * Formats a vector as x|y|... (no brackets).
     */
    private String formatVector(int[] vector) {
        if (vector == null) return "";
        return java.util.Arrays.stream(vector).mapToObj(String::valueOf).collect(java.util.stream.Collectors.joining("|"));
    }
    
    /**
     * Formats a list as x|y|... (no brackets) for JSON deserialized vectors.
     */
    private String formatListAsVector(java.util.List<?> list) {
        if (list == null) return "";
        return list.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("|"));
    }
}
