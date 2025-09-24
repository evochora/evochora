package org.evochora.server.indexer.annotation.handlers;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.server.contracts.raw.SerializableProcFrame;
import org.evochora.server.indexer.annotation.ITokenHandler;
import org.evochora.server.indexer.annotation.TokenAnalysisResult;
import org.evochora.server.indexer.annotation.enums.TokenType;

import java.util.Deque;

/**
 * Handles RET instruction tokens.
 * Shows the return address coordinates from the call stack.
 * 
 * Future enhancements:
 * - Show compiler-generated copy-out instructions
 * - Display register restoration details
 * - Show stack frame cleanup information
 */
public class RetInstructionHandler implements ITokenHandler {
    
    @Override
    public boolean canHandle(String token, int lineNumber, String fileName, ProgramArtifact artifact, TokenInfo tokenInfo) {
        // Only handle RET tokens
        return "RET".equalsIgnoreCase(token);
    }
    
    @Override
    public TokenAnalysisResult analyze(String token, int lineNumber, ProgramArtifact artifact, TokenInfo tokenInfo, RawOrganismState o) {
        if (!"RET".equalsIgnoreCase(token)) {
            return null;
        }
        
        // Get the return address from the call stack
        if (o.callStack() != null && !o.callStack().isEmpty()) {
            SerializableProcFrame topFrame = o.callStack().peek();
            if (topFrame.absoluteReturnIp() != null) {
                // Format return address as coordinates
                String annotationText = formatCoordinates(topFrame.absoluteReturnIp());
                return new TokenAnalysisResult(token, TokenType.RET_INSTRUCTION, annotationText, "ret");
            }
        }
        
        // No return address found - return null (no annotation)
        return null;
    }
    
    /**
     * Formats coordinates as [X|Y] for display.
     */
    private String formatCoordinates(int[] coords) {
        if (coords == null || coords.length == 0) {
            return null;
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
