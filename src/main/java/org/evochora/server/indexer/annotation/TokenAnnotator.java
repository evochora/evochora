package org.evochora.server.indexer.annotation;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.server.indexer.annotation.handlers.LabelTokenHandler;
import org.evochora.server.indexer.annotation.handlers.RegisterTokenHandler;
import org.evochora.server.indexer.annotation.handlers.ParameterTokenHandler;
import org.evochora.server.indexer.annotation.handlers.CallInstructionHandler;
import org.evochora.server.indexer.annotation.handlers.RetInstructionHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Main engine for token-level annotation that coordinates handlers using deterministic
 * TokenMap data from the compiler.
 * 
 * <p>This class eliminates the need for guessing token types by using the compiler-generated
 * TokenMap to provide accurate, deterministic token classification.</p>
 */
public class TokenAnnotator {
    
    private final List<ITokenHandler> handlers;
    
    public TokenAnnotator() {
        this.handlers = new ArrayList<>();
        registerDefaultHandlers();
    }
    
    /**
     * Registers the default set of token handlers.
     */
    private void registerDefaultHandlers() {
        handlers.add(new LabelTokenHandler());
        handlers.add(new RegisterTokenHandler());
        handlers.add(new ParameterTokenHandler());
        handlers.add(new CallInstructionHandler());
        handlers.add(new RetInstructionHandler());
    }
    
    /**
     * Adds a custom token handler.
     * 
     * @param handler The handler to add
     */
    public void addHandler(ITokenHandler handler) {
        handlers.add(handler);
    }
    
    /**
     * Analyzes all tokens on a specific line using the deterministic TokenMap data.
     * 
     * @param fileName The source file name to analyze
     * @param lineNumber The line number to analyze
     * @param artifact The program artifact containing TokenMap data
     * @param organismState The raw organism state for runtime values
     * @return List of token annotations for the line
     */
    public List<TokenAnnotation> analyzeLine(String fileName, int lineNumber, ProgramArtifact artifact, RawOrganismState organismState) {
        List<TokenAnnotation> annotations = new ArrayList<>();
        
        // Use the precise file-line-column lookup structure
        Map<Integer, Map<Integer, List<TokenInfo>>> fileTokens = artifact.tokenLookup().get(fileName);
        if (fileTokens == null) {
            return annotations; // No tokens for this file
        }
        
        Map<Integer, List<TokenInfo>> lineTokensMap = fileTokens.get(lineNumber);
        if (lineTokensMap == null) {
            return annotations; // No tokens on this line
        }
        
        // Process each column position separately to preserve column information
        for (Map.Entry<Integer, List<TokenInfo>> columnEntry : lineTokensMap.entrySet()) {
            int column = columnEntry.getKey();
            List<TokenInfo> tokensAtColumn = columnEntry.getValue();
            
            // Analyze each token at this column position
            for (TokenInfo tokenInfo : tokensAtColumn) {
                String tokenText = tokenInfo.tokenText();
                
                // Find the appropriate handler for this token
                ITokenHandler handler = findHandler(tokenText, lineNumber, fileName, artifact, tokenInfo);
                if (handler != null) {
                    TokenAnalysisResult result = handler.analyze(tokenText, lineNumber, artifact, tokenInfo, organismState);
                    if (result != null) {
                        annotations.add(new TokenAnnotation(tokenText, result.annotationText(), result.kind(), column));
                    }
                }
            }
        }
        
        return annotations;
    }
    
    /**
     * Finds the appropriate handler for a given token.
     * 
     * @param token The token text
     * @param lineNumber The line number
     * @param fileName The source file name
     * @param artifact The program artifact
     * @param tokenInfo The deterministic token information
     * @return The appropriate handler, or null if none found
     */
    private ITokenHandler findHandler(String token, int lineNumber, String fileName, ProgramArtifact artifact, TokenInfo tokenInfo) {
        for (ITokenHandler handler : handlers) {
            if (handler.canHandle(token, lineNumber, fileName, artifact, tokenInfo)) {
                return handler;
            }
        }
        return null;
    }
    
}
