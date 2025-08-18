package org.evochora.compiler.frontend;

import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class PreProcessorTest {

    @TempDir
    Path tempDir;

    @Test
    void testIncludeDirectiveExpandsTokens() throws IOException {
        // Arrange
        Path libFile = tempDir.resolve("test.s");
        Files.writeString(libFile, "NOP");

        String mainSource = ".INCLUDE \"test.s\"";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(mainSource, diagnostics);
        List<Token> initialTokens = lexer.scanTokens();

        PreProcessor preProcessor = new PreProcessor(initialTokens, diagnostics, tempDir);

        // Act
        List<Token> expandedTokens = preProcessor.expand();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();
        // Expected tokens: NOP, EOF
        assertThat(expandedTokens).hasSize(2);
        assertThat(expandedTokens.get(0).type()).isEqualTo(TokenType.OPCODE);
        assertThat(expandedTokens.get(0).text()).isEqualTo("NOP");
        assertThat(expandedTokens.get(1).type()).isEqualTo(TokenType.END_OF_FILE);
    }
}
