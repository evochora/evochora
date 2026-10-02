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

/**
 * Unit tests for what the preprocessor records for a source view: a region or note is recorded
 * at exactly the position it is given, attached to the source file of that position's placement
 * and file, and recorded once however often it is given.
 */
@Tag("unit")
class PreProcessorRecordsTest {

    private static final String MAIN = "/p/main.evo";
    private static final String LIB = "/p/lib.evo";
    private static final SourceFile MAIN_SOURCE = new SourceFile("", "main.evo", MAIN, List.of("REC", "NOP", "NOP"));
    private static final SourceFile LIB_SOURCE = new SourceFile("LIB", "lib.evo", LIB, List.of("NOP"));

    @Test
    void aRecordStandsAtTheGivenPositionInTheSourceOfItsPlacementAndFile() {
        PreProcessorResult result = run(pp -> {
            pp.leftOut(new SourceInfo(MAIN, 1, 1, "", 0), 2, 3);
            pp.note(new SourceInfo(MAIN, 1, 1, "", 2), "main");
            pp.note(new SourceInfo(LIB, 1, 4, "LIB", 0), "lib");
        });

        assertThat(result.sources()).hasSize(2);
        SourceFile main = result.sources().get(0);
        SourceFile lib = result.sources().get(1);
        assertThat(main.leftOut()).containsExactly(new LeftOut(0, 1, 2, 3));
        assertThat(main.notes()).containsExactly(new Note(2, 1, 1, "main"));
        assertThat(lib.leftOut()).isEmpty();
        assertThat(lib.notes()).containsExactly(new Note(0, 1, 4, "lib"));
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
    void aRecordOfAnotherPlacementOfTheSameFileStaysOutOfTheSource() {
        PreProcessorResult result = run(pp -> pp.note(new SourceInfo(MAIN, 1, 1, "OTHER", 0), "elsewhere"));

        assertThat(result.sources()).allSatisfy(source -> assertThat(source.notes()).isEmpty());
    }

    /**
     * Preprocesses the main file with a handler for {@code REC} that records through the given
     * action and removes its token.
     */
    private static PreProcessorResult run(Consumer<PreProcessor> records) {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<Token> tokens = new Lexer("REC\nNOP\nNOP\n", diagnostics, MAIN, TestLexers.symbols()).scanTokens();
        PreProcessorContext context = new PreProcessorContext("", Map.of(), List.of(MAIN_SOURCE, LIB_SOURCE),
                CompilerOptions.defaults());
        context.handlers().register("REC", (pp, ctx) -> {
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
