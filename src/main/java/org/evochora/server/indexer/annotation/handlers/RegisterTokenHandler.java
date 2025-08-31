package org.evochora.server.indexer.annotation.handlers;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Molecule;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.server.contracts.raw.SerializableProcFrame;
import org.evochora.server.indexer.annotation.ITokenHandler;
import org.evochora.server.indexer.annotation.TokenAnalysisResult;
import org.evochora.server.indexer.annotation.enums.TokenType;

import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Handles tokens that are register references or global aliases.
 * Supports all register types: %DRx, %PRx, %FPRx, %LRx.
 * Resolves global aliases to their bound registers.
 * Creates annotations showing register values.
 * Note: Procedure parameters are handled by ParameterTokenHandler.
 */
public class RegisterTokenHandler implements ITokenHandler {
    
    @Override
    public boolean canHandle(String token, int lineNumber, ProgramArtifact artifact, TokenInfo tokenInfo) {
        // Only handle tokens that start with %
        return token.startsWith("%");
    }
    
    @Override
    public TokenAnalysisResult analyze(String token, int lineNumber, ProgramArtifact artifact, TokenInfo tokenInfo, RawOrganismState o) {
        if (!token.startsWith("%")) {
            return null;
        }
        
        // Try to resolve as an alias first
        String canonicalReg = resolveToCanonicalRegister(token, o, artifact);
        
        if (canonicalReg != null) {
            // This is an alias or FPR - show resolution + value
            Object value = getRegisterValue(canonicalReg, o);
            String annotationText = String.format("[%s=%s]", canonicalReg, formatValue(value));
            return new TokenAnalysisResult(token, TokenType.REGISTER_REFERENCE, annotationText, "reg");
        } else {
            // Direct register reference - show just the value
            Object value = getRegisterValue(token, o);
            if (value != null) {
                String annotationText = String.format("[=%s]", formatValue(value));
                return new TokenAnalysisResult(token, TokenType.REGISTER_REFERENCE, annotationText, "reg");
            }
        }
        
        // No value found - no annotation
        return null;
    }
    
    /**
     * Resolves a token to its canonical register name.
     * Only handles global aliases from registerAliasMap.
     * Procedure parameters are handled by ParameterTokenHandler.
     */
    private String resolveToCanonicalRegister(String token, RawOrganismState o, ProgramArtifact artifact) {
        String upperToken = token.toUpperCase();
        
        // Check global aliases only
        if (artifact.registerAliasMap() != null) {
            Integer regId = artifact.registerAliasMap().get(upperToken);
            if (regId != null) {
                return "%DR" + regId;
            }
        }
        
        return null;
    }
    
        // Note: FPR binding chain resolution is now handled by ParameterTokenHandler
        // This handler only deals with global aliases and direct register references
    
    /**
     * Gets the runtime value of a register.
     */
    private Object getRegisterValue(String canonicalName, RawOrganismState o) {
        if (canonicalName == null) return null;
        
        if (canonicalName.startsWith("%DR")) {
            int regId = Integer.parseInt(canonicalName.substring(3));
            if (o.drs() != null && regId < o.drs().size()) {
                return o.drs().get(regId);
            }
        } else if (canonicalName.startsWith("%PR")) {
            int regId = Integer.parseInt(canonicalName.substring(3));
            if (o.prs() != null && regId < o.prs().size()) {
                return o.prs().get(regId);
            }
        } else if (canonicalName.startsWith("%FPR")) {
            int regId = Integer.parseInt(canonicalName.substring(4));
            if (o.fprs() != null && regId < o.fprs().size()) {
                return o.fprs().get(regId);
            }
        } else if (canonicalName.startsWith("%LR")) {
            int regId = Integer.parseInt(canonicalName.substring(3));
            if (o.lrs() != null && regId < o.lrs().size()) {
                return o.lrs().get(regId);
            }
        }
        
        return null;
    }
    
    /**
     * Formats a register value for display.
     * Handles molecules (TYPE:VALUE) and vectors ([x|y|...]).
     */
    private String formatValue(Object value) {
        if (value == null) return "null";
        
        if (value instanceof Integer i) {
            // Handle molecule values (TYPE:VALUE format)
            Molecule m = Molecule.fromInt(i);
            return String.format("%s:%d", typeIdToName(m.type()), m.toScalarValue());
        } else if (value instanceof int[] v) {
            // Handle vector values ([x|y|...] format)
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
        if (typeId == Config.TYPE_CODE) return "CODE";
        if (typeId == Config.TYPE_DATA) return "DATA";
        if (typeId == Config.TYPE_ENERGY) return "ENERGY";
        if (typeId == Config.TYPE_STRUCTURE) return "STRUCTURE";
        return "UNKNOWN";
    }
    
    /**
     * Formats a vector as [x|y|...].
     */
    private String formatVector(int[] vector) {
        if (vector == null) return "[]";
        return "[" + Arrays.stream(vector).mapToObj(String::valueOf).collect(java.util.stream.Collectors.joining("|")) + "]";
    }
    
    /**
     * Formats a list as [x|y|...] for JSON deserialized vectors.
     */
    private String formatListAsVector(java.util.List<?> list) {
        if (list == null) return "[]";
        return "[" + list.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("|")) + "]";
    }
}
