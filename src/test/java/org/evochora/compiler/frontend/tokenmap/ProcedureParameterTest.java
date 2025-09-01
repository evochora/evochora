package org.evochora.compiler.frontend.tokenmap;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to understand how procedure parameters are handled in the token map system.
 * This test will help us understand the current behavior before making changes.
 */
@Tag("unit")
class ProcedureParameterTest {

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void testProcedureParametersAreInTokenMap() throws Exception {
        // Simple procedure with parameters
        String source = ".PROC PROC1 EXPORT WITH PROC1REG1 PROC1REG2\n  NOP\n  RET\n.ENDP";
        
        Compiler compiler = new Compiler();
        ProgramArtifact artifact = compiler.compile(List.of(source.split("\n")), "test_proc.s");
        
        // Verify that procedure parameters are correctly stored in TokenMap
        assertNotNull(artifact.tokenMap(), "TokenMap should exist");
        assertTrue(artifact.tokenMap().size() > 0, "TokenMap should contain tokens");
        
        // Check if procedure parameters are in the token map
        boolean foundProc1 = false;
        boolean foundProc1Reg1 = false;
        boolean foundProc1Reg2 = false;
        
        for (TokenInfo tokenInfo : artifact.tokenMap().values()) {
            String tokenText = tokenInfo.tokenText();
            if ("PROC1".equals(tokenText)) {
                foundProc1 = true;
            } else if ("PROC1REG1".equals(tokenText)) {
                foundProc1Reg1 = true;
            } else if ("PROC1REG2".equals(tokenText)) {
                foundProc1Reg2 = true;
            }
        }
        
        // Verify that all expected tokens are found
        assertTrue(foundProc1, "Procedure name PROC1 should be in TokenMap");
        assertTrue(foundProc1Reg1, "Parameter PROC1REG1 should be in TokenMap");
        assertTrue(foundProc1Reg2, "Parameter PROC1REG2 should be in TokenMap");
    }
}
