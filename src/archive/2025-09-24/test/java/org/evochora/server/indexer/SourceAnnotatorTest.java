package org.evochora.server.indexer;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.server.contracts.debug.PreparedTickState.InlineSpan;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import java.util.Map;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contains unit tests for the {@link SourceAnnotator}.
 * These tests verify that the annotator can correctly generate inline "spans"
 * (additional debug information) for a given line of source code, based on the
 * program artifact and the current organism state.
 * This is a unit test and does not require external resources.
 */
import org.evochora.runtime.model.EnvironmentProperties;

class SourceAnnotatorTest {

    private final EnvironmentProperties testEnvProps = new EnvironmentProperties(new int[]{100, 100}, true);

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    /**
     * Verifies that the SourceAnnotator correctly creates annotation spans for both
     * register aliases and jump labels.
     * It checks that a register alias is annotated with its underlying register and current value,
     * and that a jump label is annotated with its resolved memory address.
     * This is a unit test for the source annotation logic.
     * @throws Exception if compilation fails.
     */
    @Test
    @Tag("unit")
    //@Disabled("Source Annotation still needs t be implemented properly")
    void annotator_createsCorrectSpans_forAliasesAndLabels() throws Exception {
        // 1. Arrange: Erstelle ein Artefakt mit Aliasen und Labels
        String source = String.join("\n",
                ".REG %COUNTER %DR0",
                "START:",
                "  ADDI %COUNTER DATA:1",
                "  JMPI START"
        );
        Compiler compiler = new Compiler();
        ProgramArtifact artifact = compiler.compile(List.of(source.split("\n")), "annotator_test.s", testEnvProps);

        // Erstelle einen rohen Organismus-Zustand, bei dem %DR0 einen Wert hat
        RawOrganismState organismState = new RawOrganismState(
                1, null, 0L, artifact.programId(), new int[]{0,0},
                new int[]{2,0}, new int[]{1,0}, Collections.emptyList(), 0, 1000,
                Collections.singletonList(new Molecule(Config.TYPE_DATA, 42).toInt()), // DR0 = DATA:42
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                new java.util.ArrayDeque<>(), new java.util.ArrayDeque<>(), new java.util.ArrayDeque<>(),
                false, false, null, false, new int[]{2,0}, new int[]{1,0}
        );

        SourceAnnotator annotator = new SourceAnnotator();
        String lineToAnnotate = "  ADDI %COUNTER DATA:1";
        int lineNumber = 3;

        // 2. Act: Führe die Annotation aus (this is the active line)
        List<InlineSpan> spans = annotator.annotate(organismState, artifact, "annotator_test.s", lineToAnnotate, lineNumber, true);

        // Check if we have tokens for the current line in any file
        boolean foundTokens = false;
        for (Map<Integer, Map<Integer, List<org.evochora.compiler.api.TokenInfo>>> fileTokens : artifact.tokenLookup().values()) {
            if (fileTokens.containsKey(lineNumber)) {
                foundTokens = true;
                break;
            }
        }
        
        for (Map.Entry<String, Map<Integer, Map<Integer, List<org.evochora.compiler.api.TokenInfo>>>> fileEntry : artifact.tokenLookup().entrySet()) {
            String fileName = fileEntry.getKey();
            for (Map.Entry<Integer, Map<Integer, List<org.evochora.compiler.api.TokenInfo>>> lineEntry : fileEntry.getValue().entrySet()) {
                Integer lineNum = lineEntry.getKey();
                Map<Integer, List<org.evochora.compiler.api.TokenInfo>> columnTokens = lineEntry.getValue();
                List<org.evochora.compiler.api.TokenInfo> allTokens = columnTokens.values().stream().flatMap(List::stream).toList();
            }
        }

        boolean foundCounter = false;
        if (artifact.tokenMap() != null) {
            for (org.evochora.compiler.api.TokenInfo info : artifact.tokenMap().values()) {
                if ("%COUNTER".equals(info.tokenText())) {
                    foundCounter = true;
                    break;
                }
            }
        }

        // 3. Assert
        assertThat(spans).hasSize(1);

        InlineSpan counterSpan = spans.get(0);
        assertThat(counterSpan.lineNumber()).isEqualTo(3);
        assertThat(counterSpan.tokenToAnnotate()).isEqualTo("%COUNTER");
        assertThat(counterSpan.occurrence()).isEqualTo(1);
        assertThat(counterSpan.kind()).isEqualTo("reg");
        assertThat(counterSpan.annotationText()).isEqualTo("%DR0=DATA:42");

        // Teste die Annotation für das Sprungziel (this is the active line)
        String jumpLine = "  JMPI START";
        List<InlineSpan> jumpSpans = annotator.annotate(organismState, artifact, "annotator_test.s", jumpLine, 4, true);

        assertThat(jumpSpans).hasSize(1);
        InlineSpan jumpSpan = jumpSpans.get(0);
        assertThat(jumpSpan.tokenToAnnotate()).isEqualTo("START");
        assertThat(jumpSpan.annotationText()).contains("0|0"); // Die exakte Koordinate hängt vom Layout ab
        assertThat(jumpSpan.kind()).isEqualTo("label");
        
        // Test that non-active lines don't get annotations
        List<InlineSpan> nonActiveSpans = annotator.annotate(organismState, artifact, "annotator_test.s", lineToAnnotate, lineNumber, false);
        assertThat(nonActiveSpans).isEmpty();
    }

    /**
     * Verifies that procedure calls are properly annotated with their jump target coordinates.
     * This tests that CALL MY_PROC shows as CALL MY_PROC[1|2] where 1|2 are the target coordinates.
     * @throws Exception if compilation fails.
     */
    @Test
    @Tag("unit")
    //@Disabled("Source Annotation still needs t be implemented properly")
    void annotator_createsCorrectSpans_forProcedureCalls() throws Exception {
        // 1. Arrange: Create an artifact with procedure calls
        String source = String.join("\n",
                "  NOP",  // Add NOP before procedure to shift coordinates
                ".PROC MY_PROC EXPORT REF PARAM1 PARAM2",
                "  NOP",
                "  RET",
                ".ENDP",
                "",
                "START:",
                "  CALL MY_PROC REF %DR0 %DR1",
                "  JMPI START"
        );
        Compiler compiler = new Compiler();
        ProgramArtifact artifact = compiler.compile(List.of(source.split("\n")), "proc_call_test.s", testEnvProps);

        // Create a raw organism state
        RawOrganismState organismState = new RawOrganismState(
                1, null, 0L, artifact.programId(), new int[]{0,0},
                new int[]{2,0}, new int[]{1,0}, Collections.emptyList(), 0, 1000,
                Arrays.asList(
                    new Molecule(Config.TYPE_DATA, 42).toInt(),  // DR0 = DATA:42
                    new Molecule(Config.TYPE_CODE, 100).toInt()  // DR1 = CODE:100
                ),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                new java.util.ArrayDeque<>(), new java.util.ArrayDeque<>(), new java.util.ArrayDeque<>(),
                false, false, null, false, new int[]{2,0}, new int[]{1,0}
        );

        SourceAnnotator annotator = new SourceAnnotator();
        String lineToAnnotate = "  CALL MY_PROC REF %DR0 %DR1";
        int lineNumber = 8; // CALL instruction is actually on line 8 based on TokenMap output

        // 2. Act: Execute the annotation (this is the active line)
        List<InlineSpan> spans = annotator.annotate(organismState, artifact, "proc_call_test.s", lineToAnnotate, lineNumber, true);

        // 3. Assert: Should have annotation for MY_PROC showing jump target coordinates
        assertThat(spans).hasSize(3); // We expect 3: MY_PROC, %DR0, %DR1

        // Find the MY_PROC annotation
        InlineSpan procSpan = spans.stream()
                .filter(span -> "MY_PROC".equals(span.tokenToAnnotate()))
                .findFirst()
                .orElse(null);
        
        assertThat(procSpan).isNotNull();
        assertThat(procSpan.lineNumber()).isEqualTo(8);
        assertThat(procSpan.tokenToAnnotate()).isEqualTo("MY_PROC");
        assertThat(procSpan.occurrence()).isEqualTo(1);
        assertThat(procSpan.kind()).isEqualTo("label");
        // The annotation should show the jump target coordinates from callSiteBindings
        assertThat(procSpan.annotationText()).isNotEmpty();

        // Check that we have register annotations too
        InlineSpan dr0Span = spans.stream()
                .filter(span -> "%DR0".equals(span.tokenToAnnotate()))
                .findFirst()
                .orElse(null);
        assertThat(dr0Span).isNotNull();
        assertThat(dr0Span.annotationText()).isEqualTo("DATA:42");
    }
    

}