package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.TestLexers;
import org.evochora.compiler.TestRegistries;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.compiler.diagnostics.Diagnostic;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.BlockKind;
import org.evochora.compiler.frontend.BlockReader;
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
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for how the preprocessor walk treats blocks, with two block kinds of its own and a
 * handler that records what it is given: a whole block reaches its handler, a broken block and a
 * block whose handler gives up are left behind, a stored body may hold no top-level-only word,
 * and a closer or divider the walk reaches is removed.
 */
@Tag("unit")
class PreProcessorBlockTest {

    private static final BlockKind STORE = new BlockKind(Set.of(".STORE"), ".ENDSTORE", Set.of());
    private static final BlockKind WHEN = new BlockKind(Set.of(".WHEN"), ".ENDWHEN", Set.of(".OTHERWISE"));

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void aWholeBlockReachesItsHandlerWithItsBodyAndIsTakenOutByIt() {
        Run run = run(lex(
                ".STORE",
                "  .REPEAT 2",
                "    .WHEN",
                "      NOP",
                "    .ENDWHEN",
                "  .ENDREPEAT",
                ".ENDSTORE",
                "JMPI END"));

        assertThat(run.errors()).isEmpty();
        assertThat(run.recorder.bodies).hasSize(1);
        assertThat(texts(run.recorder.bodies.get(0)))
                .containsExactly(".REPEAT", "2", ".WHEN", "NOP", ".ENDWHEN", ".ENDREPEAT");
        assertThat(texts(run.tokens)).containsExactly("JMPI", "END");
    }

    @Test
    void dividersReachTheHandlerOnlyAtTheBlocksOwnLevel() {
        Run run = run(lex(
                ".WHEN",
                "  NOP",
                ".OTHERWISE",
                "  .WHEN",
                "  .OTHERWISE",
                "  .ENDWHEN",
                ".OTHERWISE",
                ".ENDWHEN"));

        assertThat(run.errors()).isEmpty();
        assertThat(run.recorder.dividerLines).containsExactly(List.of(3, 7));
    }

    @Test
    void aBrokenBlockIsLeftBehindAndTheWalkContinuesAfterIt() {
        Run run = run(lex(
                ".STORE",
                "  .WHEN",
                ".ENDSTORE",
                "  .ENDWHEN",
                "NOP"));

        assertThat(run.errors()).containsExactly(
                "<memory>:3: .ENDSTORE closes the .STORE opened at <memory>:1, but the .WHEN opened at <memory>:2 is still open",
                "<memory>:4: .ENDWHEN closes no open block");
        assertThat(run.recorder.bodies).isEmpty();
        assertThat(texts(run.tokens)).endsWith("NOP");
    }

    @Test
    void aBlockOpenAtTheEndOfTheInputIsReportedAtItsOpenerAndLeftBehind() {
        Run run = run(lex(
                "NOP",
                ".WHEN",
                "  JMPI END"));

        assertThat(run.errors()).containsExactly(
                "<memory>:2: .WHEN opened at <memory>:2 is not closed before the end of the input");
        assertThat(run.recorder.bodies).isEmpty();
        assertThat(texts(run.tokens)).containsExactly("NOP", ".WHEN", "JMPI", "END");
    }

    @Test
    void aBlockWhoseHandlerGivesUpIsLeftBehindAsAWhole() {
        Run run = run(lex(
                ".STORE",
                "  NOP",
                ".ENDSTORE",
                "JMPI END"), new GivingUp());

        assertThat(run.errors()).containsExactly("<memory>:1: Expected a name.");
        assertThat(texts(run.tokens)).containsExactly(".STORE", "NOP", ".ENDSTORE", "JMPI", "END");
    }

    @Test
    void aTopLevelOnlyDirectiveInAStoredBodyIsReportedAtAnyDepthAndTheBlockIsLeftBehind() {
        Run run = run(lex(
                ".STORE",
                "  .WHEN",
                "    .SOURCE \"lib.evo\"",
                "  .ENDWHEN",
                "  .IMPORT \"lib.evo\" AS LIB",
                ".ENDSTORE",
                "NOP"));

        assertThat(run.errors()).containsExactly(
                "<memory>:3: .SOURCE may not stand inside a .STORE body; the body opened at <memory>:1",
                "<memory>:5: .IMPORT may not stand inside a .STORE body; the body opened at <memory>:1");
        assertThat(run.recorder.bodies).isEmpty();
        assertThat(texts(run.tokens)).endsWith("NOP");
    }

    @Test
    void aTopLevelOnlyDirectiveIsAcceptedInABlockProcessedInPlace() {
        Run run = run(lex(
                ".WHEN",
                "  .SOURCE \"lib.evo\"",
                ".ENDWHEN"));

        assertThat(run.errors()).isEmpty();
        assertThat(texts(run.recorder.bodies.get(0))).containsExactly(".SOURCE", "\"lib.evo\"");
    }

    @Test
    void aCloserOrDividerTheWalkReachesIsReportedAndRemoved() {
        Run run = run(lex(
                "NOP",
                ".ENDWHEN",
                ".OTHERWISE",
                "JMPI END"));

        assertThat(run.errors()).containsExactly(
                "<memory>:2: .ENDWHEN closes no open block",
                "<memory>:3: .OTHERWISE divides no open block");
        assertThat(texts(run.tokens)).containsExactly("NOP", "JMPI", "END");
    }

    // --- helpers ---

    private static final class Recorder implements IPreProcessorBlockHandler {
        private final List<List<Token>> bodies = new ArrayList<>();
        private final List<List<Integer>> dividerLines = new ArrayList<>();

        @Override
        public void process(PreProcessor preProcessor, PreProcessorContext preProcessorContext, BlockReader.Block block) {
            bodies.add(preProcessor.tokensOf(block.bodyStart(), block.closer()));
            dividerLines.add(block.dividers().stream().map(i -> preProcessor.getToken(i).source().lineNumber()).toList());
            preProcessor.removeTokens(block.opener(), block.end() - block.opener());
        }
    }

    private static final class GivingUp implements IPreProcessorBlockHandler {
        @Override
        public void process(PreProcessor preProcessor, PreProcessorContext preProcessorContext, BlockReader.Block block) {
            preProcessor.advance();
            preProcessor.consume(TokenType.IDENTIFIER, "Expected a name.");
        }
    }

    private record Run(List<Token> tokens, DiagnosticsEngine diagnostics, Recorder recorder) {
        List<String> errors() {
            return diagnostics.getDiagnostics().stream()
                    .filter(d -> d.type() == Diagnostic.Type.ERROR)
                    .map(d -> d.fileName() + ":" + d.lineNumber() + ": " + d.message())
                    .toList();
        }
    }

    private static Run run(List<Token> tokens) {
        return run(tokens, null);
    }

    private static Run run(List<Token> tokens, IPreProcessorBlockHandler storeHandler) {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        PreProcessorContext context = new PreProcessorContext("", Map.of(), tokens.getFirst().source().fileName(),
                CompilerOptions.defaults());
        TestRegistries.registerPreProcessorBlocks(context.handlers());
        Recorder recorder = new Recorder();
        context.handlers().registerBlock(STORE, storeHandler != null ? storeHandler : recorder, true);
        context.handlers().registerBlock(WHEN, recorder, false);
        PreProcessor preProcessor = new PreProcessor(tokens, diagnostics,
                new SourceRootResolver(List.of(new SourceRoot(".", null)), Path.of("")), context);
        return new Run(preProcessor.expand().tokens(), diagnostics, recorder);
    }

    private static List<Token> lex(String... lines) {
        return new Lexer(String.join("\n", lines) + "\n", new DiagnosticsEngine(), TestLexers.symbols()).scanTokens();
    }

    private static List<String> texts(List<Token> tokens) {
        return tokens.stream()
                .filter(t -> t.type() != TokenType.NEWLINE && t.type() != TokenType.END_OF_FILE)
                .map(Token::text)
                .toList();
    }
}
