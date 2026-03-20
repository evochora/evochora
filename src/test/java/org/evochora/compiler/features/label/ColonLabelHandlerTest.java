package org.evochora.compiler.features.label;

import org.evochora.compiler.api.SourceRoot;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;
import org.evochora.compiler.frontend.preprocessor.PreProcessorHandlerRegistry;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.util.SourceRootResolver;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ColonLabelHandler}.
 */
@Tag("unit")
class ColonLabelHandlerTest {

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    private List<Token> preprocess(String source) {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(source, diagnostics, "test.s");
        List<Token> tokens = lexer.scanTokens();
        PreProcessorHandlerRegistry registry = new PreProcessorHandlerRegistry();
        registry.register(":", new ColonLabelHandler());
        PreProcessor pp = new PreProcessor(tokens, diagnostics,
                new SourceRootResolver(List.of(new SourceRoot(".", null)), Path.of("")),
                registry, new PreProcessorContext());
        return pp.expand().tokens();
    }

    @Test
    void identifierColon_rewritesToLabel() {
        List<Token> tokens = preprocess("LOOP: NOP");

        assertThat(tokens).extracting(Token::type)
                .containsSubsequence(TokenType.DIRECTIVE, TokenType.IDENTIFIER, TokenType.OPCODE);
        assertThat(tokens).extracting(Token::text)
                .containsSubsequence(".LABEL", "LOOP", "NOP");
    }

    @Test
    void identifierColonAtEndOfLine() {
        List<Token> tokens = preprocess("LOOP:\n");

        assertThat(tokens).extracting(Token::type)
                .containsSubsequence(TokenType.DIRECTIVE, TokenType.IDENTIFIER, TokenType.NEWLINE);
        assertThat(tokens).extracting(Token::text)
                .containsSubsequence(".LABEL", "LOOP");
    }

    @Test
    void typedLiteral_notRewritten() {
        List<Token> tokens = preprocess("NOP CODE:5");

        assertThat(tokens).extracting(Token::type)
                .containsSubsequence(TokenType.OPCODE, TokenType.IDENTIFIER, TokenType.COLON, TokenType.NUMBER);
        assertThat(tokens).extracting(Token::text)
                .containsSubsequence("NOP", "CODE");
    }

    @Test
    void registerColon_notRewritten() {
        List<Token> tokens = preprocess("NOP %DR0:");

        assertThat(tokens).extracting(Token::type)
                .contains(TokenType.REGISTER, TokenType.COLON);
        // Verify no .LABEL was injected
        assertThat(tokens).extracting(Token::type)
                .doesNotContain(TokenType.DIRECTIVE);
    }

    @Test
    void colonAtStreamStart_notRewritten() {
        List<Token> tokens = preprocess(": NOP");

        assertThat(tokens).extracting(Token::type)
                .containsSubsequence(TokenType.COLON, TokenType.OPCODE);
        assertThat(tokens).extracting(Token::type)
                .doesNotContain(TokenType.DIRECTIVE);
    }

    @Test
    void exportedLabel() {
        List<Token> tokens = preprocess("EXPORT LOOP: NOP");

        assertThat(tokens).extracting(Token::text)
                .containsSubsequence("EXPORT", ".LABEL", "LOOP", "NOP");
        assertThat(tokens).extracting(Token::type)
                .containsSubsequence(TokenType.IDENTIFIER, TokenType.DIRECTIVE, TokenType.IDENTIFIER, TokenType.OPCODE);
    }

    @Test
    void multipleLabelsAndLiterals() {
        List<Token> tokens = preprocess("LOOP: SETI %DR0 CODE:5");

        assertThat(tokens).extracting(Token::text)
                .containsSubsequence(".LABEL", "LOOP", "SETI");
        // Typed literal preserved
        assertThat(tokens).extracting(Token::type)
                .contains(TokenType.COLON);
    }
}
