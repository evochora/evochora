package org.evochora.server.indexer.annotation;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.compiler.frontend.semantics.Symbol;
import org.evochora.server.indexer.annotation.handlers.ParameterTokenHandler;
import org.evochora.server.indexer.annotation.TokenAnalysisResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ParameterTokenHandler to ensure it correctly identifies and processes procedure parameters.
 */
@Tag("unit")
class ParameterTokenHandlerTest {
    
    @Test
    void testCanHandleProcedureParameter() {
        ParameterTokenHandler handler = new ParameterTokenHandler();
        
        // Create a TokenInfo for a procedure parameter
        TokenInfo paramTokenInfo = new TokenInfo("PARAM1", Symbol.Type.VARIABLE, "PROC1");
        
        // Should handle VARIABLE type tokens in procedure scope (not global)
        assertTrue(handler.canHandle("PARAM1", 1, "test.s", null, paramTokenInfo));
        
        // Should NOT handle VARIABLE type tokens in global scope
        TokenInfo globalTokenInfo = new TokenInfo("GLOBAL_VAR", Symbol.Type.VARIABLE, "global");
        assertFalse(handler.canHandle("GLOBAL_VAR", 1, "test.s", null, globalTokenInfo));
        
        // Should NOT handle other token types
        TokenInfo labelTokenInfo = new TokenInfo("LABEL", Symbol.Type.LABEL, "PROC1");
        assertFalse(handler.canHandle("LABEL", 1, "test.s", null, labelTokenInfo));
    }
    
    @Test
    void testCanHandleWithNullTokenInfo() {
        ParameterTokenHandler handler = new ParameterTokenHandler();
        
        // Should not handle null TokenInfo
        assertFalse(handler.canHandle("PARAM1", 1, "test.s", null, null));
    }
}
