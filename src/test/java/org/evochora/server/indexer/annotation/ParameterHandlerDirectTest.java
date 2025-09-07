package org.evochora.server.indexer.annotation;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.server.indexer.annotation.handlers.ParameterTokenHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

// TODO: This test was not migrated to REF/VAL syntax due to a compiler bug that prevents REF parameters from being added to the token map.
@Tag("unit")
@Tag("legacy-with")
class ParameterHandlerDirectTest {

    private final EnvironmentProperties testEnvProps = new EnvironmentProperties(new int[]{100, 100}, true);

    @BeforeAll
    static void init() {
        org.evochora.runtime.isa.Instruction.init();
    }

    @Test
    void testParameterTokenHandlerDirectly() throws Exception {
        String source = ".PROC PROC1 EXPORT WITH PROC1REG1 PROC1REG2\n  NOP\n  RET\n.ENDP";
        
        Compiler compiler = new Compiler();
        ProgramArtifact artifact = compiler.compile(List.of(source.split("\n")), "test_proc.s", testEnvProps);
        
        ParameterTokenHandler handler = new ParameterTokenHandler();
        
        for (Map.Entry<org.evochora.compiler.api.SourceInfo, TokenInfo> entry : artifact.tokenMap().entrySet()) {
            TokenInfo tokenInfo = entry.getValue();
            String tokenText = tokenInfo.tokenText();
            
            if (tokenText.startsWith("PROC1REG")) {
                boolean canHandle = handler.canHandle(tokenText, 1, "test_proc.s", artifact, tokenInfo);
                assertTrue(canHandle, "ParameterTokenHandler should handle parameter: " + tokenText);
                
                if (canHandle) {
                    TokenAnalysisResult result = handler.analyze(tokenText, 1, artifact, tokenInfo, null);
                }
            }
        }
        
        assertNotNull(artifact.tokenMap(), "TokenMap should exist");
        assertTrue(artifact.tokenMap().size() > 0, "TokenMap should contain tokens");
        
        boolean foundParameter = artifact.tokenMap().values().stream()
            .anyMatch(tokenInfo -> tokenInfo.tokenText().startsWith("PROC1REG"));
        assertTrue(foundParameter, "Should find at least one procedure parameter token to test");
    }
}
