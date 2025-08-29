package org.evochora.compiler.directives;

import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the preprocessor's handling of macro definitions and expansions.
 * These are unit tests and do not require external resources.
 */
public class MacroDirectiveTest {

    /**
     * Verifies that the {@link PreProcessor} correctly expands a macro invocation.
     * The test defines a macro, invokes it, and then checks that the resulting
     * token stream matches the expected expanded output, with parameters correctly substituted.
     * This is a unit test for the preprocessor.
     */
    @Test
    @Tag("unit")
    void testMacroExpansion() {
        // Arrange
        String source = String.join("\n",
                ".MACRO INCREMENT REG",
                "  ADDI REG DATA:1",
                ".ENDM",
                "INCREMENT %DR0"
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
        assertThat(types).containsExactly(
                TokenType.OPCODE,    // ADDI
                TokenType.REGISTER,  // %DR0
                TokenType.IDENTIFIER,  // DATA
                TokenType.COLON,       // :
                TokenType.NUMBER,      // 1
                TokenType.NEWLINE,
                TokenType.END_OF_FILE
        );
    }
}
