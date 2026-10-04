package org.evochora.compiler.frontend;

import org.evochora.compiler.FeatureRegistry;
import org.evochora.compiler.TestLexers;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Contains unit tests for the {@link Lexer}.
 * These tests verify that the lexer correctly converts source code strings into a stream of tokens,
 * identifying different token types and handling features like comments.
 * These are unit tests and do not require external resources.
 */
public class LexerTest {

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    /**
     * Verifies that the relative marker is one symbol token carrying its sign, so that the number
     * after it is read as an unsigned number.
     * This is a unit test for the lexer.
     */
    @Test
    @Tag("unit")
    void testRelativeMarkerIsOneTokenWithItsSign() {
        // Arrange
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(".ORG @+2|@-3", diagnostics, TestLexers.symbols());

        // Act
        List<Token> tokens = lexer.scanTokens();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(tokens.get(1)).extracting(Token::type, Token::text).containsExactly(TokenType.SYMBOL, "@+");
        assertThat(tokens.get(2)).extracting(Token::type, Token::value).containsExactly(TokenType.NUMBER, 2);
        assertThat(tokens.get(3)).extracting(Token::type).isEqualTo(TokenType.PIPE);
        assertThat(tokens.get(4)).extracting(Token::type, Token::text).containsExactly(TokenType.SYMBOL, "@-");
        assertThat(tokens.get(5)).extracting(Token::type, Token::value).containsExactly(TokenType.NUMBER, 3);
    }

    /**
     * Verifies that the marker character without a sign is rejected and that the message names
     * the registered symbols beginning with it.
     * This is a unit test for the lexer.
     */
    @Test
    @Tag("unit")
    void testMarkerWithoutSignNamesTheSymbolsBeginningWithIt() {
        // Arrange
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(".ORG 0|@2", diagnostics, TestLexers.symbols());

        // Act
        lexer.scanTokens();

        // Assert
        assertThat(diagnostics.hasErrors()).isTrue();
        assertThat(diagnostics.summary())
                .contains("Unexpected character '@'; symbols beginning with it are '@+' and '@-'");
    }

    /**
     * Verifies that a character no symbol begins with keeps the plain message.
     * This is a unit test for the lexer.
     */
    @Test
    @Tag("unit")
    void testCharacterWithoutSymbolKeepsThePlainMessage() {
        // Arrange
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer("NOP ?", diagnostics, TestLexers.symbols());

        // Act
        lexer.scanTokens();

        // Assert
        assertThat(diagnostics.summary()).contains("Unexpected character: ?");
    }

    /**
     * Verifies that the symbols of the standard features are lexed as symbol tokens with their
     * text.
     * This is a unit test for the lexer.
     */
    @Test
    @Tag("unit")
    void testStandardSymbolsAreSymbolTokens() {
        // Arrange
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer("* , @+ @- ^ ..", diagnostics, TestLexers.symbols());

        // Act
        List<Token> tokens = lexer.scanTokens();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(tokens).extracting(Token::type, Token::text).containsExactly(
                tuple(TokenType.SYMBOL, "*"),
                tuple(TokenType.SYMBOL, ","),
                tuple(TokenType.SYMBOL, "@+"),
                tuple(TokenType.SYMBOL, "@-"),
                tuple(TokenType.SYMBOL, "^"),
                tuple(TokenType.SYMBOL, ".."),
                tuple(TokenType.END_OF_FILE, "")
        );
    }

    /**
     * Verifies that the longest registered symbol wins, and that a symbol is not assembled across
     * whitespace.
     * This is a unit test for the lexer.
     */
    @Test
    @Tag("unit")
    void testLongestSymbolWins() {
        // Arrange
        FeatureRegistry registry = new FeatureRegistry(new RuntimeInstructionSetAdapter());
        registry.lexerSymbol("<");
        registry.lexerSymbol("<=");
        registry.lexerSymbol("=");
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();

        // Act
        List<Token> joined = new Lexer("<=", diagnostics, registry.lexerSymbols()).scanTokens();
        List<Token> apart = new Lexer("< =", diagnostics, registry.lexerSymbols()).scanTokens();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(joined).extracting(Token::type, Token::text).containsExactly(
                tuple(TokenType.SYMBOL, "<="),
                tuple(TokenType.END_OF_FILE, ""));
        assertThat(apart).extracting(Token::type, Token::text).containsExactly(
                tuple(TokenType.SYMBOL, "<"),
                tuple(TokenType.SYMBOL, "="),
                tuple(TokenType.END_OF_FILE, ""));
    }

    /**
     * Verifies that the message names a single symbol beginning with the unexpected character in
     * the singular.
     * This is a unit test for the lexer.
     */
    @Test
    @Tag("unit")
    void testMessageNamesTheOneSymbolBeginningWithTheCharacter() {
        // Arrange
        FeatureRegistry registry = new FeatureRegistry(new RuntimeInstructionSetAdapter());
        registry.lexerSymbol("<=");
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();

        // Act
        new Lexer("< 1", diagnostics, registry.lexerSymbols()).scanTokens();

        // Assert
        assertThat(diagnostics.summary())
                .contains("Unexpected character '<'; the symbol beginning with it is '<='");
    }

    /**
     * Verifies that two dots between numbers are the range symbol while a dot before a word
     * still opens a directive.
     * This is a unit test for the lexer.
     */
    @Test
    @Tag("unit")
    void testRangeSymbolAgainstDirective() {
        // Arrange
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer("1..10 .X", diagnostics, TestLexers.symbols());

        // Act
        List<Token> tokens = lexer.scanTokens();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(tokens).extracting(Token::type, Token::text).containsExactly(
                tuple(TokenType.NUMBER, "1"),
                tuple(TokenType.SYMBOL, ".."),
                tuple(TokenType.NUMBER, "10"),
                tuple(TokenType.DIRECTIVE, ".X"),
                tuple(TokenType.END_OF_FILE, "")
        );
    }

    /**
     * Verifies that hexadecimal and binary literals are read as one number token, with and
     * without a leading minus.
     * This is a unit test for the lexer.
     */
    @Test
    @Tag("unit")
    void testHexAndBinaryLiteralsWithAndWithoutSign() {
        // Arrange
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer("-0x10 -0b11 0x10 0b11", diagnostics, TestLexers.symbols());

        // Act
        List<Token> tokens = lexer.scanTokens();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(tokens).extracting(Token::type, Token::value).containsExactly(
                tuple(TokenType.NUMBER, -16),
                tuple(TokenType.NUMBER, -3),
                tuple(TokenType.NUMBER, 16),
                tuple(TokenType.NUMBER, 3),
                tuple(TokenType.END_OF_FILE, null)
        );
    }

    /**
     * Verifies that the lexer correctly tokenizes a source string containing various language elements,
     * including directives, identifiers, numbers, labels, opcodes, registers, and comments.
     * The test asserts that the resulting token stream has the correct types and values.
     * This is a unit test for the lexer.
     */
    @Test
    @Tag("unit")
    void testLexerTokenization() {
        // Arrange
        String source = String.join("\n",
                ".CONST HELLO 42",
                "L1: SETI %DR0 HELLO # Lade 42"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());

        // Act
        List<Token> tokens = lexer.scanTokens();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(tokens).hasSize(10);
        assertThat(tokens.get(0)).extracting(Token::type, Token::text).containsExactly(TokenType.DIRECTIVE, ".CONST");
        assertThat(tokens.get(1)).extracting(Token::type, Token::text).containsExactly(TokenType.IDENTIFIER, "HELLO");
        assertThat(tokens.get(2)).extracting(Token::type, Token::text, Token::value).containsExactly(TokenType.NUMBER, "42", 42);
        assertThat(tokens.get(3)).extracting(Token::type).isEqualTo(TokenType.NEWLINE);
        assertThat(tokens.get(4)).extracting(Token::type, Token::text).containsExactly(TokenType.IDENTIFIER, "L1");
        assertThat(tokens.get(5)).extracting(Token::type, Token::text).containsExactly(TokenType.COLON, ":");
        assertThat(tokens.get(6)).extracting(Token::type, Token::text).containsExactly(TokenType.OPCODE, "SETI");
        assertThat(tokens.get(7)).extracting(Token::type, Token::text).containsExactly(TokenType.REGISTER, "%DR0");
        assertThat(tokens.get(8)).extracting(Token::type, Token::text).containsExactly(TokenType.IDENTIFIER, "HELLO");
        assertThat(tokens.get(9)).extracting(Token::type).isEqualTo(TokenType.END_OF_FILE);
    }

    /**
     * Specifically verifies that the lexer correctly identifies an instruction mnemonic like "SETI"
     * as a token of type {@link TokenType#OPCODE}.
     * This is a unit test for the lexer.
     */
    @Test
    @Tag("unit")
    void testSETIAsOpcode() {
        // Arrange
        String source = "SETI %DR0 DATA:42";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());

        // Act
        List<Token> tokens = lexer.scanTokens();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        
        // Find SETI token
        Token setiToken = tokens.stream()
            .filter(t -> t.text().equals("SETI"))
            .findFirst()
            .orElse(null);
        
        assertThat(setiToken).isNotNull();
        assertThat(setiToken.type()).isEqualTo(TokenType.OPCODE);
        assertThat(setiToken.text()).isEqualTo("SETI");
    }

    /**
     * Verifies that semicolons are tokenized as NEWLINE tokens, enabling multiple instructions per line.
     * This allows syntax like "NOP; SETI %DR0 1; NOP" to be parsed as three separate instructions.
     */
    @Test
    @Tag("unit")
    void testSemicolonAsStatementSeparator() {
        // Arrange
        String source = "NOP; SETI %DR0 1; NOP";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());

        // Act
        List<Token> tokens = lexer.scanTokens();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(tokens).extracting(Token::type).containsExactly(
                TokenType.OPCODE,    // NOP
                TokenType.NEWLINE,   // ;
                TokenType.OPCODE,    // SETI
                TokenType.REGISTER,  // %DR0
                TokenType.NUMBER,    // 1
                TokenType.NEWLINE,   // ;
                TokenType.OPCODE,    // NOP
                TokenType.END_OF_FILE
        );
    }

    /**
     * Verifies that semicolons and newlines can be mixed freely, allowing flexible formatting.
     */
    @Test
    @Tag("unit")
    void testMixedSemicolonsAndNewlines() {
        // Arrange
        String source = "NOP; NOP\nNOP";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics, TestLexers.symbols());

        // Act
        List<Token> tokens = lexer.scanTokens();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(tokens).extracting(Token::type).containsExactly(
                TokenType.OPCODE,    // NOP
                TokenType.NEWLINE,   // ;
                TokenType.OPCODE,    // NOP
                TokenType.NEWLINE,   // \n
                TokenType.OPCODE,    // NOP
                TokenType.END_OF_FILE
        );
    }
}
