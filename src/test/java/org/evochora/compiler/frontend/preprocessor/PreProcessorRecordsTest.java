package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.TestLexers;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.Expansion;
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
 * placement, file and instance, or to the instance that is no inclusion under its number; and it
 * is recorded once however often it is given.
 */
@Tag("unit")
class PreProcessorRecordsTest {

    private static final String MAIN = "/p/main.evo";
    private static final String LIB = "/p/lib.evo";
    private static final List<String> LIB_LINES = List.of("NOP");
    private static final List<String> MAIN_LINES = List.of("REC", "NOP", "NOP");
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
        assertThat(result.expansions()).isEmpty();
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
    void aRecordThatNamesNeitherAnEntryNorAnExpansionIsAnInternalError() {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        PreProcessorResult result = run(diagnostics, pp -> {
            pp.note(new SourceInfo(MAIN, 1, 1, "", 0), "kept");
            pp.note(new SourceInfo(MAIN, 3, 1, "OTHER", 0), "elsewhere");
        });

        assertThat(result.sources().get(0).notes()).containsExactly(new Note(0, 1, 1, "kept"));
        assertThat(diagnostics.summary())
                .contains(MAIN + ":3: Internal error: 1 record(s) of the source view name neither a source entry"
                        + " nor an expansion.");
    }

    @Test
    void anInstanceThatIsNoInclusionKeepsWhereItCameFrom_andTheRecordsMadeInIt() {
        SourceInfo definedAt = new SourceInfo(LIB, 1, 1, "", 2);
        SourceInfo calledAt = new SourceInfo(MAIN, 3, 1, "", 0);
        List<Expansion.Binding> bindings = List.of(new Expansion.Binding("A", "%DR0"), new Expansion.Binding("B", "1 | 0"));
        PreProcessorResult result = run(pp -> {
            pp.expands(5, definedAt, calledAt, "STEP", bindings);
            pp.note(new SourceInfo(LIB, 1, 1, "", 5), "in five");
            pp.leftOut(new SourceInfo(LIB, 1, 1, "", 5), 1, 1);
            pp.note(new SourceInfo(LIB, 1, 1, "", 2), "in the inclusion");
        });

        assertThat(result.expansions()).containsOnlyKeys(5);
        Expansion five = result.expansions().get(5);
        assertThat(five.calledAt()).isEqualTo(calledAt);
        assertThat(five.definedAt()).isEqualTo(definedAt);
        assertThat(five.name()).isEqualTo("STEP");
        assertThat(five.bindings()).isEqualTo(bindings);
        assertThat(five.notes()).containsExactly(new Note(5, 1, 1, "in five"));
        assertThat(five.leftOut()).containsExactly(new LeftOut(5, 1, 1, 1));
        SourceFile sourced = result.sources().get(2);
        assertThat(sourced.notes()).containsExactly(new Note(2, 1, 1, "in the inclusion"));
        assertThat(sourced.leftOut()).isEmpty();
    }

    /**
     * Preprocesses the main file with a handler for {@code REC} that adds the two inclusions of
     * the library, records through the given action and removes its token.
     */
    private static PreProcessorResult run(Consumer<PreProcessor> records) {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        PreProcessorResult result = run(diagnostics, records);
        assertThat(diagnostics.hasErrors()).isFalse();
        return result;
    }

    /**
     * Preprocesses as {@link #run(Consumer)} does, reporting to the given diagnostics.
     */
    private static PreProcessorResult run(DiagnosticsEngine diagnostics, Consumer<PreProcessor> records) {
        List<Token> tokens = new Lexer("REC\nNOP\nNOP\n", diagnostics, MAIN, TestLexers.symbols()).scanTokens();
        PreProcessorContext context = new PreProcessorContext("", Map.of(), Map.of(MAIN, MAIN_LINES, LIB, LIB_LINES),
                "main.evo", MAIN, CompilerOptions.defaults());
        context.handlers().register("REC", (pp, ctx) -> {
            pp.includes(LIB_PLACEMENT);
            pp.includes(LIB_SOURCED);
            records.accept(pp);
            pp.removeTokens(pp.getCurrentIndex(), 1);
        });
        PreProcessor preProcessor = new PreProcessor(tokens, diagnostics,
                new SourceRootResolver(List.of(new SourceRoot(".", null)), Path.of("/p")), context);
        return preProcessor.expand();
    }
}
