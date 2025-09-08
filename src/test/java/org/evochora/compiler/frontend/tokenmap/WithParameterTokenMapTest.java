package org.evochora.compiler.frontend.tokenmap;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.CompilerTestBase;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.TokenInfo;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// TODO: This test was not migrated to REF/VAL syntax due to a compiler bug that prevents REF parameters from being added to the token map.
@Tag("unit")
@Tag("legacy-with")
class WithParameterTokenMapTest extends CompilerTestBase {

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void testProcedureParametersAreInTokenMap() throws Exception {
        String source = ".PROC PROC1 EXPORT WITH PROC1REG1 PROC1REG2\n  NOP\n  RET\n.ENDP";
        
        Compiler compiler = new Compiler();
        ProgramArtifact artifact = compiler.compile(List.of(source.split("\n")), "test_proc.s", testEnvProps);
        
        assertNotNull(artifact.tokenMap(), "TokenMap should exist");
        assertTrue(artifact.tokenMap().size() > 0, "TokenMap should contain tokens");
        
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
        
        assertTrue(foundProc1, "Procedure name PROC1 should be in TokenMap");
        assertTrue(foundProc1Reg1, "Parameter PROC1REG1 should be in TokenMap");
        assertTrue(foundProc1Reg2, "Parameter PROC1REG2 should be in TokenMap");
    }
}
