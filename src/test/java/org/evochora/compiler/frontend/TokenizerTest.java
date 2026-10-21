package org.evochora.compiler.frontend;

import org.evochora.compiler.FeatureRegistry;
import org.evochora.compiler.TestLexers;
import org.evochora.compiler.frontend.lexer.Tokenizer;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Contains unit tests for the {@link Lexer}.
 * These tests verify that the lexer correctly converts source code strings into a stream of tokens,
 * identifying different token types and handling features like comments.
 * These are unit tests and do not require external resources.
 */
public class TokenizerTest {

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    /**
     * A configured value is lexed on its own: its tokens stand under the name of the value, on
     * line 0, in the root placement, with the end of file kept.
     */
    @Test
    @Tag("unit")
    void lexValuesPutsTheTokensOfAValueUnderItsNameInTheRootPlacement() {
        Map<String, List<Token>> tokens = Tokenizer.lexValues(Map.of("STEPS", "DATA:20"), "ROOT",
                new RuntimeInstructionSetAdapter(), TestLexers.symbols());

        assertThat(tokens.get("STEPS")).extracting(Token::type)
                .containsExactly(TokenType.IDENTIFIER, TokenType.COLON, TokenType.NUMBER, TokenType.END_OF_FILE);
        assertThat(tokens.get("STEPS")).extracting(t -> t.source().fileName()).containsOnly("<value STEPS>");
        assertThat(tokens.get("STEPS")).extracting(t -> t.source().lineNumber()).containsOnly(0);
        assertThat(tokens.get("STEPS")).extracting(t -> t.source().placement()).containsOnly("ROOT");
    }

    /**
     * A text the lexer rejects yields no tokens and reports nothing here; the parser's reader of
     * the requirement reports it in its own words.
     */
    @Test
    @Tag("unit")
    void lexValuesGivesNoTokensForATextTheLexerRejects() {
        Map<String, List<Token>> tokens = Tokenizer.lexValues(Map.of("STEPS", "DATA:2\"0"), "ROOT",
                new RuntimeInstructionSetAdapter(), TestLexers.symbols());

        assertThat(tokens.get("STEPS")).isEmpty();
    }

    /**
     * Verifies that the relative marker is the name {@code @} followed by the symbol of its sign,
     * and that the number after it is read as an unsigned number.
     * This is a unit test for the lexer.
     */
    @Test
    @Tag("unit")
    void testRelativeMarkerIsTheNameAtFollowedByItsSign() {
        // Arrange
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Tokenizer lexer = TestLexers.tokenizer(".ORG @+2|@-3", diagnostics);

        // Act
        List<Token> tokens = lexer.scanTokens();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(tokens).extracting(Token::type, Token::text).containsExactly(
                tuple(TokenType.DIRECTIVE, ".ORG"),
                tuple(TokenType.IDENTIFIER, "@"),
                tuple(TokenType.SYMBOL, "+"),
                tuple(TokenType.NUMBER, "2"),
                tuple(TokenType.PIPE, "|"),
                tuple(TokenType.IDENTIFIER, "@"),
                tuple(TokenType.SYMBOL, "-"),
                tuple(TokenType.NUMBER, "3"),
                tuple(TokenType.END_OF_FILE, ""));
    }

    /**
     * Verifies that {@code @} alone is a name token of its own and never joins the word after it.
     * This is a unit test for the lexer.
     */
    @Test
    @Tag("unit")
    void testAtAloneIsAnIdentifierToken() {
        // Arrange
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Tokenizer lexer = TestLexers.tokenizer("@ @FOO", diagnostics);

        // Act
        List<Token> tokens = lexer.scanTokens();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(tokens).extracting(Token::type, Token::text).containsExactly(
                tuple(TokenType.IDENTIFIER, "@"),
                tuple(TokenType.IDENTIFIER, "@"),
                tuple(TokenType.IDENTIFIER, "FOO"),
                tuple(TokenType.END_OF_FILE, ""));
    }

    /**
     * Verifies that a minus is always a symbol token of its own and that a number carries no
     * sign.
     * This is a unit test for the lexer.
     */
    @Test
    @Tag("unit")
    void testMinusIsASymbolAndNumbersCarryNoSign() {
        // Arrange
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Tokenizer lexer = TestLexers.tokenizer("-5 A-1 - 2", diagnostics);

        // Act
        List<Token> tokens = lexer.scanTokens();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(tokens).extracting(Token::type, Token::text).containsExactly(
                tuple(TokenType.SYMBOL, "-"),
                tuple(TokenType.NUMBER, "5"),
                tuple(TokenType.IDENTIFIER, "A"),
                tuple(TokenType.SYMBOL, "-"),
                tuple(TokenType.NUMBER, "1"),
                tuple(TokenType.SYMBOL, "-"),
                tuple(TokenType.NUMBER, "2"),
                tuple(TokenType.END_OF_FILE, ""));
    }

    /**
     * Verifies that parentheses, brackets and the comma are tokens of their own types.
     * This is a unit test for the lexer.
     */
    @Test
    @Tag("unit")
    void testParenthesesBracketsAndCommaAreFixedTokens() {
        // Arrange
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Tokenizer lexer = TestLexers.tokenizer("(A)[0],B", diagnostics);

        // Act
        List<Token> tokens = lexer.scanTokens();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(tokens).extracting(Token::type).containsExactly(
                TokenType.OPEN_PARENTHESIS,
                TokenType.IDENTIFIER,
                TokenType.CLOSE_PARENTHESIS,
                TokenType.OPEN_BRACKET,
                TokenType.NUMBER,
                TokenType.CLOSE_BRACKET,
                TokenType.COMMA,
                TokenType.IDENTIFIER,
                TokenType.END_OF_FILE);
    }

    /**
     * Verifies that a parenthesis touching the identifier or opcode before it opens a call, while
     * one after a space, after a closing parenthesis or after a number opens a group.
     * This is a unit test for the lexer.
     */
    @Test
    @Tag("unit")
    void testCallParenthesisTouchesTheWordBeforeIt() {
        // Arrange
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Tokenizer lexer = TestLexers.tokenizer("MIN(A) NOT(B) NAME (C) )( 5(", diagnostics);

        // Act
        List<Token> tokens = lexer.scanTokens();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(tokens).extracting(Token::type).containsExactly(
                TokenType.IDENTIFIER, TokenType.CALL_PARENTHESIS, TokenType.IDENTIFIER, TokenType.CLOSE_PARENTHESIS,
                TokenType.OPCODE, TokenType.CALL_PARENTHESIS, TokenType.IDENTIFIER, TokenType.CLOSE_PARENTHESIS,
                TokenType.IDENTIFIER, TokenType.OPEN_PARENTHESIS, TokenType.IDENTIFIER, TokenType.CLOSE_PARENTHESIS,
                TokenType.CLOSE_PARENTHESIS, TokenType.OPEN_PARENTHESIS,
                TokenType.NUMBER, TokenType.OPEN_PARENTHESIS,
                TokenType.END_OF_FILE);
    }

    /**
     * Verifies that a name stops before two dots, while a single dot inside a name stays part of
     * it.
     * This is a unit test for the lexer.
     */
    @Test
    @Tag("unit")
    void testNameStopsBeforeTwoDots() {
        // Arrange
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Tokenizer lexer = TestLexers.tokenizer("OUT_NW..OUT_SE LIB.LABEL", diagnostics);

        // Act
        List<Token> tokens = lexer.scanTokens();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(tokens).extracting(Token::type, Token::text).containsExactly(
                tuple(TokenType.IDENTIFIER, "OUT_NW"),
                tuple(TokenType.SYMBOL, ".."),
                tuple(TokenType.IDENTIFIER, "OUT_SE"),
                tuple(TokenType.IDENTIFIER, "LIB.LABEL"),
                tuple(TokenType.END_OF_FILE, ""));
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
        Tokenizer lexer = TestLexers.tokenizer("NOP ?", diagnostics);

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
        Tokenizer lexer = TestLexers.tokenizer("* + ^ ..", diagnostics);

        // Act
        List<Token> tokens = lexer.scanTokens();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(tokens).extracting(Token::type, Token::text).containsExactly(
                tuple(TokenType.SYMBOL, "*"),
                tuple(TokenType.SYMBOL, "+"),
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
        List<Token> joined = new Tokenizer("<=", diagnostics, "<memory>", new RuntimeInstructionSetAdapter(), registry.lexerSymbols()).scanTokens();
        List<Token> apart = new Tokenizer("< =", diagnostics, "<memory>", new RuntimeInstructionSetAdapter(), registry.lexerSymbols()).scanTokens();

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
        new Tokenizer("< 1", diagnostics, "<memory>", new RuntimeInstructionSetAdapter(), registry.lexerSymbols()).scanTokens();

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
        Tokenizer lexer = TestLexers.tokenizer("1..10 .X", diagnostics);

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
     * Verifies that hexadecimal and binary literals are read as one number token, and that a
     * minus before one is a symbol token of its own.
     * This is a unit test for the lexer.
     */
    @Test
    @Tag("unit")
    void aPrefixedNumberEndsBeforeTheRangeSymbol() {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Tokenizer lexer = TestLexers.tokenizer("0x1F..3", diagnostics);

        List<Token> tokens = lexer.scanTokens();

        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(tokens).extracting(Token::type, Token::text).containsExactly(
                tuple(TokenType.NUMBER, "0x1F"),
                tuple(TokenType.SYMBOL, ".."),
                tuple(TokenType.NUMBER, "3"),
                tuple(TokenType.END_OF_FILE, "")
        );
    }

    @Test
    @Tag("unit")
    void testHexAndBinaryLiteralsWithAndWithoutSign() {
        // Arrange
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Tokenizer lexer = TestLexers.tokenizer("-0x10 -0b11 0x10 0b11 0o17 -0O17", diagnostics);

        // Act
        List<Token> tokens = lexer.scanTokens();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(tokens).extracting(Token::type, Token::value).containsExactly(
                tuple(TokenType.SYMBOL, null),
                tuple(TokenType.NUMBER, 16L),
                tuple(TokenType.SYMBOL, null),
                tuple(TokenType.NUMBER, 3L),
                tuple(TokenType.NUMBER, 16L),
                tuple(TokenType.NUMBER, 3L),
                tuple(TokenType.NUMBER, 15L),
                tuple(TokenType.SYMBOL, null),
                tuple(TokenType.NUMBER, 15L),
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
        Tokenizer lexer = TestLexers.tokenizer(source, diagnostics);

        // Act
        List<Token> tokens = lexer.scanTokens();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(tokens).hasSize(10);
        assertThat(tokens.get(0)).extracting(Token::type, Token::text).containsExactly(TokenType.DIRECTIVE, ".CONST");
        assertThat(tokens.get(1)).extracting(Token::type, Token::text).containsExactly(TokenType.IDENTIFIER, "HELLO");
        assertThat(tokens.get(2)).extracting(Token::type, Token::text, Token::value).containsExactly(TokenType.NUMBER, "42", 42L);
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
        Tokenizer lexer = TestLexers.tokenizer(source, diagnostics);

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
        Tokenizer lexer = TestLexers.tokenizer(source, diagnostics);

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
        Tokenizer lexer = TestLexers.tokenizer(source, diagnostics);

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
