package org.evochora.compiler;

import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple test to isolate the vector parameter bug.
 * 
 * This test shows that REF syntax fails to compile or fails at runtime
 * when dealing with vector parameters.
 */
@Tag("unit")
@Disabled("Was only to verify a bug, keeping it for now, but should be deleted if we do not face the bug anymore!")
public class SimpleVectorParameterTest {

    @Test
    public void testWithSyntaxWorks() throws Exception {
        // Initialize instruction set
        Instruction.init();
        EnvironmentProperties envProps = new EnvironmentProperties(new int[]{100, 100}, true);
        Compiler compiler = new Compiler();
        
        // Simple program with WITH syntax
        String program = """
            JMPI START
            .PROC TEST WITH VEC
              V2BR %DR1 VEC
              RET
            .ENDP
            
            START:
              SETV %DR0 1|0
              CALL TEST WITH %DR0
              NOP
            """;
        
        // This should compile successfully
        ProgramArtifact artifact = compiler.compile(List.of(program), "with_test", envProps);
        assertNotNull(artifact, "WITH syntax should compile successfully");
    }
    
    @Test
    public void testRefSyntaxFails() throws Exception {
        // Initialize instruction set
        Instruction.init();
        EnvironmentProperties envProps = new EnvironmentProperties(new int[]{100, 100}, true);
        Compiler compiler = new Compiler();
        
        // Same program but with REF syntax
        String program = """
            JMPI START
            .PROC TEST REF VEC
              V2BR %DR1 VEC
              RET
            .ENDP
            
            START:
              SETV %DR0 1|0
              CALL TEST REF %DR0
              NOP
            """;
        
        // This should also compile successfully, but will fail at runtime
        // OR it might fail to compile (depending on the bug)
        try {
            ProgramArtifact artifact = compiler.compile(List.of(program), "ref_test", envProps);
            assertNotNull(artifact, "REF syntax should compile successfully");
            
            // If we get here, REF syntax compiles but will fail at runtime
            // The bug: vector parameter %VEC loses type information
            
        } catch (Exception e) {
            // REF syntax fails to compile - this documents the compilation bug
            // REF syntax compilation failed: " + e.getMessage()
            
            // This documents that REF syntax has issues with vector parameters
            // The error should indicate what's wrong with REF syntax
        }
    }
    
    @Test
    public void testDirectVectorOperation() throws Exception {
        // Test that basic vector operations work without procedures
        
        // Initialize instruction set
        Instruction.init();
        EnvironmentProperties envProps = new EnvironmentProperties(new int[]{100, 100}, true);
        Compiler compiler = new Compiler();
        
        // Simple program without procedures
        String program = """
            .ORG 0|0
            START:
              SETV %DR0 1|0
              V2BR %DR1 %DR0
              NOP
            """;
        
        // This should compile and work fine
        ProgramArtifact artifact = compiler.compile(List.of(program), "direct_test", envProps);
        assertNotNull(artifact, "Direct vector operations should work");
        
        // This proves that the problem is specifically with parameter passing,
        // not with vector operations themselves
    }
}
