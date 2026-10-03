package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.TestLexers;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.SourceFile;
import org.evochora.compiler.api.SourceFile.LeftOut;
import org.evochora.compiler.api.SourceFile.Note;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.util.SourceRootResolver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Unit tests for the entries the preprocessor lists and what it records for a source view: the
 * main file is the first entry and every inclusion follows in the order it is added; a region or
 * note is recorded at exactly the position it is given, attached to the entry of that position's
 * placement, file and instance, or to the entry an instance stands on; and it is recorded once
 * however often it is given.
 */
@Tag("unit")
class PreProcessorRecordsTest {

    private static final String MAIN = "/p/main.evo";
    private static final String LIB = "/p/lib.evo";
    private static final List<String> LIB_LINES = List.of("NOP");
    private static final SourceFile MAIN_SOURCE = new SourceFile("", "main.evo", MAIN, List.of("REC", "NOP", "NOP"));
    private static final SourceFile LIB_PLACEMENT = new SourceFile("LIB", "lib.evo", LIB, 0,
            new SourceInfo(MAIN, 1, 1, "", 0), LIB_LINES);
    private static final SourceFile LIB_SOURCED = new SourceFile("", "lib.evo", LIB, 2,
            new SourceInfo(MAIN, 2, 1, "", 0), LIB_LINES);

    @Test
    void theMainFileComesFirst_andTheInclusionsFollowInTheOrderTheyAreAdded() {
        PreProcessorResult result = run(pp -> { });

        assertThat(result.sources()).extracting(SourceFile::placement, SourceFile::instance)
                .containsExactly(tuple("", 0), tuple("LIB", 0), tuple("", 2));
        assertThat(result.sources().get(0).includedAt()).isNull();
        assertThat(result.sources().get(2).includedAt()).isEqualTo(new SourceInfo(MAIN, 2, 1, "", 0));
        assertThat(result.sources().get(1).lines()).isSameAs(result.sources().get(2).lines());
        assertThat(result.expansionHomes()).isEmpty();
    }

    @Test
    void aRecordStandsAtTheGivenPositionInTheEntryOfItsPlacementFileAndInstance() {
        PreProcessorResult result = run(pp -> {
            pp.leftOut(new SourceInfo(MAIN, 1, 1, "", 0), 2, 3);
            pp.note(new SourceInfo(LIB, 1, 4, "LIB", 0), "lib");
            pp.note(new SourceInfo(LIB, 1, 1, "", 2), "sourced");
        });

        SourceFile main = result.sources().get(0);
        SourceFile placed = result.sources().get(1);
        SourceFile sourced = result.sources().get(2);
        assertThat(main.leftOut()).containsExactly(new LeftOut(0, 1, 2, 3));
        assertThat(main.notes()).isEmpty();
        assertThat(placed.leftOut()).isEmpty();
        assertThat(placed.notes()).containsExactly(new Note(0, 1, 4, "lib"));
        assertThat(sourced.notes()).containsExactly(new Note(2, 1, 1, "sourced"));
    }

    @Test
    void aRecordGivenTwiceIsRecordedOnce_andDistinctRecordsKeepTheirOrder() {
        PreProcessorResult result = run(pp -> {
            SourceInfo at = new SourceInfo(MAIN, 1, 1, "", 0);
            pp.note(at, "second");
            pp.note(at, "first");
            pp.note(at, "second");
            pp.leftOut(at, 2, 3);
            pp.leftOut(at, 2, 3);
        });

        SourceFile main = result.sources().get(0);
        assertThat(main.notes()).containsExactly(new Note(0, 1, 1, "second"), new Note(0, 1, 1, "first"));
        assertThat(main.leftOut()).containsExactly(new LeftOut(0, 1, 2, 3));
    }

    @Test
    void aRecordOfAnotherPlacementOrOfAnInstanceStandingNowhereStaysOutOfTheEntries() {
        PreProcessorResult result = run(pp -> {
            pp.note(new SourceInfo(MAIN, 1, 1, "OTHER", 0), "elsewhere");
            pp.note(new SourceInfo(MAIN, 1, 1, "", 7), "nowhere");
        });

        assertThat(result.sources()).allSatisfy(source -> assertThat(source.notes()).isEmpty());
    }

    @Test
    void anInstanceStandingOnTheLinesOfAnEntry_keepsItsRecordsThereUnderItsOwnNumber() {
        PreProcessorResult result = run(pp -> {
            pp.homeOf(5, new SourceInfo(LIB, 1, 1, "", 2));
            pp.homeOf(6, new SourceInfo(LIB, 1, 1, "", 5));
            pp.homeOf(8, new SourceInfo(MAIN, 3, 1, "", 0));
            pp.note(new SourceInfo(LIB, 1, 1, "", 5), "in five");
            pp.leftOut(new SourceInfo(LIB, 1, 1, "", 6), 1, 1);
            pp.note(new SourceInfo(MAIN, 3, 1, "", 8), "in eight");
        });

        assertThat(result.expansionHomes()).containsExactlyInAnyOrderEntriesOf(Map.of(5, 2, 6, 2, 8, 0));
        SourceFile sourced = result.sources().get(2);
        assertThat(sourced.notes()).containsExactly(new Note(5, 1, 1, "in five"));
        assertThat(sourced.leftOut()).containsExactly(new LeftOut(6, 1, 1, 1));
        assertThat(result.sources().get(0).notes()).containsExactly(new Note(8, 3, 1, "in eight"));
    }

    /**
     * Preprocesses the main file with a handler for {@code REC} that adds the two inclusions of
     * the library, records through the given action and removes its token.
     */
    private static PreProcessorResult run(Consumer<PreProcessor> records) {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<Token> tokens = new Lexer("REC\nNOP\nNOP\n", diagnostics, MAIN, TestLexers.symbols()).scanTokens();
        PreProcessorContext context = new PreProcessorContext("", Map.of(), Map.of(LIB, LIB_LINES), MAIN_SOURCE,
                CompilerOptions.defaults());
        context.handlers().register("REC", (pp, ctx) -> {
            pp.includes(LIB_PLACEMENT);
            pp.includes(LIB_SOURCED);
            records.accept(pp);
            pp.removeTokens(pp.getCurrentIndex(), 1);
        });
        PreProcessor preProcessor = new PreProcessor(tokens, diagnostics,
                new SourceRootResolver(List.of(new SourceRoot(".", null)), Path.of("/p")), context);
        PreProcessorResult result = preProcessor.expand();
        assertThat(diagnostics.hasErrors()).isFalse();
        return result;
    }
}
