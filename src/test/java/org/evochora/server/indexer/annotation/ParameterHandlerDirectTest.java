package org.evochora.server.indexer.annotation;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.server.indexer.annotation.handlers.ParameterTokenHandler;
import org.evochora.server.indexer.annotation.TokenAnalysisResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct test of ParameterTokenHandler to see if it's working correctly.
 */
@Tag("unit")
class ParameterHandlerDirectTest {

    @BeforeAll
    static void init() {
        org.evochora.runtime.isa.Instruction.init();
    }

    @Test
    void testParameterTokenHandlerDirectly() throws Exception {
        // Simple procedure with parameters
        String source = ".PROC PROC1 EXPORT WITH PROC1REG1 PROC1REG2\n  NOP\n  RET\n.ENDP";
        
        Compiler compiler = new Compiler();
        ProgramArtifact artifact = compiler.compile(List.of(source.split("\n")), "test_proc.s");
        
        ParameterTokenHandler handler = new ParameterTokenHandler();
        
        // Test that ParameterTokenHandler can handle procedure parameters
        for (Map.Entry<org.evochora.compiler.api.SourceInfo, TokenInfo> entry : artifact.tokenMap().entrySet()) {
            TokenInfo tokenInfo = entry.getValue();
            String tokenText = tokenInfo.tokenText();
            
            if (tokenText.startsWith("PROC1REG")) {
                // Test canHandle directly
                boolean canHandle = handler.canHandle(tokenText, 1, "test_proc.s", artifact, tokenInfo);
                assertTrue(canHandle, "ParameterTokenHandler should handle parameter: " + tokenText);
                
                if (canHandle) {
                    // Test analyze directly (with null organism state for now)
                    TokenAnalysisResult result = handler.analyze(tokenText, 1, artifact, tokenInfo, null);
                    // Result can be null without call stack context, which is expected
                }
            }
        }
        
        // Verify that we can compile and get tokens
        assertNotNull(artifact.tokenMap(), "TokenMap should exist");
        assertTrue(artifact.tokenMap().size() > 0, "TokenMap should contain tokens");
        
        // Verify that at least one parameter token was found and tested
        boolean foundParameter = artifact.tokenMap().values().stream()
            .anyMatch(tokenInfo -> tokenInfo.tokenText().startsWith("PROC1REG"));
        assertTrue(foundParameter, "Should find at least one procedure parameter token to test");
    }
}
