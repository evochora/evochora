package org.evochora.datapipeline.services.debugindexer.annotation.handlers;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.datapipeline.services.debugindexer.annotation.ITokenHandler;
import org.evochora.datapipeline.services.debugindexer.annotation.TokenAnalysisResult;
import org.evochora.datapipeline.services.debugindexer.annotation.enums.TokenType;

/**
 * Handles tokens that are procedure parameters.
 * Creates annotations showing parameter bindings and values.
 */
public class ParameterTokenHandler implements ITokenHandler {
    
    @Override
    public boolean canHandle(String token, int lineNumber, String fileName, ProgramArtifact artifact, TokenInfo tokenInfo) {
        if (tokenInfo == null) {
            return false;
        }
        
        return tokenInfo.tokenType() == org.evochora.compiler.frontend.semantics.Symbol.Type.VARIABLE;
    }
    
    @Override
    public TokenAnalysisResult analyze(String token, int lineNumber, ProgramArtifact artifact, TokenInfo tokenInfo, RawOrganismState o) {
        // Simplified implementation - show parameter value
        String annotationText = "param";
        return new TokenAnalysisResult(token, TokenType.PARAMETER_NAME, annotationText, "param");
    }
}
