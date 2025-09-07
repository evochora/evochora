package org.evochora.server.indexer.annotation;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("unit")
class CallAnnotationTest {

    private final EnvironmentProperties testEnvProps = new EnvironmentProperties(new int[]{100, 100}, true);

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void testCallInstructionAnnotationIntegration() throws Exception {
        String source = String.join("\n",
            ".PROC PROC1 EXPORT REF PROC1REG1 PROC1REG2",
            "  NOP",
            "  RET",
            ".ENDP",
            "",
            "START:",
            "  CALL PROC1 REF %DR0 %DR1",
            "  JMPI START"
        );
        
        Compiler compiler = new Compiler();
        ProgramArtifact artifact = compiler.compile(List.of(source.split("\n")), "call_integration_test.s", testEnvProps);
        
        TokenAnnotator annotator = new TokenAnnotator();
        
        String callLine = "  CALL PROC1 REF %DR0 %DR1";
        int callLineNumber = 7;
        
        List<TokenAnnotation> annotations = annotator.analyzeLine("call_integration_test.s", callLineNumber, artifact, null);
        
        if (annotations != null) {
            boolean callHasAnnotation = annotations.stream()
                .anyMatch(a -> "CALL".equals(a.token()));
            assertFalse(callHasAnnotation, "CALL instruction should not have an annotation");
        }
        
        TokenAnnotation proc1Annotation = annotations.stream()
            .filter(a -> "PROC1".equals(a.token()))
            .findFirst()
            .orElse(null);
        
        assertNotNull(proc1Annotation, "PROC1 should have an annotation");
        assertEquals("label", proc1Annotation.kind(), "PROC1 annotation should be of kind 'label'");
        assertNotNull(proc1Annotation.annotationText(), "PROC1 should have jump target coordinates");
        assertFalse(proc1Annotation.annotationText().isEmpty(), "PROC1 jump target coordinates should not be empty");
        
        assertTrue(proc1Annotation.annotationText().contains("|"), "Jump target should contain coordinate separator");
        
        assertNotNull(artifact.tokenMap(), "TokenMap should exist");
        assertTrue(artifact.tokenMap().size() > 0, "TokenMap should contain tokens");
    }
}
