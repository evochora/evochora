package org.evochora.server.indexer.annotation;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.server.contracts.raw.SerializableProcFrame;
import org.evochora.server.indexer.annotation.handlers.ParameterTokenHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for REF/VAL parameter annotation functionality.
 * These tests ensure that the new REF/VAL syntax works correctly with parameter annotations.
 */
@Tag("unit")
class RefValParameterAnnotationTest {

    private final EnvironmentProperties testEnvProps = new EnvironmentProperties(new int[]{100, 100}, true);

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void testRefParameterInTokenMap() throws Exception {
        String source = String.join("\n",
            ".PROC PROC1 EXPORT REF PROC1REG1 PROC1REG2",
            "  NOP",
            "  RET",
            ".ENDP"
        );
        
        Compiler compiler = new Compiler();
        ProgramArtifact artifact = compiler.compile(List.of(source.split("\n")), "test_ref_proc.s", testEnvProps);
        
        // Verify that REF parameters are in the token map
        boolean foundRefParam1 = artifact.tokenMap().values().stream()
            .anyMatch(tokenInfo -> "PROC1REG1".equals(tokenInfo.tokenText()) && "PROC1".equals(tokenInfo.scope()));
        assertTrue(foundRefParam1, "PROC1REG1 should be in token map with scope PROC1");
        
        boolean foundRefParam2 = artifact.tokenMap().values().stream()
            .anyMatch(tokenInfo -> "PROC1REG2".equals(tokenInfo.tokenText()) && "PROC1".equals(tokenInfo.scope()));
        assertTrue(foundRefParam2, "PROC1REG2 should be in token map with scope PROC1");
    }

    @Test
    void testValParameterInTokenMap() throws Exception {
        String source = String.join("\n",
            ".PROC PROC1 EXPORT VAL PROC1REG1 PROC1REG2",
            "  NOP",
            "  RET",
            ".ENDP"
        );
        
        Compiler compiler = new Compiler();
        ProgramArtifact artifact = compiler.compile(List.of(source.split("\n")), "test_val_proc.s", testEnvProps);
        
        // Verify that VAL parameters are in the token map
        boolean foundValParam1 = artifact.tokenMap().values().stream()
            .anyMatch(tokenInfo -> "PROC1REG1".equals(tokenInfo.tokenText()) && "PROC1".equals(tokenInfo.scope()));
        assertTrue(foundValParam1, "PROC1REG1 should be in token map with scope PROC1");
        
        boolean foundValParam2 = artifact.tokenMap().values().stream()
            .anyMatch(tokenInfo -> "PROC1REG2".equals(tokenInfo.tokenText()) && "PROC1".equals(tokenInfo.scope()));
        assertTrue(foundValParam2, "PROC1REG2 should be in token map with scope PROC1");
    }

    @Test
    void testRefValMixedParameterInTokenMap() throws Exception {
        String source = String.join("\n",
            ".PROC PROC1 EXPORT REF PROC1REG1 VAL PROC1REG2",
            "  NOP",
            "  RET",
            ".ENDP"
        );
        
        Compiler compiler = new Compiler();
        ProgramArtifact artifact = compiler.compile(List.of(source.split("\n")), "test_refval_proc.s", testEnvProps);
        
        // Verify that both REF and VAL parameters are in the token map
        boolean foundRefParam = artifact.tokenMap().values().stream()
            .anyMatch(tokenInfo -> "PROC1REG1".equals(tokenInfo.tokenText()) && "PROC1".equals(tokenInfo.scope()));
        assertTrue(foundRefParam, "PROC1REG1 (REF) should be in token map with scope PROC1");
        
        boolean foundValParam = artifact.tokenMap().values().stream()
            .anyMatch(tokenInfo -> "PROC1REG2".equals(tokenInfo.tokenText()) && "PROC1".equals(tokenInfo.scope()));
        assertTrue(foundValParam, "PROC1REG2 (VAL) should be in token map with scope PROC1");
    }

    @Test
    void testCallSiteBindingsForRefSyntax() throws Exception {
        String source = String.join("\n",
            ".REG %TEST1 %DR0",
            ".REG %TEST2 %DR1",
            "",
            "START:",
            "CALL PROC1 REF %TEST1 %TEST2",
            "",
            ".PROC PROC1 EXPORT REF PROC1REG1 PROC1REG2",
            "  NOP",
            "  RET",
            ".ENDP"
        );
        
        Compiler compiler = new Compiler();
        ProgramArtifact artifact = compiler.compile(List.of(source.split("\n")), "test_ref_call.s", testEnvProps);
        
        // Verify that call site bindings are created for REF syntax
        assertNotNull(artifact.callSiteBindings(), "callSiteBindings should not be null");
        assertFalse(artifact.callSiteBindings().isEmpty(), "callSiteBindings should not be empty for REF syntax");
        
        // Find the CALL instruction address and verify its bindings
        boolean foundCallBinding = false;
        for (Map.Entry<Integer, int[]> entry : artifact.callSiteBindings().entrySet()) {
            int[] bindings = entry.getValue();
            if (bindings.length == 2 && bindings[0] == 0 && bindings[1] == 1) {
                foundCallBinding = true;
                break;
            }
        }
        assertTrue(foundCallBinding, "Should find call site binding for REF parameters [0, 1]");
    }

    @Test
    void testCallSiteBindingsForRefValSyntax() throws Exception {
        String source = String.join("\n",
            ".REG %TEST1 %DR0",
            "",
            "START:",
            "CALL PROC1 REF %TEST1 VAL DATA:1",
            "",
            ".PROC PROC1 EXPORT REF PROC1REG1 VAL PROC1REG2",
            "  NOP",
            "  RET",
            ".ENDP"
        );
        
        Compiler compiler = new Compiler();
        ProgramArtifact artifact = compiler.compile(List.of(source.split("\n")), "test_refval_call.s", testEnvProps);
        
        // Verify that call site bindings are created for REF/VAL syntax
        assertNotNull(artifact.callSiteBindings(), "callSiteBindings should not be null");
        assertFalse(artifact.callSiteBindings().isEmpty(), "callSiteBindings should not be empty for REF/VAL syntax");
        
        // Find the CALL instruction address and verify its bindings
        // Only REF parameters should be in bindings (VAL literals are not)
        boolean foundCallBinding = false;
        for (Map.Entry<Integer, int[]> entry : artifact.callSiteBindings().entrySet()) {
            int[] bindings = entry.getValue();
            if (bindings.length == 1 && bindings[0] == 0) {
                foundCallBinding = true;
                break;
            }
        }
        assertTrue(foundCallBinding, "Should find call site binding for REF parameter [0] (VAL literal not included)");
    }

    @Test
    void testParameterTokenHandlerWithRefSyntax() throws Exception {
        String source = String.join("\n",
            ".PROC PROC1 EXPORT REF PROC1REG1 PROC1REG2",
            "  NOP",
            "  RET",
            ".ENDP"
        );
        
        Compiler compiler = new Compiler();
        ProgramArtifact artifact = compiler.compile(List.of(source.split("\n")), "test_ref_handler.s", testEnvProps);
        
        ParameterTokenHandler handler = new ParameterTokenHandler();
        
        // Find REF parameter tokens
        for (Map.Entry<org.evochora.compiler.api.SourceInfo, TokenInfo> entry : artifact.tokenMap().entrySet()) {
            TokenInfo tokenInfo = entry.getValue();
            String tokenText = tokenInfo.tokenText();
            
            if ("PROC1REG1".equals(tokenText) && "PROC1".equals(tokenInfo.scope())) {
                boolean canHandle = handler.canHandle(tokenText, 1, "test_ref_handler.s", artifact, tokenInfo);
                assertTrue(canHandle, "ParameterTokenHandler should handle REF parameter: " + tokenText);
                
                if (canHandle) {
                    // Test with empty call stack (should return null)
                    TokenAnalysisResult result = handler.analyze(tokenText, 0, artifact, tokenInfo, null);
                    // Without call stack context, the handler should return null
                    // This is expected behavior - parameter annotations need call stack context
                    assertNull(result, "Handler should return null without call stack context");
                }
            }
        }
    }

    @Test
    void testParameterTokenHandlerWithValSyntax() throws Exception {
        String source = String.join("\n",
            ".PROC PROC1 EXPORT VAL PROC1REG1 PROC1REG2",
            "  NOP",
            "  RET",
            ".ENDP"
        );
        
        Compiler compiler = new Compiler();
        ProgramArtifact artifact = compiler.compile(List.of(source.split("\n")), "test_val_handler.s", testEnvProps);
        
        ParameterTokenHandler handler = new ParameterTokenHandler();
        
        // Find VAL parameter tokens
        for (Map.Entry<org.evochora.compiler.api.SourceInfo, TokenInfo> entry : artifact.tokenMap().entrySet()) {
            TokenInfo tokenInfo = entry.getValue();
            String tokenText = tokenInfo.tokenText();
            
            if ("PROC1REG1".equals(tokenText) && "PROC1".equals(tokenInfo.scope())) {
                boolean canHandle = handler.canHandle(tokenText, 1, "test_val_handler.s", artifact, tokenInfo);
                assertTrue(canHandle, "ParameterTokenHandler should handle VAL parameter: " + tokenText);
                
                if (canHandle) {
                    // Test with empty call stack (should return null)
                    TokenAnalysisResult result = handler.analyze(tokenText, 0, artifact, tokenInfo, null);
                    // Without call stack context, the handler should return null
                    // This is expected behavior - parameter annotations need call stack context
                    assertNull(result, "Handler should return null without call stack context");
                }
            }
        }
    }
}
