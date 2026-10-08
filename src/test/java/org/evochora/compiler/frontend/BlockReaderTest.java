package org.evochora.compiler.frontend;

import org.evochora.compiler.TestLexers;
import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.diagnostics.Diagnostic;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link BlockReader} on token lists, with two block kinds of its own: the extent
 * of a whole block, and every structural error with the place the reading stops at.
 */
@Tag("unit")
class BlockReaderTest {

    private static final BlockKind STORE = new BlockKind(Set.of(".STORE"), ".ENDSTORE", Set.of());
    private static final BlockKind WHEN = new BlockKind(Set.of(".WHEN"), ".ENDWHEN", Set.of(".OTHERWISE"));
    private static final Map<String, BlockKind> KINDS = Map.of(
            ".STORE", STORE, ".ENDSTORE", STORE,
            ".WHEN", WHEN, ".ENDWHEN", WHEN, ".OTHERWISE", WHEN);
    private static final Predicate<Token> NO_PREFIX = token -> false;
    private static final Predicate<Token> EXPORT = token -> token.type() == TokenType.IDENTIFIER
            && "EXPORT".equalsIgnoreCase(token.text());

    @Test
    void aWholeBlockEndsAfterItsCloserAndItsBodyBeginsAfterTheOpenersLine() {
        List<Token> tokens = lex(
                ".STORE",
                "  .WHEN",
                "    NOP",
                "  .ENDWHEN",
                ".ENDSTORE",
                "JMPI END");

        Read read = read(tokens, 0, true, NO_PREFIX);

        assertThat(read.errors()).isEmpty();
        assertThat(read.block().whole()).isTrue();
        assertThat(read.block().opener()).isZero();
        assertThat(texts(tokens, read.block().bodyStart(), read.block().closer()))
                .containsExactly(".WHEN", "NOP", ".ENDWHEN");
        assertThat(tokens.get(read.block().closer()).text()).isEqualTo(".ENDSTORE");
        assertThat(read.block().end()).isEqualTo(read.block().closer() + 1);
        assertThat(read.block().dividers()).isEmpty();
    }

    @Test
    void theCloserOfAnEnclosingBlockEndsTheInnerBlockAndClosesTheOuter() {
        List<Token> tokens = lex(
                ".STORE",
                "  .WHEN",
                ".ENDSTORE",
                "  .ENDWHEN");

        Read read = read(tokens, 0, true, NO_PREFIX);

        assertThat(read.errors()).containsExactly(
                "<memory>:3: .ENDSTORE closes the .STORE opened at <memory>:1, but the .WHEN opened at <memory>:2 is still open");
        assertThat(read.block().whole()).isFalse();
        assertThat(tokens.get(read.block().closer()).text()).isEqualTo(".ENDSTORE");
        assertThat(read.block().end()).isEqualTo(read.block().closer() + 1);
    }

    @Test
    void aDividerOfABlockThatIsNotInnermostIsReportedAndPassedOver() {
        List<Token> tokens = lex(
                ".WHEN",
                "  .STORE",
                "  .OTHERWISE",
                "  .ENDSTORE",
                ".ENDWHEN");

        Read read = read(tokens, 0, true, NO_PREFIX);

        assertThat(read.errors()).containsExactly(
                "<memory>:3: .OTHERWISE divides the .WHEN opened at <memory>:1, but the .STORE opened at <memory>:2 is still open");
        assertThat(read.block().whole()).isFalse();
        assertThat(read.block().dividers()).isEmpty();
        assertThat(tokens.get(read.block().closer()).text()).isEqualTo(".ENDWHEN");
    }

    @Test
    void aDividerWithNoBlockOfItsKindOpenIsReported() {
        List<Token> tokens = lex(
                ".STORE",
                "  .OTHERWISE",
                ".ENDSTORE");

        Read read = read(tokens, 0, true, NO_PREFIX);

        assertThat(read.errors()).containsExactly(
                "<memory>:2: .OTHERWISE divides no open block; the .STORE opened at <memory>:1 is still open");
        assertThat(read.block().whole()).isFalse();
        assertThat(tokens.get(read.block().closer()).text()).isEqualTo(".ENDSTORE");
    }

    @Test
    void aCloserWithNoBlockOfItsKindOpenIsReportedAndPassedOver() {
        List<Token> tokens = lex(
                ".STORE",
                "  .ENDWHEN",
                ".ENDSTORE");

        Read read = read(tokens, 0, true, NO_PREFIX);

        assertThat(read.errors()).containsExactly(
                "<memory>:2: .ENDWHEN closes no open block; the .STORE opened at <memory>:1 is still open");
        assertThat(read.block().whole()).isFalse();
        assertThat(tokens.get(read.block().closer()).text()).isEqualTo(".ENDSTORE");
    }

    @Test
    void theEndOfTheInputIsReportedAtEveryOpenerStillOpenInnermostFirst() {
        List<Token> tokens = lex(
                "NOP",
                ".STORE",
                "  .WHEN",
                "    JMPI END");

        Read read = read(tokens, 2, true, NO_PREFIX);

        assertThat(read.errors()).containsExactly(
                "<memory>:3: .WHEN opened at <memory>:3 is not closed before the end of the input",
                "<memory>:2: .STORE opened at <memory>:2 is not closed before the end of the input");
        assertThat(read.block().whole()).isFalse();
        assertThat(tokens.get(read.block().closer()).type()).isEqualTo(TokenType.END_OF_FILE);
        assertThat(read.block().end()).isEqualTo(read.block().closer());
    }

    @Test
    void anOpenerAsTheLastTokenOfTheInputIsReportedAndItsBodyIsEmpty() {
        List<Token> tokens = new ArrayList<>();
        tokens.add(new Token(TokenType.DIRECTIVE, ".STORE", null, new SourceInfo("a.evo", 1, 1, "", 0)));
        tokens.add(new Token(TokenType.END_OF_FILE, "", null, new SourceInfo("a.evo", 1, 7, "", 0)));

        Read read = read(tokens, 0, true, NO_PREFIX);

        assertThat(read.errors()).containsExactly("a.evo:1: .STORE opened at a.evo:1 is not closed before the end of the input");
        assertThat(read.block().bodyStart()).isEqualTo(1);
        assertThat(read.block().end()).isEqualTo(1);
    }

    @Test
    void dividersAreRecordedOnlyAtTheBlocksOwnLevel() {
        List<Token> tokens = lex(
                ".WHEN",
                "  NOP",
                ".OTHERWISE",
                "  .WHEN",
                "  .OTHERWISE",
                "  .ENDWHEN",
                ".OTHERWISE",
                ".ENDWHEN");

        Read read = read(tokens, 0, true, NO_PREFIX);

        assertThat(read.errors()).isEmpty();
        assertThat(read.block().whole()).isTrue();
        assertThat(read.block().dividers().stream().map(i -> tokens.get(i).source().lineNumber()))
                .containsExactly(3, 7);
    }

    @Test
    void withTheFileRuleABlockWordFromAnotherFileEndsTheBlockBeforeTheWord() {
        List<Token> tokens = new ArrayList<>();
        tokens.addAll(line("a.evo", 1, ".STORE"));
        tokens.addAll(line("a.evo", 2, "NOP"));
        tokens.addAll(line("b.evo", 7, ".ENDSTORE"));
        tokens.add(new Token(TokenType.END_OF_FILE, "", null, new SourceInfo("b.evo", 8, 1, "", 0)));

        Read read = read(tokens, 0, true, NO_PREFIX);

        assertThat(read.errors()).containsExactly("a.evo:1: .STORE opened at a.evo:1 is not closed before the end of a.evo");
        assertThat(read.block().whole()).isFalse();
        assertThat(tokens.get(read.block().end()).text()).isEqualTo(".ENDSTORE");
    }

    @Test
    void withTheFileRuleATokenFromAnotherFileThatIsNoBlockWordPasses() {
        List<Token> tokens = new ArrayList<>();
        tokens.addAll(line("a.evo", 1, ".STORE"));
        tokens.addAll(line("b.evo", 4, "NOP"));
        tokens.addAll(line("a.evo", 2, ".ENDSTORE"));
        tokens.add(new Token(TokenType.END_OF_FILE, "", null, new SourceInfo("a.evo", 3, 1, "", 0)));

        Read read = read(tokens, 0, true, NO_PREFIX);

        assertThat(read.errors()).isEmpty();
        assertThat(read.block().whole()).isTrue();
    }

    @Test
    void withoutTheFileRuleABlockClosesInAnotherFile() {
        List<Token> tokens = new ArrayList<>();
        tokens.addAll(line("a.evo", 1, ".STORE"));
        tokens.addAll(line("b.evo", 7, ".ENDSTORE"));
        tokens.add(new Token(TokenType.END_OF_FILE, "", null, new SourceInfo("b.evo", 8, 1, "", 0)));

        Read read = read(tokens, 0, false, NO_PREFIX);

        assertThat(read.errors()).isEmpty();
        assertThat(read.block().whole()).isTrue();
        assertThat(tokens.get(read.block().closer()).text()).isEqualTo(".ENDSTORE");
    }

    @Test
    void aPrefixWordOnTheSameLineBelongsToTheDividerOrCloserAfterIt() {
        List<Token> tokens = lex(
                ".WHEN",
                "  NOP",
                "EXPORT .OTHERWISE",
                "  NOP",
                "EXPORT .ENDWHEN");

        Read read = read(tokens, 0, true, EXPORT);

        assertThat(read.errors()).isEmpty();
        int divider = read.block().dividers().getFirst();
        int closer = read.block().closer();
        assertThat(read.block().prefixed()).containsExactlyInAnyOrder(divider, closer);
        assertThat(tokens.get(read.block().partEnd(divider)).text()).isEqualTo("EXPORT");
        assertThat(tokens.get(read.block().partEnd(closer)).text()).isEqualTo("EXPORT");
    }

    @Test
    void aPrefixWordOnItsOwnLineBelongsToNoBlockWord() {
        List<Token> tokens = lex(
                ".WHEN",
                "EXPORT",
                ".ENDWHEN");

        Read read = read(tokens, 0, true, EXPORT);

        assertThat(read.errors()).isEmpty();
        assertThat(read.block().prefixed()).isEmpty();
        assertThat(read.block().partEnd(read.block().closer())).isEqualTo(read.block().closer());
    }

    @Test
    void aTokenThatOpensNoBlockIsRejected() {
        List<Token> tokens = lex(".ENDSTORE");

        assertThatThrownBy(() -> read(tokens, 0, true, NO_PREFIX))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(".ENDSTORE");
    }

    // --- helpers ---

    private record Read(BlockReader.Block block, DiagnosticsEngine diagnostics) {
        List<String> errors() {
            return diagnostics.getDiagnostics().stream()
                    .filter(d -> d.type() == Diagnostic.Type.ERROR)
                    .map(d -> d.fileName() + ":" + d.lineNumber() + ": " + d.message())
                    .toList();
        }
    }

    private static Read read(List<Token> tokens, int opener, boolean fileRule, Predicate<Token> prefix) {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        BlockReader reader = new BlockReader(
                word -> Optional.ofNullable(KINDS.get(word.toUpperCase())), fileRule, prefix, diagnostics);
        return new Read(reader.read(tokens, opener), diagnostics);
    }

    private static List<Token> lex(String... lines) {
        return new Lexer(String.join("\n", lines) + "\n", new DiagnosticsEngine(), TestLexers.symbols()).scanTokens();
    }

    private static List<Token> line(String file, int line, String word) {
        TokenType type = word.startsWith(".") ? TokenType.DIRECTIVE : TokenType.OPCODE;
        return List.of(
                new Token(type, word, null, new SourceInfo(file, line, 1, "", 0)),
                new Token(TokenType.NEWLINE, "\n", null, new SourceInfo(file, line, word.length() + 1, "", 0)));
    }

    private static List<String> texts(List<Token> tokens, int from, int to) {
        return tokens.subList(from, to).stream()
                .filter(t -> t.type() != TokenType.NEWLINE && t.type() != TokenType.END_OF_FILE)
                .map(Token::text)
                .toList();
    }
}
