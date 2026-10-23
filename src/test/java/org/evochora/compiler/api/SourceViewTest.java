package org.evochora.compiler.api;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The source view lists its instances by number and the records of an entry or an instance by
 * position, whatever order they were given in, so that an artifact is written the same way in
 * every run.
 */
@Tag("unit")
class SourceViewTest {

    private static final String MAIN = "/p/main.evo";

    @Test
    void theInstancesStandInTheOrderOfTheirNumbers() {
        SourceView view = new SourceView(List.of(), Map.of(5, expansion(), 1, expansion(), 3, expansion()));

        assertThat(view.expansions().keySet()).containsExactly(1, 3, 5);
    }

    @Test
    void notesAndRegionsStandByPositionAcrossCalls() {
        SourceView view = new SourceView(List.of(new SourceFile("", "main.evo", MAIN, List.of("A", "B", "C", "D"))), Map.of())
                .withRecords(List.of(region(4, 5)), List.of(note(3, 7, "late"), note(1, 4, "early")))
                .withRecords(List.of(region(2, 3)), List.of(note(3, 2, "between")));

        SourceFile main = view.sources().getFirst();
        assertThat(main.notes()).extracting(SourceFile.Note::text).containsExactly("early", "between", "late");
        assertThat(main.leftOut()).extracting(SourceFile.LeftOut::directiveLine).containsExactly(2, 4);
    }

    @Test
    void differentNotesAtOnePositionKeepTheOrderTheyWereGivenIn() {
        SourceView view = new SourceView(List.of(new SourceFile("", "main.evo", MAIN, List.of("A"))), Map.of())
                .withRecords(List.of(), List.of(note(1, 1, "second"), note(1, 1, "first")));

        assertThat(view.sources().getFirst().notes()).extracting(SourceFile.Note::text).containsExactly("second", "first");
    }

    private static Expansion expansion() {
        return new Expansion(new SourceInfo(MAIN, 1, 1, "", 0), new SourceInfo(MAIN, 1, 1, "", 0), "M", List.of(),
                List.of(), List.of());
    }

    private static PositionedNote note(int line, int column, String text) {
        return new PositionedNote(new SourceInfo(MAIN, line, column, "", 0), text);
    }

    private static PositionedLeftOut region(int directiveLine, int to) {
        return new PositionedLeftOut("", MAIN, new SourceFile.LeftOut(0, directiveLine, directiveLine + 1, to));
    }
}
