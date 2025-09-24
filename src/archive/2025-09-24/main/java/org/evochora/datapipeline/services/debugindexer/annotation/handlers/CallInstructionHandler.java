package org.evochora.datapipeline.services.debugindexer.annotation.handlers;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.datapipeline.services.debugindexer.annotation.ITokenHandler;
import org.evochora.datapipeline.services.debugindexer.annotation.TokenAnalysisResult;
import org.evochora.datapipeline.services.debugindexer.annotation.enums.TokenType;

/**
 * Handles CALL instruction tokens.
 * Creates annotations for procedure calls.
 */
public class CallInstructionHandler implements ITokenHandler {
    
    @Override
    public boolean canHandle(String token, int lineNumber, String fileName, ProgramArtifact artifact, TokenInfo tokenInfo) {
        return "CALL".equalsIgnoreCase(token);
    }
    
    @Override
    public TokenAnalysisResult analyze(String token, int lineNumber, ProgramArtifact artifact, TokenInfo tokenInfo, RawOrganismState o) {
        String annotationText = "call";
        return new TokenAnalysisResult(token, TokenType.CALL_INSTRUCTION, annotationText, "call");
    }
}
