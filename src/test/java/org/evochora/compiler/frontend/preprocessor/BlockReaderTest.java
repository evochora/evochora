package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.TestLexers;
import org.evochora.compiler.TestRegistries;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.util.SourceRootResolver;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How {@link BlockReader} matches blocks: two block kinds of this test, a stored one
 * ({@code .STORE ... .ENDSTORE}) and one processed in place with a divider
 * ({@code .WHEN ... .OTHERWISE ... .ENDWHEN}), registered next to the block kinds and top-level-only
 * directives of the standard features. A recording handler reads the outermost block and removes
 * it, as a feature's handler would.
 */
@Tag("unit")
class BlockReaderTest {

    private static final BlockKind STORE = new BlockKind(Set.of(".STORE"), ".ENDSTORE", Set.of(), true);
    private static final BlockKind WHEN = new BlockKind(Set.of(".WHEN"), ".ENDWHEN", Set.of(".OTHERWISE"), false);

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void blocksOfDifferentKindsNest() {
        Run run = run(lex(
                ".STORE",
                "  .REPEAT 2",
                "    .WHEN",
                "      NOP",
                "    .ENDWHEN",
                "  .ENDREPEAT",
                ".ENDSTORE",
                "JMPI END"));

        assertThat(run.diagnostics.hasErrors()).isFalse();
        assertThat(run.recorder.bodies).hasSize(1);
        assertThat(texts(run.recorder.bodies.get(0)))
                .containsExactly(".REPEAT", "2", ".WHEN", "NOP", ".ENDWHEN", ".ENDREPEAT");
        assertThat(texts(run.tokens)).containsExactly("JMPI", "END");
    }

    @Test
    void overlappingBlocksAreReportedWithBothPositions() {
        Run run = run(lex(
                ".STORE",
                "  .WHEN",
                ".ENDSTORE",
                "  .ENDWHEN"));

        assertThat(run.diagnostics.summary()).contains(
                ".ENDSTORE closes the .STORE opened at <memory>:1, but the .WHEN opened at <memory>:2 is still open");
        assertThat(run.recorder.bodies).isEmpty();
    }

    @Test
    void aBlockOpenAtTheEndOfTheInputIsReportedAtItsOpener() {
        Run run = run(lex(
                "NOP",
                ".WHEN",
                "  JMPI END"));

        assertThat(run.diagnostics.summary()).contains("<memory>:2: .WHEN opened at <memory>:2 is not closed before the end of the input");
        // The walk passes over the opener's line and resumes after it.
        assertThat(texts(run.tokens)).containsExactly("NOP", ".WHEN", "JMPI", "END");
    }

    @Test
    void aBlockWordFromAnotherFileIsReportedAtTheOpener() {
        List<Token> tokens = new ArrayList<>();
        tokens.addAll(line("a.evo", 1, ".STORE"));
        tokens.addAll(line("a.evo", 2, "NOP"));
        tokens.addAll(line("b.evo", 7, ".ENDSTORE"));
        tokens.add(new Token(TokenType.END_OF_FILE, "", null, 8, 1, "b.evo", ""));

        Run run = run(tokens);

        assertThat(run.diagnostics.summary()).contains("a.evo:1: .STORE opened at a.evo:1 is not closed before the end of a.evo");
        assertThat(run.recorder.bodies).isEmpty();
    }

    @Test
    void aTokenFromAnotherFileThatIsNoBlockWordPasses() {
        List<Token> tokens = new ArrayList<>();
        tokens.addAll(line("a.evo", 1, ".STORE"));
        tokens.addAll(line("b.evo", 4, "NOP"));
        tokens.addAll(line("a.evo", 2, ".ENDSTORE"));
        tokens.add(new Token(TokenType.END_OF_FILE, "", null, 3, 1, "a.evo", ""));

        Run run = run(tokens);

        assertThat(run.diagnostics.hasErrors()).isFalse();
        assertThat(texts(run.recorder.bodies.get(0))).containsExactly("NOP");
    }

    @Test
    void dividersAreHandedOverOnlyAtTheBlocksOwnLevel() {
        Run run = run(lex(
                ".WHEN",
                "  NOP",
                ".OTHERWISE",
                "  .WHEN",
                "  .OTHERWISE",
                "  .ENDWHEN",
                ".OTHERWISE",
                ".ENDWHEN"));

        assertThat(run.diagnostics.hasErrors()).isFalse();
        assertThat(run.recorder.dividerLines).containsExactly(List.of(3, 7));
    }

    @Test
    void aDividerOfABlockThatIsNotInnermostIsReported() {
        Run run = run(lex(
                ".WHEN",
                "  .STORE",
                "  .OTHERWISE",
                "  .ENDSTORE",
                ".ENDWHEN",
                "NOP"));

        assertThat(run.diagnostics.summary()).contains(
                "<memory>:3: .OTHERWISE divides the .WHEN opened at <memory>:1, but the .STORE opened at <memory>:2 is still open");
        // The block is whole, so it is taken out as a whole.
        assertThat(texts(run.tokens)).containsExactly("NOP");
    }

    @Test
    void aDividerWithoutItsBlockIsReported() {
        Run run = run(lex(
                ".STORE",
                "  .OTHERWISE",
                ".ENDSTORE"));

        assertThat(run.diagnostics.summary()).contains(
                ".OTHERWISE divides no open block; the .STORE opened at <memory>:1 is still open");
    }

    @Test
    void aTopLevelOnlyDirectiveIsRejectedInAStoredBody() {
        Run run = run(lex(
                ".STORE",
                "  .WHEN",
                "    .SOURCE \"lib.evo\"",
                "  .ENDWHEN",
                ".ENDSTORE",
                "NOP"));

        assertThat(run.diagnostics.summary()).contains(
                "<memory>:3: .SOURCE may not stand inside a .STORE body; the body opened at <memory>:1");
        assertThat(texts(run.tokens)).containsExactly("NOP");
    }

    @Test
    void aTopLevelOnlyDirectiveIsAcceptedInABlockProcessedInPlace() {
        Run run = run(lex(
                ".WHEN",
                "  .SOURCE \"lib.evo\"",
                ".ENDWHEN"));

        assertThat(run.diagnostics.hasErrors()).isFalse();
        assertThat(texts(run.recorder.bodies.get(0))).containsExactly(".SOURCE", "\"lib.evo\"");
    }

    // --- helpers ---

    /** Reads the block it stands on, records it, and removes it from the stream. */
    private static final class Recorder implements IPreProcessorHandler {
        private final List<List<Token>> bodies = new ArrayList<>();
        private final List<List<Integer>> dividerLines = new ArrayList<>();

        @Override
        public void process(PreProcessor preProcessor, PreProcessorContext preProcessorContext) {
            int start = preProcessor.getCurrentIndex();
            BlockReader.Block block = preProcessor.readBlock(start);
            bodies.add(block.body());
            dividerLines.add(block.dividers().stream().map(i -> preProcessor.getToken(i).line()).toList());
            preProcessor.removeTokens(start, block.end() - start);
        }
    }

    private record Run(List<Token> tokens, DiagnosticsEngine diagnostics, Recorder recorder) {
    }

    private static Run run(List<Token> tokens) {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        PreProcessorContext context = new PreProcessorContext();
        TestRegistries.registerPreProcessorBlocks(context.handlers());
        context.handlers().registerBlock(STORE);
        context.handlers().registerBlock(WHEN);
        Recorder recorder = new Recorder();
        context.handlers().register(".STORE", recorder);
        context.handlers().register(".WHEN", recorder);
        PreProcessor preProcessor = new PreProcessor(tokens, diagnostics,
                new SourceRootResolver(List.of(new SourceRoot(".", null)), Path.of("")), context);
        return new Run(preProcessor.expand().tokens(), diagnostics, recorder);
    }

    private static List<Token> lex(String... lines) {
        return new Lexer(String.join("\n", lines) + "\n", new DiagnosticsEngine(), TestLexers.symbols()).scanTokens();
    }

    /** One line of a file: a directive or opcode followed by a newline. */
    private static List<Token> line(String file, int line, String word) {
        TokenType type = word.startsWith(".") ? TokenType.DIRECTIVE : TokenType.OPCODE;
        return List.of(
                new Token(type, word, null, line, 1, file, ""),
                new Token(TokenType.NEWLINE, "\n", null, line, word.length() + 1, file, ""));
    }

    private static List<String> texts(List<Token> tokens) {
        return tokens.stream()
                .filter(t -> t.type() != TokenType.NEWLINE && t.type() != TokenType.END_OF_FILE)
                .map(Token::text)
                .toList();
    }
}
