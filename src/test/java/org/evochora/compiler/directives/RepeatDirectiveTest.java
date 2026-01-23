package org.evochora.compiler.directives;

import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
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

    /**
     * Tests inline mode: .REPEAT n INSTRUCTION
     * Should expand to n copies of the instruction separated by NEWLINEs.
     */
    @Test
    @Tag("unit")
    void testInlineRepeatSingleInstruction() {
        // Arrange
        String source = ".REPEAT 3 NOP";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> initialTokens = lexer.scanTokens();
        PreProcessor preProcessor = new PreProcessor(initialTokens, diagnostics, Path.of(""));

        // Act
        List<Token> expandedTokens = preProcessor.expand();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        List<TokenType> types = expandedTokens.stream().map(Token::type).toList();
        // NOP NEWLINE NOP NEWLINE NOP EOF
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
     * Tests inline mode with instruction that has arguments: .REPEAT n JMPI LABEL
     */
    @Test
    @Tag("unit")
    void testInlineRepeatWithArguments() {
        // Arrange
        String source = ".REPEAT 2 JMPI LOOP";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> initialTokens = lexer.scanTokens();
        PreProcessor preProcessor = new PreProcessor(initialTokens, diagnostics, Path.of(""));

        // Act
        List<Token> expandedTokens = preProcessor.expand();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        List<TokenType> types = expandedTokens.stream().map(Token::type).toList();
        // JMPI LOOP NEWLINE JMPI LOOP EOF
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
     * Tests block mode: .REPEAT n; ... .ENDR
     */
    @Test
    @Tag("unit")
    void testBlockRepeat() {
        // Arrange: semicolons become NEWLINEs in the lexer
        String source = ".REPEAT 2; JMPI LOOP; NOP; .ENDR";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> initialTokens = lexer.scanTokens();
        PreProcessor preProcessor = new PreProcessor(initialTokens, diagnostics, Path.of(""));

        // Act
        List<Token> expandedTokens = preProcessor.expand();

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
                ".ENDR"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> initialTokens = lexer.scanTokens();
        PreProcessor preProcessor = new PreProcessor(initialTokens, diagnostics, Path.of(""));

        // Act
        List<Token> expandedTokens = preProcessor.expand();

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
     * Tests that .REPEAT 0 produces no output.
     */
    @Test
    @Tag("unit")
    void testRepeatZero() {
        // Arrange
        String source = ".REPEAT 0 NOP";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> initialTokens = lexer.scanTokens();
        PreProcessor preProcessor = new PreProcessor(initialTokens, diagnostics, Path.of(""));

        // Act
        List<Token> expandedTokens = preProcessor.expand();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        List<TokenType> types = expandedTokens.stream().map(Token::type).toList();
        assertThat(types).containsExactly(TokenType.END_OF_FILE);
    }

    /**
     * Tests inline mode with context: instructions before and after.
     */
    @Test
    @Tag("unit")
    void testInlineRepeatWithContext() {
        // Arrange
        String source = "JMPI START; .REPEAT 3 NOP; JMPI END";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> initialTokens = lexer.scanTokens();
        PreProcessor preProcessor = new PreProcessor(initialTokens, diagnostics, Path.of(""));

        // Act
        List<Token> expandedTokens = preProcessor.expand();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        List<TokenType> types = expandedTokens.stream().map(Token::type).toList();
        // JMPI START NEWLINE NOP NEWLINE NOP NEWLINE NOP NEWLINE JMPI END EOF
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
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> initialTokens = lexer.scanTokens();
        PreProcessor preProcessor = new PreProcessor(initialTokens, diagnostics, Path.of(""));

        // Act
        List<Token> expandedTokens = preProcessor.expand();

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
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> initialTokens = lexer.scanTokens();
        PreProcessor preProcessor = new PreProcessor(initialTokens, diagnostics, Path.of(""));

        // Act
        List<Token> expandedTokens = preProcessor.expand();

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
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> initialTokens = lexer.scanTokens();
        PreProcessor preProcessor = new PreProcessor(initialTokens, diagnostics, Path.of(""));

        // Act
        List<Token> expandedTokens = preProcessor.expand();

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
        Lexer lexer = new Lexer(source, diagnostics);
        List<Token> initialTokens = lexer.scanTokens();
        PreProcessor preProcessor = new PreProcessor(initialTokens, diagnostics, Path.of(""));

        // Act
        List<Token> expandedTokens = preProcessor.expand();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        List<TokenType> types = expandedTokens.stream().map(Token::type).toList();
        assertThat(types).containsExactly(TokenType.END_OF_FILE);
    }
}
