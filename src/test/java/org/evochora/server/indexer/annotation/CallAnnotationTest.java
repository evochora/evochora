package org.evochora.server.indexer.annotation;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test to verify that CALL instructions are correctly annotated:
 * - No [call] annotation on CALL instruction
 * - Jump target coordinates on procedure name (e.g., PROC1[0|0])
 */
@Tag("unit")
class CallAnnotationTest {

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void testCallInstructionAnnotationIntegration() throws Exception {
        // Simple procedure with CALL instruction
        String source = String.join("\n",
            ".PROC PROC1 EXPORT WITH PROC1REG1 PROC1REG2",
            "  NOP",
            "  RET",
            ".ENDP",
            "",
            "START:",
            "  CALL PROC1 WITH %DR0 %DR1",
            "  JMPI START"
        );
        
        Compiler compiler = new Compiler();
        ProgramArtifact artifact = compiler.compile(List.of(source.split("\n")), "call_integration_test.s");
        
        // Create TokenAnnotator
        TokenAnnotator annotator = new TokenAnnotator();
        
        // Test the CALL line (line 7)
        String callLine = "  CALL PROC1 WITH %DR0 %DR1";
        int callLineNumber = 7;
        
        // Analyze the line (with null organism state for now)
        List<TokenAnnotation> annotations = annotator.analyzeLine("call_integration_test.s", callLineNumber, artifact, null);
        
        // Verify that CALL instruction itself has no annotation
        if (annotations != null) {
            boolean callHasAnnotation = annotations.stream()
                .anyMatch(a -> "CALL".equals(a.token()));
            assertFalse(callHasAnnotation, "CALL instruction should not have an annotation");
        }
        
        // Verify that PROC1 has a jump target annotation
        TokenAnnotation proc1Annotation = annotations.stream()
            .filter(a -> "PROC1".equals(a.token()))
            .findFirst()
            .orElse(null);
        
        assertNotNull(proc1Annotation, "PROC1 should have an annotation");
        assertEquals("label", proc1Annotation.kind(), "PROC1 annotation should be of kind 'label'");
        assertNotNull(proc1Annotation.annotationText(), "PROC1 should have jump target coordinates");
        assertFalse(proc1Annotation.annotationText().isEmpty(), "PROC1 jump target coordinates should not be empty");
        
        // The coordinates should be in format "x|y" (e.g., "0|0")
        assertTrue(proc1Annotation.annotationText().contains("|"), "Jump target should contain coordinate separator");
        
        // Verify basic compilation
        assertNotNull(artifact.tokenMap(), "TokenMap should exist");
        assertTrue(artifact.tokenMap().size() > 0, "TokenMap should contain tokens");
    }
}
