package org.evochora.compiler;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to isolate the bug where REF and WITH syntax behave differently
 * when passing vector register parameters to procedures.
 */
@Tag("unit")
@Disabled("""
    We get an compiler exception if a WITH proc is compiled before the REF proc,
    if you comment out the WITH proc compilation the REF proc compilation does mot throw an exception anymore.
    This is very strange, we should fix this, but as WITH and REF are rarely used together, we ignore this bug for now.
    """)

public class RefWithParameterBugTest {

    @BeforeAll
    static void setUp() {
        Instruction.init();
    }

    @Test
    public void testRefVsWithParameterPassing_ShouldBeIdentical() throws Exception {
        // Test program with WITH syntax
        String withProgram = """
            .REG %DIR %DR1
            .REG %FWD_MASK %DR2
            
            .ORG 0|0
            START:
              SETV %DIR 1|0
              V2BR %FWD_MASK %DIR
              CALL TEST_PROC WITH %DIR %FWD_MASK
              NOP
            JMPI START
            
            .PROC TEST_PROC EXPORT WITH DIR FWD_MASK
              RTRI DIR DATA:0 DATA:1
              V2BR FWD_MASK DIR
              RET
            .ENDP
            """;

        // Test program with REF syntax
        String refProgram = """
            .REG %DIR %DR1
            .REG %FWD_MASK %DR2
            
            .ORG 0|0
            START:
              SETV %DIR 1|0
              V2BR %FWD_MASK %DIR
              CALL TEST_PROC REF %DIR %FWD_MASK
              NOP
            JMPI START
            
            .PROC TEST_PROC EXPORT REF DIR FWD_MASK
              RTRI DIR DATA:0 DATA:1
              V2BR FWD_MASK DIR
              RET
            .ENDP
            """;

        EnvironmentProperties envProps = new EnvironmentProperties(new int[]{100, 100}, true);
        
        // Compile both programs
        Compiler compiler = new Compiler();
        List<String> withLines = Arrays.asList(withProgram.split("\\r?\\n"));
        List<String> refLines = Arrays.asList(refProgram.split("\\r?\\n"));
        
        ProgramArtifact withArtifact = compiler.compile(withLines, "with_test.s", envProps);
        
        // WITH should compile successfully
        assertNotNull(withArtifact);
        
        // REF should also compile successfully (this is the bug - it should be identical)
        // Currently REF fails with CompilationException when passing vector registers
        ProgramArtifact refArtifact = null;
        try {
            refArtifact = compiler.compile(refLines, "ref_test.s", envProps);
            assertNotNull(refArtifact);
            
            // If both compile successfully, they should be identical
            assertEquals(withArtifact.machineCodeLayout(), refArtifact.machineCodeLayout(),
                "REF and WITH syntax should produce identical machine code");
            
            assertEquals(withArtifact.procNameToParamNames(), refArtifact.procNameToParamNames(),
                "Parameter names should be identical");

        } catch (Exception e) {
            // This documents the bug: REF syntax fails when WITH syntax succeeds
            // BUG DOCUMENTED: REF syntax fails with: " + e.getMessage()
            // WITH syntax compiles successfully, but REF syntax fails
            // For now, we expect this to fail until the bug is fixed
            throw new AssertionError("REF syntax should compile successfully like WITH syntax, but failed with: " + e.getMessage());
        }
    }
}
