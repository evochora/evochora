package org.evochora.compiler.frontend;

import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.lexer.Token;
import org.evochora.compiler.frontend.lexer.TokenType;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.evochora.runtime.isa.Instruction;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link PreProcessor}, focusing on file inclusion capabilities.
 * These tests are tagged as "integration" because they require filesystem access
 * to handle the `.INCLUDE` directive.
 */
public class PreProcessorTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        Instruction.init();
    }

    /**
     * Verifies that the preprocessor correctly expands an `.INCLUDE` directive.
     * The test creates a temporary source file and a main file that includes it.
     * It then asserts that the preprocessor replaces the include directive with the
     * tokens from the included file, properly wrapped in `.PUSH_CTX` and `.POP_CTX`
     * directives to manage context.
     * <p>
     * This is an integration test because it relies on the filesystem.
     *
     * @throws IOException if there is an error writing the temporary files.
     */
    @Test
    @Tag("integration")
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

        // We now expect 5 tokens: PUSH_CTX, NOP, NEWLINE, POP_CTX, and the final END_OF_FILE.
        // The extra NEWLINE is due to the consistent normalization in IncludeDirectiveHandler which ensures files end with \n.
        assertThat(expandedTokens).hasSize(5);
        assertThat(expandedTokens.get(0).type()).isEqualTo(TokenType.DIRECTIVE);
        assertThat(expandedTokens.get(0).text()).isEqualTo(".PUSH_CTX");
        assertThat(expandedTokens.get(1).type()).isEqualTo(TokenType.OPCODE);
        assertThat(expandedTokens.get(1).text()).isEqualTo("NOP");
        assertThat(expandedTokens.get(2).type()).isEqualTo(TokenType.NEWLINE);
        assertThat(expandedTokens.get(3).type()).isEqualTo(TokenType.DIRECTIVE);
        assertThat(expandedTokens.get(3).text()).isEqualTo(".POP_CTX");
        assertThat(expandedTokens.get(4).type()).isEqualTo(TokenType.END_OF_FILE);
    }
}