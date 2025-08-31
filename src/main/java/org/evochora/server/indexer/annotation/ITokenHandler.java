package org.evochora.server.indexer.annotation;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.server.contracts.raw.RawOrganismState;

/**
 * Interface for token handlers that analyze specific types of tokens.
 * Each handler is responsible for a specific token classification.
 * 
 * <p>Handlers now receive deterministic TokenInfo from the compiler's TokenMap,
 * eliminating the need for guessing token types and scopes.</p>
 */
public interface ITokenHandler {
    
    /**
     * Determines if this handler can process the given token.
     * 
     * @param token The token to analyze
     * @param lineNumber The line number where the token appears
     * @param artifact The program artifact containing IR data
     * @param tokenInfo The deterministic token information from the compiler's TokenMap
     * @return true if this handler can process the token, false otherwise
     */
    boolean canHandle(String token, int lineNumber, ProgramArtifact artifact, TokenInfo tokenInfo);
    
    /**
     * Analyzes the token and returns annotation information.
     * 
     * @param token The token to analyze
     * @param lineNumber The line number where the token appears
     * @param artifact The program artifact containing IR data
     * @param tokenInfo The deterministic token information from the compiler's TokenMap
     * @param o The raw organism state for runtime values
     * @return TokenAnalysisResult with annotation information, or null if no annotation needed
     */
    TokenAnalysisResult analyze(String token, int lineNumber, ProgramArtifact artifact, TokenInfo tokenInfo, RawOrganismState o);
}
