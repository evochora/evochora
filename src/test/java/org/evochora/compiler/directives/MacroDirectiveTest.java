package org.evochora.compiler.directives;

import org.evochora.compiler.core.phases.Lexer;
import org.evochora.compiler.core.Token;
import org.evochora.compiler.core.TokenType;
import org.evochora.compiler.core.phases.PreProcessor;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class MacroDirectiveTest {

    @Test
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
