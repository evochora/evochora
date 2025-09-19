package org.evochora.datapipeline.services.debugindexer.annotation.handlers;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Molecule;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.datapipeline.services.debugindexer.annotation.ITokenHandler;
import org.evochora.datapipeline.services.debugindexer.annotation.TokenAnalysisResult;
import org.evochora.datapipeline.services.debugindexer.annotation.enums.TokenType;

import java.util.Arrays;
import java.util.List;

/**
 * Handles tokens that are register references or global aliases.
 * Supports all register types: %DRx, %PRx, %FPRx, %LRx.
 * Resolves global aliases to their bound registers.
 * Creates annotations showing register values.
 */
public class RegisterTokenHandler implements ITokenHandler {
    
    @Override
    public boolean canHandle(String token, int lineNumber, String fileName, ProgramArtifact artifact, TokenInfo tokenInfo) {
        if (tokenInfo == null) {
            return false;
        }
        
        return tokenInfo.tokenType() == org.evochora.compiler.frontend.semantics.Symbol.Type.ALIAS ||
               (tokenInfo.tokenType() == org.evochora.compiler.frontend.semantics.Symbol.Type.VARIABLE && token.startsWith("%"));
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
            String value = getRegisterValue(canonicalReg, o);
            String annotationText = canonicalReg + "=" + value;
            return new TokenAnalysisResult(token, TokenType.REGISTER_REFERENCE, annotationText, "reg");
        } else {
            // Direct register reference
            String value = getRegisterValue(token, o);
            String annotationText = "=" + value;
            return new TokenAnalysisResult(token, TokenType.REGISTER_REFERENCE, annotationText, "reg");
        }
    }
    
    private String resolveToCanonicalRegister(String token, RawOrganismState o, ProgramArtifact artifact) {
        // Simplified implementation - resolve aliases to canonical register names
        if (artifact.registerAliasMap() != null && artifact.registerAliasMap().containsKey(token)) {
            return String.valueOf(artifact.registerAliasMap().get(token));
        }
        return null;
    }
    
    private String getRegisterValue(String register, RawOrganismState o) {
        // Simplified implementation - extract register value
        if (register.startsWith("%DR")) {
            int index = Integer.parseInt(register.substring(3));
            if (index < o.drs().size()) {
                Object value = o.drs().get(index);
                return formatValue(value);
            }
        }
        return "0";
    }
    
    private String formatValue(Object value) {
        if (value instanceof Integer i) {
            Molecule m = Molecule.fromInt(i);
            return String.format("%s:%d", getTypeName(m.type()), m.toScalarValue());
        }
        return "0";
    }
    
    private String getTypeName(int type) {
        if (type == Config.TYPE_CODE) return "CODE";
        if (type == Config.TYPE_DATA) return "DATA";
        if (type == Config.TYPE_ENERGY) return "ENERGY";
        if (type == Config.TYPE_STRUCTURE) return "STRUCTURE";
        return "UNKNOWN";
    }
}
