package org.evochora.compiler.frontend;

import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.junit.jupiter.api.Tag;
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
    @Tag("unit")
    void testIncludeDirectiveExpandsTokens() throws IOException {
        // Arrange
        Path libFile = tempDir.resolve("test.s");
        Files.writeString(libFile, "NOP"); // Schreibt NUR "NOP", ohne Zeilenumbruch

        String mainSource = ".INCLUDE \"test.s\"";
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();

        Path mainFile = tempDir.resolve("main.s");
        Lexer lexer = new Lexer(mainSource, diagnostics, mainFile.toString());
        List<Token> initialTokens = lexer.scanTokens();

        PreProcessor preProcessor = new PreProcessor(initialTokens, diagnostics, tempDir);

        // Act
        List<Token> expandedTokens = preProcessor.expand();

        // Assert
        assertThat(diagnostics.hasErrors()).isFalse();

        // Print tokens for debugging
        expandedTokens.forEach(System.out::println);

        // We now expect 4 tokens: PUSH_CTX, NOP, POP_CTX, and the final END_OF_FILE.
        assertThat(expandedTokens).hasSize(4);
        assertThat(expandedTokens.get(0).type()).isEqualTo(TokenType.DIRECTIVE);
        assertThat(expandedTokens.get(0).text()).isEqualTo(".PUSH_CTX");
        assertThat(expandedTokens.get(1).type()).isEqualTo(TokenType.OPCODE);
        assertThat(expandedTokens.get(1).text()).isEqualTo("NOP");
        assertThat(expandedTokens.get(2).type()).isEqualTo(TokenType.DIRECTIVE);
        assertThat(expandedTokens.get(2).text()).isEqualTo(".POP_CTX");
        assertThat(expandedTokens.get(3).type()).isEqualTo(TokenType.END_OF_FILE);
    }
}