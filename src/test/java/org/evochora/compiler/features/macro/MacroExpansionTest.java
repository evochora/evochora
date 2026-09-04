package org.evochora.compiler.features.macro;

import java.nio.file.Path;
import java.util.List;

import org.evochora.compiler.api.SourceRoot;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.util.SourceRootResolver;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a macro invocation expands to, checked on the token stream the preprocessor leaves
 * behind: parameters of every operand form, nesting, the order of definition and use, and
 * the cases the preprocessor has to reject.
 */
@Tag("unit")
class MacroExpansionTest {

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void expandsMacroWithoutParameters() {
        Expansion result = expand(
                ".MACRO PAUSE",
                "  NOP",
                "  NOP",
                ".ENDM",
                "PAUSE");

        assertThat(result.diagnostics.hasErrors()).isFalse();
        assertThat(result.texts()).containsExactly("NOP", "NOP");
    }

    @Test
    void substitutesVectorArgumentAsAWhole() {
        Expansion result = expand(
                ".MACRO STEP DIR",
                "  SEKI DIR",
                ".ENDM",
                "STEP 1|0");

        assertThat(result.diagnostics.hasErrors()).isFalse();
        assertThat(result.texts()).containsExactly("SEKI", "1", "|", "0");
    }

    @Test
    void substitutesTypedLiteralArgumentAsAWhole() {
        Expansion result = expand(
                ".MACRO SET REG VALUE",
                "  SETI REG VALUE",
                ".ENDM",
                "SET %DR0 DATA:5");

        assertThat(result.diagnostics.hasErrors()).isFalse();
        assertThat(result.texts()).containsExactly("SETI", "%DR0", "DATA", ":", "5");
    }

    @Test
    void expandsMacroInvokedFromAnotherMacroBody() {
        Expansion result = expand(
                ".MACRO INC REG",
                "  ADDI REG DATA:1",
                ".ENDM",
                ".MACRO INC2 REG",
                "  INC REG",
                "  INC REG",
                ".ENDM",
                "INC2 %DR0");

        assertThat(result.diagnostics.hasErrors()).isFalse();
        assertThat(result.texts()).containsExactly(
                "ADDI", "%DR0", "DATA", ":", "1",
                "ADDI", "%DR0", "DATA", ":", "1");
    }

    @Test
    void invocationIsCaseInsensitive() {
        Expansion result = expand(
                ".MACRO INC REG",
                "  ADDI REG DATA:1",
                ".ENDM",
                "inc %DR0");

        assertThat(result.diagnostics.hasErrors()).isFalse();
        assertThat(result.texts()).containsExactly("ADDI", "%DR0", "DATA", ":", "1");
    }

    @Test
    void nameUsedBeforeItsDefinitionIsNotExpanded() {
        Expansion result = expand(
                "INC %DR0",
                ".MACRO INC REG",
                "  ADDI REG DATA:1",
                ".ENDM");

        // The preprocessor leaves the name as it found it; a later phase rejects the unknown
        // statement.
        assertThat(result.diagnostics.hasErrors()).isFalse();
        assertThat(result.texts()).containsExactly("INC", "%DR0");
    }

    @Test
    void secondDefinitionOfANameIsRejectedAndTheFirstStaysInForce() {
        Expansion result = expand(
                ".MACRO INC REG",
                "  ADDI REG DATA:1",
                ".ENDM",
                ".MACRO INC REG",
                "  ADDI REG DATA:1",
                ".ENDM",
                "INC %DR0");

        assertThat(result.diagnostics.hasErrors()).isTrue();
        assertThat(result.diagnostics.summary()).contains("Cannot define macro 'INC': the name is already used at");
        assertThat(result.texts()).containsExactly("ADDI", "%DR0", "DATA", ":", "1");
    }

    @Test
    void wrongArgumentCountIsReportedAndTheInvocationRemoved() {
        Expansion result = expand(
                ".MACRO INC REG",
                "  ADDI REG DATA:1",
                ".ENDM",
                "INC %DR0 %DR1");

        assertThat(result.diagnostics.hasErrors()).isTrue();
        assertThat(result.diagnostics.summary()).contains("Macro 'INC' expects 1 arguments, but got 2");
        assertThat(result.texts()).isEmpty();
    }

    private record Expansion(List<Token> tokens, DiagnosticsEngine diagnostics) {
        /** The texts of the expanded tokens, without newlines and the end marker. */
        List<String> texts() {
            return tokens.stream()
                    .filter(t -> t.type() != TokenType.NEWLINE && t.type() != TokenType.END_OF_FILE)
                    .map(Token::text)
                    .toList();
        }
    }

    private static Expansion expand(String... lines) {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(String.join("\n", lines) + "\n", diagnostics);
        List<Token> tokens = lexer.scanTokens();
        PreProcessorContext context = new PreProcessorContext();
        context.handlers().register(".MACRO", new MacroDirectiveHandler());
        PreProcessor preProcessor = new PreProcessor(tokens, diagnostics,
                new SourceRootResolver(List.of(new SourceRoot(".", null)), Path.of("")),
                context);
        return new Expansion(preProcessor.expand().tokens(), diagnostics);
    }
}
