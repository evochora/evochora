package org.evochora.datapipeline.services.debugindexer.annotation.handlers;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.datapipeline.services.debugindexer.annotation.ITokenHandler;
import org.evochora.datapipeline.services.debugindexer.annotation.TokenAnalysisResult;
import org.evochora.datapipeline.services.debugindexer.annotation.enums.TokenType;

import java.util.Map;
import java.util.List;

/**
 * Handles tokens that are label references.
 * Creates jump target annotations for labels used in instructions.
 * Ignores label definitions (lines ending with ":").
 * 
 * <p>Now uses deterministic TokenInfo from the compiler's TokenMap to accurately
 * identify label tokens and their scope.</p>
 */
public class LabelTokenHandler implements ITokenHandler {
    
    @Override
    public boolean canHandle(String token, int lineNumber, String fileName, ProgramArtifact artifact, TokenInfo tokenInfo) {
        // Use deterministic TokenInfo to check if this is a label or procedure token
        if (tokenInfo == null) {
            return false;
        }
        
        // Handle LABEL type tokens for jump target annotations
        if (tokenInfo.tokenType() == org.evochora.compiler.frontend.semantics.Symbol.Type.LABEL) {
            return true;
        }
        
        // Handle PROCEDURE type tokens, but only if they're being referenced (not defined)
        if (tokenInfo.tokenType() == org.evochora.compiler.frontend.semantics.Symbol.Type.PROCEDURE) {
            // Check if this procedure name is actually being referenced by looking for CALL instructions
            // on the same line or checking if it's in a CALL context
            return isProcedureReference(token, lineNumber, fileName, artifact, tokenInfo);
        }
        
        return false;
    }
    
    /**
     * Determines if a PROCEDURE type token is being referenced (called) rather than defined.
     * This prevents procedure definitions from getting jump target annotations.
     */
    private boolean isProcedureReference(String token, int lineNumber, String fileName, ProgramArtifact artifact, TokenInfo tokenInfo) {
        // Get all tokens on this line using the provided fileName
        Map<Integer, Map<Integer, List<TokenInfo>>> fileTokens = artifact.tokenLookup().get(fileName);
        if (fileTokens == null) return false;
        
        Map<Integer, List<TokenInfo>> lineTokens = fileTokens.get(lineNumber);
        if (lineTokens == null) return false;
        
        // Check if there's a CALL instruction on this line
        for (List<TokenInfo> columnTokens : lineTokens.values()) {
            for (TokenInfo columnToken : columnTokens) {
                if ("CALL".equalsIgnoreCase(columnToken.tokenText())) {
                    // This line contains a CALL instruction, so the procedure token is being referenced
                    return true;
                }
            }
        }
        
        // No CALL instruction found, so this is likely a procedure definition
        return false;
    }
    
    @Override
    public TokenAnalysisResult analyze(String token, int lineNumber, ProgramArtifact artifact, TokenInfo tokenInfo, RawOrganismState o) {
        // Handle qualified names like LIB.PROC1 by extracting the actual procedure name
        String actualName = extractActualName(token);
        
        // Find the label's address
        Integer labelAddress = artifact.labelAddressToName().entrySet().stream()
            .filter(entry -> actualName.equalsIgnoreCase(entry.getValue()))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(null);
            
        if (labelAddress != null) {
            // Get the label's coordinates
            int[] coords = artifact.linearAddressToCoord().get(labelAddress);
            if (coords != null) {
                // Create jump target annotation
                String annotationText = formatCoordinates(coords);
                return new TokenAnalysisResult(token, TokenType.LABEL_REFERENCE, annotationText, "label");
            }
        }
        
        // No coordinates found - no annotation
        return null;
    }
    
    /**
     * Extracts the actual procedure/label name from a qualified name.
     * For example, "LIB.PROC1" becomes "PROC1".
     */
    private String extractActualName(String qualifiedName) {
        if (qualifiedName == null) {
            return null;
        }
        
        // If the name contains a dot, extract the part after the last dot
        int lastDotIndex = qualifiedName.lastIndexOf('.');
        if (lastDotIndex >= 0 && lastDotIndex < qualifiedName.length() - 1) {
            return qualifiedName.substring(lastDotIndex + 1);
        }
        
        // No dot found, return the original name
        return qualifiedName;
    }
    
    /**
     * Formats coordinates as [X|Y] for display.
     */
    private String formatCoordinates(int[] coords) {
        if (coords == null || coords.length == 0) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        // Note: Frontend will add brackets, so don't include them here
        for (int i = 0; i < coords.length; i++) {
            if (i > 0) {
                sb.append("|");
            }
            sb.append(coords[i]);
        }
        
        return sb.toString();
    }
}
