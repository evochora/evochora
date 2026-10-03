package org.evochora.compiler.frontend.preprocessor;

import org.evochora.compiler.TestLexers;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.util.SourceRootResolver;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the placement of the main file's tokens: the lexer leaves it empty, and the
 * preprocessor gives every token the placement of the compilation root.
 */
@Tag("unit")
class PreProcessorRootPlacementTest {

    @Test
    void theTokensOfTheMainFileStandInTheRootPlacement() {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<Token> tokens = new Lexer("NOP\nNOP\n", diagnostics, "/p/main.evo", TestLexers.symbols()).scanTokens();
        assertThat(tokens).allSatisfy(token -> assertThat(token.source().placement()).isEmpty());

        PreProcessorContext context = new PreProcessorContext("PRED", Map.of(), "/p/main.evo", CompilerOptions.defaults());
        List<Token> expanded = new PreProcessor(tokens, diagnostics,
                new SourceRootResolver(List.of(new SourceRoot(".", null)), Path.of("/p")), context).expand().tokens();

        assertThat(expanded).hasSize(tokens.size())
                .allSatisfy(token -> assertThat(token.source().placement()).isEqualTo("PRED"));
    }
}
