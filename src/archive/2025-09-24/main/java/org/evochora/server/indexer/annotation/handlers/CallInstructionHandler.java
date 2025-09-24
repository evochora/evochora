package org.evochora.server.indexer.annotation.handlers;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.server.indexer.annotation.ITokenHandler;
import org.evochora.server.indexer.annotation.TokenAnalysisResult;
import org.evochora.server.indexer.annotation.enums.TokenType;

/**
 * Handles CALL instruction tokens.
 * Currently a placeholder for future compiler-generated copy-in/out instructions.
 * 
 * Future enhancements:
 * - Show automatic parameter copying instructions
 * - Display register state changes during call
 * - Show stack frame setup details
 */
public class CallInstructionHandler implements ITokenHandler {
    
    @Override
    public boolean canHandle(String token, int lineNumber, String fileName, ProgramArtifact artifact, TokenInfo tokenInfo) {
        // Only handle CALL tokens
        return "CALL".equalsIgnoreCase(token);
    }
    
    @Override
    public TokenAnalysisResult analyze(String token, int lineNumber, ProgramArtifact artifact, TokenInfo tokenInfo, RawOrganismState o) {
        if (!"CALL".equalsIgnoreCase(token)) {
            return null;
        }
        
        // TODO: Future enhancement - show compiler-generated copy-in/out instructions
        // For now, return null to avoid [call] annotation
        // Later this could show: [copy-in: %DR0→%FPR0, %DR1→%FPR1]
        
        return null; // No annotation for CALL instruction itself
    }
}
