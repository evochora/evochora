package org.evochora.server.indexer;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.server.contracts.debug.PreparedTickState.InlineSpan;
import org.evochora.server.contracts.raw.RawOrganismState;
import org.evochora.runtime.model.Molecule;
import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SourceAnnotatorTest {

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void annotator_createsCorrectSpans_forAliasesAndLabels() throws Exception {
        // 1. Arrange: Erstelle ein Artefakt mit Aliasen und Labels
        String source = String.join("\n",
                ".REG %COUNTER %DR0",
                "START:",
                "  ADDI %COUNTER DATA:1",
                "  JMPI START"
        );
        Compiler compiler = new Compiler();
        ProgramArtifact artifact = compiler.compile(List.of(source.split("\n")), "annotator_test.s");

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

        // 2. Act: Führe die Annotation aus
        List<InlineSpan> spans = annotator.annotate(organismState, artifact, lineToAnnotate, lineNumber);

        // 3. Assert
        assertThat(spans).hasSize(1);

        InlineSpan counterSpan = spans.get(0);
        assertThat(counterSpan.lineNumber()).isEqualTo(3);
        assertThat(counterSpan.tokenToAnnotate()).isEqualTo("%COUNTER");
        assertThat(counterSpan.occurrence()).isEqualTo(1);
        assertThat(counterSpan.kind()).isEqualTo("reg");
        assertThat(counterSpan.annotationText()).isEqualTo("[%DR0=DATA:42]");

        // Teste die Annotation für das Sprungziel
        String jumpLine = "  JMPI START";
        List<InlineSpan> jumpSpans = annotator.annotate(organismState, artifact, jumpLine, 4);

        assertThat(jumpSpans).hasSize(1);
        InlineSpan jumpSpan = jumpSpans.get(0);
        assertThat(jumpSpan.tokenToAnnotate()).isEqualTo("START");
        assertThat(jumpSpan.annotationText()).contains("[0|0]"); // Die exakte Koordinate hängt vom Layout ab
        assertThat(jumpSpan.kind()).isEqualTo("jump");
    }
}