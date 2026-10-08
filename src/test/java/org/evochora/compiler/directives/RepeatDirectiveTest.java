package org.evochora.compiler.directives;

import java.util.Map;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.TestRegistries;
import org.evochora.compiler.TestLexers;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.features.repeat.CaretDirectiveHandler;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.util.SourceRootResolver;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the preprocessor's handling of the .REPEAT directive.
 */
public class RepeatDirectiveTest {

    @BeforeAll
    static void setUp() {
        Instruction.init();
    }

    private PreProcessor createPreProcessor(List<Token> initialTokens, DiagnosticsEngine diagnostics) {
        PreProcessorContext context = new PreProcessorContext("", Map.of(), initialTokens.getFirst().source().fileName(),
                CompilerOptions.defaults());
        TestRegistries.registerPreProcessorBlocks(context.handlers());
        context.handlers().register("^", new CaretDirectiveHandler());
        context.handlers().register(":", new org.evochora.compiler.features.label.ColonLabelHandler());
        return new PreProcessor(initialTokens, diagnostics,
                new SourceRootResolver(List.of(new SourceRoot(".", null)), Path.of("")),
                context);
    }

    /**
     * Tests block mode: .REPEAT n; ... .ENDREPEAT
     */
    @Test
    @Tag("unit")
    void testBlockRepeat() {
        // Arrange: semicolons become NEWLINEs in the lexer
        String source = ".REPEAT 2; JMPI LOOP; NOP; .ENDREPEAT";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());
        List<Token> initialTokens = lexer.scanTokens();
        PreProcessor preProcessor = createPreProcessor(initialTokens, diagnostics);

        // Act
        List<Token> expandedTokens = preProcessor.expand().tokens();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        List<TokenType> types = expandedTokens.stream().map(Token::type).toList();
        // (JMPI LOOP NEWLINE NOP) NEWLINE (JMPI LOOP NEWLINE NOP) EOF
        assertThat(types).containsExactly(
                TokenType.OPCODE,      // JMPI
                TokenType.IDENTIFIER,  // LOOP
                TokenType.NEWLINE,
                TokenType.OPCODE,      // NOP
                TokenType.NEWLINE,     // between repetitions
                TokenType.OPCODE,      // JMPI
                TokenType.IDENTIFIER,  // LOOP
                TokenType.NEWLINE,
                TokenType.OPCODE,      // NOP
                TokenType.END_OF_FILE
        );
    }

    /**
     * A statement after a block stays a statement of its own: the newline after .ENDREPEAT
     * separates it from the last repetition.
     */
    @Test
    @Tag("unit")
    void testBlockRepeatFollowedByStatement() {
        // Arrange
        String source = String.join("\n",
                ".REPEAT 2",
                "  NOP",
                ".ENDREPEAT",
                "JMPI START",
                ""
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());
        List<Token> initialTokens = lexer.scanTokens();
        PreProcessor preProcessor = createPreProcessor(initialTokens, diagnostics);

        // Act
        List<Token> expandedTokens = preProcessor.expand().tokens();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        List<TokenType> types = expandedTokens.stream().map(Token::type).toList();
        // (NOP) NEWLINE (NOP) NEWLINE JMPI START NEWLINE EOF
        assertThat(types).containsExactly(
                TokenType.OPCODE,      // NOP
                TokenType.NEWLINE,     // between repetitions
                TokenType.OPCODE,      // NOP
                TokenType.NEWLINE,     // the newline after .ENDREPEAT
                TokenType.OPCODE,      // JMPI
                TokenType.IDENTIFIER,  // START
                TokenType.NEWLINE,
                TokenType.END_OF_FILE
        );
    }

    /**
     * Tests block mode with actual newlines.
     */
    @Test
    @Tag("unit")
    void testBlockRepeatMultiline() {
        // Arrange
        String source = String.join("\n",
                ".REPEAT 2",
                "  NOP",
                "  JMPI START",
                ".ENDREPEAT"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());
        List<Token> initialTokens = lexer.scanTokens();
        PreProcessor preProcessor = createPreProcessor(initialTokens, diagnostics);

        // Act
        List<Token> expandedTokens = preProcessor.expand().tokens();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        List<TokenType> types = expandedTokens.stream().map(Token::type).toList();
        // (NOP NEWLINE JMPI START) NEWLINE (NOP NEWLINE JMPI START) EOF
        assertThat(types).containsExactly(
                TokenType.OPCODE,      // NOP
                TokenType.NEWLINE,
                TokenType.OPCODE,      // JMPI
                TokenType.IDENTIFIER,  // START
                TokenType.NEWLINE,     // between repetitions
                TokenType.OPCODE,      // NOP
                TokenType.NEWLINE,
                TokenType.OPCODE,      // JMPI
                TokenType.IDENTIFIER,  // START
                TokenType.END_OF_FILE
        );
    }

    /**
     * A block repeated zero times leaves nothing behind.
     */
    @Test
    @Tag("unit")
    void testBlockRepeatZero() {
        // Arrange
        String source = String.join("\n",
                ".REPEAT 0",
                "  NOP",
                ".ENDREPEAT"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());
        PreProcessor preProcessor = createPreProcessor(lexer.scanTokens(), diagnostics);

        // Act
        List<Token> expandedTokens = preProcessor.expand().tokens();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(expandedTokens.stream().map(Token::type).toList()).containsExactly(TokenType.END_OF_FILE);
    }

    /**
     * A statement on the line of .REPEAT is rejected, and the message names the shorthand that
     * repeats a single statement; the block is removed as a whole.
     */
    @Test
    @Tag("unit")
    void testStatementOnTheRepeatLineIsRejected() {
        // Arrange
        String source = "JMPI START; .REPEAT 3 NOP; .ENDREPEAT; JMPI END";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());
        PreProcessor preProcessor = createPreProcessor(lexer.scanTokens(), diagnostics);

        // Act
        List<Token> expandedTokens = preProcessor.expand().tokens();

        // Assert
        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.summary()).contains("NOP^3");
        assertThat(expandedTokens.stream().map(Token::text).toList())
                .doesNotContain(".REPEAT", "NOP", ".ENDREPEAT")
                .containsSubsequence("JMPI", "START", "JMPI", "END");
    }

    /**
     * A .REPEAT line with a statement and no .ENDREPEAT is a block that is never closed: the
     * block is read before the handler runs, so the message is the one for an open block.
     */
    @Test
    @Tag("unit")
    void testStatementOnTheRepeatLineWithoutEndRepeatIsReportedAsUnclosed() {
        // Arrange
        String source = "JMPI START; .REPEAT 3 NOP; JMPI END";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());
        PreProcessor preProcessor = createPreProcessor(lexer.scanTokens(), diagnostics);

        // Act
        preProcessor.expand();

        // Assert
        assertThat(diagnostics.summary())
                .contains("<memory>:1: .REPEAT opened at <memory>:1 is not closed before the end of the input")
                .doesNotContain("NOP^3");
    }

    /**
     * An .ENDREPEAT outside any block is reported at its line and removed.
     */
    @Test
    @Tag("unit")
    void testStrayEndRepeatIsReported() {
        // Arrange
        String source = "NOP\n.ENDREPEAT\nJMPI END\n";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());
        PreProcessor preProcessor = createPreProcessor(lexer.scanTokens(), diagnostics);

        // Act
        List<Token> expandedTokens = preProcessor.expand().tokens();

        // Assert
        assertThat(diagnostics.summary()).contains("<memory>:2: .ENDREPEAT closes no open block");
        assertThat(expandedTokens.stream().map(Token::text).toList()).doesNotContain(".ENDREPEAT");
    }

    /**
     * Blocks nest: a .REPEAT inside a .REPEAT is closed by its own .ENDREPEAT.
     */
    @Test
    @Tag("unit")
    void testNestedBlocks() {
        // Arrange
        String source = String.join("\n",
                ".REPEAT 2",
                "  .REPEAT 3",
                "    NOP",
                "  .ENDREPEAT",
                "  JMPI START",
                ".ENDREPEAT"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());
        PreProcessor preProcessor = createPreProcessor(lexer.scanTokens(), diagnostics);

        // Act
        List<String> texts = preProcessor.expand().tokens().stream()
                .filter(t -> t.type() != TokenType.NEWLINE && t.type() != TokenType.END_OF_FILE)
                .map(Token::text).toList();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(texts).containsExactly(
                "NOP", "NOP", "NOP", "JMPI", "START",
                "NOP", "NOP", "NOP", "JMPI", "START");
    }

    // ========== Caret Syntax (^n) Tests ==========

    /**
     * Tests caret syntax: NOP^3 should expand to NOP; NOP; NOP
     */
    @Test
    @Tag("unit")
    void testCaretSyntaxSimple() {
        // Arrange
        String source = "NOP^3";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());
        List<Token> initialTokens = lexer.scanTokens();
        PreProcessor preProcessor = createPreProcessor(initialTokens, diagnostics);

        // Act
        List<Token> expandedTokens = preProcessor.expand().tokens();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        List<TokenType> types = expandedTokens.stream().map(Token::type).toList();
        assertThat(types).containsExactly(
                TokenType.OPCODE,    // NOP
                TokenType.NEWLINE,
                TokenType.OPCODE,    // NOP
                TokenType.NEWLINE,
                TokenType.OPCODE,    // NOP
                TokenType.END_OF_FILE
        );
    }

    /**
     * Tests caret syntax with arguments: JMPI LOOP^2
     */
    @Test
    @Tag("unit")
    void testCaretSyntaxWithArguments() {
        // Arrange
        String source = "JMPI LOOP^2";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());
        List<Token> initialTokens = lexer.scanTokens();
        PreProcessor preProcessor = createPreProcessor(initialTokens, diagnostics);

        // Act
        List<Token> expandedTokens = preProcessor.expand().tokens();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        List<TokenType> types = expandedTokens.stream().map(Token::type).toList();
        assertThat(types).containsExactly(
                TokenType.OPCODE,      // JMPI
                TokenType.IDENTIFIER,  // LOOP
                TokenType.NEWLINE,
                TokenType.OPCODE,      // JMPI
                TokenType.IDENTIFIER,  // LOOP
                TokenType.END_OF_FILE
        );
    }

    /**
     * Tests caret syntax with context: JMPI START; NOP^3; JMPI END
     */
    @Test
    @Tag("unit")
    void testCaretSyntaxWithContext() {
        // Arrange
        String source = "JMPI START; NOP^3; JMPI END";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());
        List<Token> initialTokens = lexer.scanTokens();
        PreProcessor preProcessor = createPreProcessor(initialTokens, diagnostics);

        // Act
        List<Token> expandedTokens = preProcessor.expand().tokens();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        List<TokenType> types = expandedTokens.stream().map(Token::type).toList();
        assertThat(types).containsExactly(
                TokenType.OPCODE,      // JMPI
                TokenType.IDENTIFIER,  // START
                TokenType.NEWLINE,
                TokenType.OPCODE,      // NOP
                TokenType.NEWLINE,
                TokenType.OPCODE,      // NOP
                TokenType.NEWLINE,
                TokenType.OPCODE,      // NOP
                TokenType.NEWLINE,
                TokenType.OPCODE,      // JMPI
                TokenType.IDENTIFIER,  // END
                TokenType.END_OF_FILE
        );
    }

    /**
     * Tests caret syntax with ^0 (should produce nothing).
     */
    @Test
    @Tag("unit")
    void testCaretSyntaxZero() {
        // Arrange
        String source = "NOP^0";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());
        List<Token> initialTokens = lexer.scanTokens();
        PreProcessor preProcessor = createPreProcessor(initialTokens, diagnostics);

        // Act
        List<Token> expandedTokens = preProcessor.expand().tokens();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        List<TokenType> types = expandedTokens.stream().map(Token::type).toList();
        assertThat(types).containsExactly(TokenType.END_OF_FILE);
    }

    /**
     * The shorthand and the block it stands for expand to the same tokens.
     */
    @Test
    @Tag("unit")
    void testCaretAndBlockExpandAlike() {
        DiagnosticsEngine caretDiagnostics = new DiagnosticsEngine();
        List<Token> caret = createPreProcessor(
                new Lexer("NOP^3\nJMPI END\n", caretDiagnostics, TestLexers.symbols()).scanTokens(),
                caretDiagnostics).expand().tokens();
        DiagnosticsEngine blockDiagnostics = new DiagnosticsEngine();
        List<Token> block = createPreProcessor(
                new Lexer(".REPEAT 3\nNOP\n.ENDREPEAT\nJMPI END\n", blockDiagnostics, TestLexers.symbols()).scanTokens(),
                blockDiagnostics).expand().tokens();

        assertThat(caretDiagnostics.hasErrors()).isFalse();
        assertThat(blockDiagnostics.hasErrors()).isFalse();
        assertThat(caret.stream().map(Token::text).toList())
                .containsExactlyElementsOf(block.stream().map(Token::text).toList());
        assertThat(caret.stream().map(Token::type).toList())
                .containsExactlyElementsOf(block.stream().map(Token::type).toList());
    }

    /**
     * The body of the shorthand is a stored body like that of a block: a directive that stands
     * only at the top level is rejected in it.
     */
    @Test
    @Tag("unit")
    void testCaretBodyRejectsATopLevelOnlyDirective() {
        // Arrange
        String source = ".REQUIRE \"lib.evo\" AS LIB^2\nNOP\n";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());
        PreProcessor preProcessor = createPreProcessor(lexer.scanTokens(), diagnostics);

        // Act
        List<Token> expandedTokens = preProcessor.expand().tokens();

        // Assert
        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.summary()).contains(".REQUIRE may not stand inside a .REPEAT body");
        // The block is left behind unexpanded, and the walk goes on after it.
        assertThat(expandedTokens.stream().map(Token::text).toList())
                .containsSubsequence(".REPEAT", ".REQUIRE", ".ENDREPEAT", "NOP")
                .containsOnlyOnce(".REQUIRE");
    }

    /**
     * Tests that caret syntax preserves labels and doesn't repeat them.
     * LABEL: NOP^2 should become LABEL: NOP; NOP (not LABEL: NOP; LABEL: NOP)
     */
    @Test
    @Tag("unit")
    void testCaretSyntaxWithLabel() {
        // Arrange
        String source = "LOOP: NOP^2";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());
        List<Token> initialTokens = lexer.scanTokens();
        PreProcessor preProcessor = createPreProcessor(initialTokens, diagnostics);

        // Act
        List<Token> expandedTokens = preProcessor.expand().tokens();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        List<TokenType> types = expandedTokens.stream().map(Token::type).toList();
        // .LABEL LOOP NOP NEWLINE NOP EOF
        // The label should NOT be repeated, only the NOP instruction
        assertThat(types).containsExactly(
                TokenType.DIRECTIVE,   // .LABEL
                TokenType.IDENTIFIER,  // LOOP
                TokenType.OPCODE,      // NOP
                TokenType.NEWLINE,
                TokenType.OPCODE,      // NOP
                TokenType.END_OF_FILE
        );
    }

    /**
     * Tests caret syntax with label and instruction with arguments.
     * LOOP: JMPI START^2 should become LOOP: JMPI START; JMPI START
     */
    @Test
    @Tag("unit")
    void testCaretSyntaxWithLabelAndArguments() {
        // Arrange
        String source = "LOOP: JMPI START^2";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());
        List<Token> initialTokens = lexer.scanTokens();
        PreProcessor preProcessor = createPreProcessor(initialTokens, diagnostics);

        // Act
        List<Token> expandedTokens = preProcessor.expand().tokens();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        List<TokenType> types = expandedTokens.stream().map(Token::type).toList();
        // .LABEL LOOP JMPI START NEWLINE JMPI START EOF
        assertThat(types).containsExactly(
                TokenType.DIRECTIVE,   // .LABEL
                TokenType.IDENTIFIER,  // LOOP
                TokenType.OPCODE,      // JMPI
                TokenType.IDENTIFIER,  // START
                TokenType.NEWLINE,
                TokenType.OPCODE,      // JMPI
                TokenType.IDENTIFIER,  // START
                TokenType.END_OF_FILE
        );
    }
}
