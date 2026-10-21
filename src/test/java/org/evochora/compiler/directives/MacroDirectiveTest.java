package org.evochora.compiler.directives;

import java.util.Map;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.TestRegistries;
import org.evochora.compiler.TestLexers;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.compiler.frontend.lexer.Tokenizer;
import org.evochora.compiler.util.SourceRootResolver;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
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

    @BeforeAll
    static void setUp() {
        Instruction.init();
    }

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
                ".ENDMACRO",
                "INCREMENT %DR0"
        );
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Tokenizer lexer = TestLexers.tokenizer(source, diagnostics);
        List<Token> initialTokens = lexer.scanTokens();
        PreProcessorContext context = new PreProcessorContext("", Map.of(), "<memory>", CompilerOptions.defaults());
        TestRegistries.registerPreProcessorBlocks(context.handlers());
        context.handlers().register(":", new org.evochora.compiler.features.label.ColonLabelHandler());
        PreProcessor preProcessor = new PreProcessor(initialTokens, diagnostics,
                new SourceRootResolver(List.of(new SourceRoot(".", null)), Path.of("")),
                context);

        // Act
        List<Token> expandedTokens = preProcessor.expand().tokens();

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

    /**
     * A name a computation reads as an operation, or the cursor, cannot be the name of a macro
     * or of one of its parameters; the letter case does not matter.
     */
    @Test
    @Tag("unit")
    void aReservedNameCannotBeAMacroNameOrAParameterName() {
        assertThat(macroErrors(".MACRO number X", "  NOP", ".ENDMACRO"))
                .containsExactly("'number' is a function and cannot be a macro name.");
        assertThat(macroErrors(".MACRO SHL X", "  NOP", ".ENDMACRO"))
                .containsExactly("'SHL' is an operator and cannot be a macro name.");
        assertThat(macroErrors(".MACRO @ X", "  NOP", ".ENDMACRO"))
                .containsExactly("'@' cannot be a macro name.");
        assertThat(macroErrors(".MACRO SET TYPE @", "  NOP", ".ENDMACRO"))
                .containsExactly("'TYPE' is a function and cannot be a parameter name.",
                        "'@' cannot be a parameter name.");
    }

    private static List<String> macroErrors(String... lines) {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<Token> tokens = TestLexers.tokenizer(String.join("\n", lines), diagnostics).scanTokens();
        PreProcessorContext context = new PreProcessorContext("", Map.of(), Map.of(), "<memory>", "<memory>",
                CompilerOptions.defaults(), TestRegistries.operations().reservedNames());
        TestRegistries.registerPreProcessorBlocks(context.handlers());
        new PreProcessor(tokens, diagnostics, new SourceRootResolver(List.of(new SourceRoot(".", null)), Path.of("")), context)
                .expand();
        return diagnostics.getDiagnostics().stream().map(d -> d.message()).toList();
    }
}
