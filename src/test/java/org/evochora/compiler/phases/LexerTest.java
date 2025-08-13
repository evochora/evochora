package org.evochora.compiler.phases;

import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class LexerTest {

    @Test
    void testLexerTokenization() {
        // Arrange
        String source = String.join("\n",
                ".DEFINE HELLO 42",
                "L1: SETI %DR0 HELLO # Lade 42"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics);

        // Act
        List<Token> tokens = lexer.scanTokens();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        assertThat(tokens).hasSize(10);
        assertThat(tokens.get(0)).extracting(Token::type, Token::text).containsExactly(TokenType.DIRECTIVE, ".DEFINE");
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
}
