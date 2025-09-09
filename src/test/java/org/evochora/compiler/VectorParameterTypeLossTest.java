package org.evochora.compiler;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Disabled;

/**
 * Isolates the vector parameter type loss bug in REF vs WITH syntax.
 * 
 * This test demonstrates that REF syntax loses type information for vector parameters,
 * causing runtime errors when vector operations are performed on parameters that
 * are treated as DATA instead of VECTOR.
 */
@Tag("unit")
@Disabled("Redundant, detects the same bug as RedWithParameterBugTest")
public class VectorParameterTypeLossTest {

    @Test
    public void testVectorParameterTypeLoss() throws Exception {
        // Initialize instruction set
        Instruction.init();
        EnvironmentProperties envProps = new EnvironmentProperties(new int[]{100, 100}, true);
        Compiler compiler = new Compiler();
        
        // Test program that isolates the vector parameter issue
        // This program should work with both WITH and REF syntax
        String testProgram = """
            .REG %DIR %DR1
            .REG %RESULT %DR2
            
            JMPI START
            
            .PROC VECTOR_TEST WITH DIR
              # This should work: %DIR is a vector parameter
              V2BR %RESULT DIR
              RET
            .ENDP
            
            START:
              SETV %DIR 1|0
              CALL VECTOR_TEST WITH %DIR
              NOP
            """;
        
        // Test WITH syntax (should work)
        ProgramArtifact withArtifact = compiler.compile(List.of(testProgram), "with_test", envProps);
        assertNotNull(withArtifact, "WITH syntax should compile successfully");
        
        // Now test REF syntax (should work but currently fails at runtime)
        String refProgram = """
            .REG %DIR %DR1
            .REG %RESULT %DR2
            
            JMPI START
            
            .PROC VECTOR_TEST REF DIR
              # This should work: %DIR is a vector parameter
              V2BR %RESULT DIR
              RET
            .ENDP
            
            START:
              SETV %DIR 1|0
              CALL VECTOR_TEST REF %DIR
              NOP
            """;
        
        // REF syntax should also compile successfully
        ProgramArtifact refArtifact = compiler.compile(List.of(refProgram), "ref_test", envProps);
        assertNotNull(refArtifact, "REF syntax should compile successfully");
        
        // Both should compile, but REF will fail at runtime with:
        // "V2B requires a unit vector with single non-zero component of magnitude 1"
        // This happens because %DIR loses its vector type information
        
        // The bug: REF parameters are copied from DR registers to FPR registers
        // During this copy, the type information (VECTOR vs DATA) is lost
        // The runtime then treats the vector parameter as DATA, causing the error
    }
    
    @Test
    public void testMinimalVectorParameterBug() throws Exception {
        // Even more minimal test to isolate the issue
        
        // Initialize instruction set
        Instruction.init();
        EnvironmentProperties envProps = new EnvironmentProperties(new int[]{100, 100}, true);
        Compiler compiler = new Compiler();
        
        // Minimal program: just a vector parameter and V2BR operation
        String minimalProgram = """
            JMPI START
            
            .PROC MINIMAL_TEST WITH VEC
              V2BR %DR1 VEC
              RET
            .ENDP
            
            START:
              SETV %DR0 1|0
              CALL MINIMAL_TEST WITH %DR0
              NOP
            """;
        
        // This should compile successfully
        ProgramArtifact artifact = compiler.compile(List.of(minimalProgram), "minimal_test", envProps);
        assertNotNull(artifact, "Minimal program should compile successfully");
        
        // The issue: When we change WITH to REF, the vector parameter %VEC
        // loses its type information and V2BR fails at runtime
        
        // This demonstrates that the problem is specifically in parameter marshalling
        // for REF syntax, not in the basic vector operations themselves
    }
    
    @Test
    public void testParameterMarshallingDifference() throws Exception {
        // This test documents the exact difference between WITH and REF parameter handling
        
        // Initialize instruction set
        Instruction.init();
        EnvironmentProperties envProps = new EnvironmentProperties(new int[]{100, 100}, true);
        Compiler compiler = new Compiler();
        
        // WITH syntax: Parameters are resolved directly to %FPRx
        String withProgram = """
            JMPI START
            
            .PROC WITH_TEST WITH PARAM
              # %PARAM is directly %FPR0, type information preserved
              V2BR %DR1 PARAM
              RET
            .ENDP
            
            START:
              SETV %DR0 1|0
              CALL WITH_TEST WITH %DR0
              NOP
            """;
        
        // REF syntax: Parameters are copied from %DRx to %FPRx
        String refProgram = """
            JMPI START
            
            .PROC REF_TEST REF PARAM
              # %PARAM is copied from %DR0 to %FPR0, type information lost
              V2BR %DR1 PARAM
              RET
            .ENDP
            
            START:
              SETV %DR0 1|0
              CALL REF_TEST REF %DR0
              NOP
            """;
        
        // Both should compile successfully
        ProgramArtifact withArtifact = compiler.compile(List.of(withProgram), "with_test", envProps);
        ProgramArtifact refArtifact = compiler.compile(List.of(refProgram), "ref_test", envProps);
        
        assertNotNull(withArtifact, "WITH syntax should compile successfully");
        assertNotNull(refArtifact, "REF syntax should compile successfully");
        
        // The bug: REF syntax copies the value but loses the type information
        // WITH syntax preserves both value and type information
        
        // This test documents the exact mechanism of the bug:
        // 1. WITH: Direct resolution to %FPRx (type preserved)
        // 2. REF: Copy from %DRx to %FPRx (type lost)
    }
}

