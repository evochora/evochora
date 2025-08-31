package org.evochora.server.indexer.annotation.handlers;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.server.indexer.annotation.ITokenHandler;
import org.evochora.server.indexer.annotation.TokenAnalysisResult;
import org.evochora.server.indexer.annotation.enums.TokenType;

import java.util.Map;

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
    public boolean canHandle(String token, int lineNumber, ProgramArtifact artifact, TokenInfo tokenInfo) {
        // Use deterministic TokenInfo to check if this is a label token
        if (tokenInfo == null) {
            return false;
        }
        
        // Only handle LABEL type tokens that are references (not definitions)
        return tokenInfo.tokenType() == org.evochora.compiler.frontend.semantics.Symbol.Type.LABEL;
    }
    
    @Override
    public TokenAnalysisResult analyze(String token, int lineNumber, ProgramArtifact artifact, TokenInfo tokenInfo, RawOrganismState o) {
        // Find the label's address
        Integer labelAddress = artifact.labelAddressToName().entrySet().stream()
            .filter(entry -> token.equalsIgnoreCase(entry.getValue()))
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
     * Formats coordinates as [X|Y] for display.
     */
    private String formatCoordinates(int[] coords) {
        if (coords == null || coords.length == 0) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < coords.length; i++) {
            if (i > 0) {
                sb.append("|");
            }
            sb.append(coords[i]);
        }
        sb.append("]");
        
        return sb.toString();
    }
}
