package org.evochora.compiler.features.macro;

import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.TestRegistries;
import org.evochora.compiler.TestLexers;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.evochora.compiler.api.SourceInfo;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.features.ctx.PopCtxPreProcessorHandler;
import org.evochora.compiler.features.repeat.RepeatDirectiveHandler;
import org.evochora.compiler.features.source.SourceDirectiveHandler;
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
                ".ENDMACRO",
                "PAUSE");

        assertThat(result.diagnostics.hasErrors()).isFalse();
        assertThat(result.texts()).containsExactly("NOP", "NOP");
    }

    /**
     * The body tokens stand in the expansion; an argument keeps the position it was written at
     * and remembers the position of the parameter it replaced, in that expansion.
     */
    @Test
    void anArgumentKeepsItsPositionAndRemembersTheParameterItReplaces() {
        Expansion result = expand(
                ".MACRO SET VALUE",
                "  SETI %DR0 VALUE",
                ".ENDMACRO",
                "SET 5");

        assertThat(result.diagnostics.hasErrors()).isFalse();
        Token seti = result.tokens.stream().filter(t -> t.text().equals("SETI")).findFirst().orElseThrow();
        Token five = result.tokens.stream().filter(t -> t.text().equals("5")).findFirst().orElseThrow();
        assertThat(seti.source()).isEqualTo(new SourceInfo("<memory>", 2, 3, "", 1));
        assertThat(seti.replaces()).isEmpty();
        assertThat(five.source()).isEqualTo(new SourceInfo("<memory>", 4, 5, "", 0));
        assertThat(five.replaces()).containsExactly(new SourceInfo("<memory>", 2, 13, "", 1));
    }

    /**
     * An argument passed on through a nested macro replaces one parameter per level, outermost
     * first, each in the expansion it stands in.
     */
    @Test
    void anArgumentPassedThroughNestedMacrosRemembersOneParameterPerLevel() {
        Expansion result = expand(
                ".MACRO INNER V",
                "  SETI %DR0 V",
                ".ENDMACRO",
                ".MACRO OUTER W",
                "  INNER W",
                ".ENDMACRO",
                "OUTER 5");

        assertThat(result.diagnostics.hasErrors()).isFalse();
        Token five = result.tokens.stream().filter(t -> t.text().equals("5")).findFirst().orElseThrow();
        assertThat(five.source()).isEqualTo(new SourceInfo("<memory>", 7, 7, "", 0));
        assertThat(five.replaces()).containsExactly(
                new SourceInfo("<memory>", 5, 9, "", 1),
                new SourceInfo("<memory>", 2, 13, "", 2));
    }

    /**
     * An argument substituted into a body that defines a macro stands in that definition as it
     * was written; expanding the inner macro keeps its position and adds its position in the body,
     * in the inner expansion, to the positions it replaces.
     */
    @Test
    void anArgumentInsideADefinitionInABodyKeepsItsPositionInTheInnerExpansion() {
        Expansion result = expand(
                ".MACRO OUTER VALUE",
                "  .MACRO INNER",
                "    SETI %DR0 VALUE",
                "  .ENDMACRO",
                "  INNER",
                ".ENDMACRO",
                "OUTER 5");

        assertThat(result.diagnostics.hasErrors()).isFalse();
        Token seti = result.tokens.stream().filter(t -> t.text().equals("SETI")).findFirst().orElseThrow();
        Token five = result.tokens.stream().filter(t -> t.text().equals("5")).findFirst().orElseThrow();
        assertThat(seti.source()).isEqualTo(new SourceInfo("<memory>", 3, 5, "", 2));
        assertThat(five.source()).isEqualTo(new SourceInfo("<memory>", 7, 7, "", 0));
        assertThat(five.replaces()).containsExactly(
                new SourceInfo("<memory>", 3, 15, "", 1),
                new SourceInfo("<memory>", 3, 15, "", 2));
    }

    @Test
    void substitutesVectorArgumentAsAWhole() {
        Expansion result = expand(
                ".MACRO STEP DIR",
                "  SEKI DIR",
                ".ENDMACRO",
                "STEP 1|0");

        assertThat(result.diagnostics.hasErrors()).isFalse();
        assertThat(result.texts()).containsExactly("SEKI", "1", "|", "0");
    }

    @Test
    void substitutesTypedLiteralArgumentAsAWhole() {
        Expansion result = expand(
                ".MACRO SET REG VALUE",
                "  SETI REG VALUE",
                ".ENDMACRO",
                "SET %DR0 DATA:5");

        assertThat(result.diagnostics.hasErrors()).isFalse();
        assertThat(result.texts()).containsExactly("SETI", "%DR0", "DATA", ":", "5");
    }

    @Test
    void expandsMacroInvokedFromAnotherMacroBody() {
        Expansion result = expand(
                ".MACRO INC REG",
                "  ADDI REG DATA:1",
                ".ENDMACRO",
                ".MACRO INC2 REG",
                "  INC REG",
                "  INC REG",
                ".ENDMACRO",
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
                ".ENDMACRO",
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
                ".ENDMACRO");

        // The preprocessor leaves the name as it found it; a later phase rejects the unknown
        // statement.
        assertThat(result.diagnostics.hasErrors()).isFalse();
        assertThat(result.texts()).containsExactly("INC", "%DR0");
    }

    @Test
    void aSecondDefinitionWithAnotherBodyIsRejectedAtBothPlaces_andTheFirstStaysInForce() {
        Expansion result = expand(
                ".MACRO INC REG",
                "  ADDI REG DATA:1",
                ".ENDMACRO",
                ".MACRO INC REG",
                "  ADDI REG DATA:2",
                ".ENDMACRO",
                "INC %DR0");

        assertThat(result.diagnostics.hasErrors()).isTrue();
        assertThat(result.diagnostics.summary()).contains(
                "Cannot define macro 'INC' differently at <memory>:4: first defined at <memory>:1 with another body.");
        assertThat(result.texts()).containsExactly("ADDI", "%DR0", "DATA", ":", "1");
    }

    @Test
    void aSecondDefinitionWithTheSameBodyButAnotherParameterNameIsRejected() {
        Expansion result = expand(
                ".MACRO INC REG",
                "  ADDI %DR0 DATA:1",
                ".ENDMACRO",
                ".MACRO INC OTHER",
                "  ADDI %DR0 DATA:1",
                ".ENDMACRO");

        assertThat(result.diagnostics.summary()).contains(
                "Cannot define macro 'INC' differently at <memory>:4: first defined at <memory>:1 with other parameters.");
    }

    /**
     * Two files that define a macro word for word alike, each sourced, define it once: the
     * second definition is accepted and the first stays in force.
     */
    @Test
    void anIdenticalDefinitionInAnotherSourcedFileIsAccepted() {
        List<String> definition = List.of(
                ".MACRO INC REG",
                "    ADDI REG DATA:1   # spacing and comments are no part of the text",
                ".ENDMACRO");
        List<String> sameDefinition = List.of(
                "",
                ".MACRO INC REG",
                "  ADDI REG DATA:1",
                ".ENDMACRO");
        Expansion result = expandWithLibraries(Map.of(LIBRARY, definition, OTHER_LIBRARY, sameDefinition),
                ".SOURCE \"lib.evo\"",
                ".SOURCE \"other.evo\"",
                "INC %DR0");

        assertThat(result.diagnostics.hasErrors()).isFalse();
        assertThat(result.texts()).containsSubsequence("ADDI", "%DR0", "DATA", ":", "1");
        assertThat(result.texts().stream().filter("ADDI"::equals).count()).isEqualTo(1);
    }

    @Test
    void aFileSourcedTwiceDefinesItsMacroOnce() {
        Expansion result = expandWithLibrary(
                List.of(
                        ".MACRO INC REG",
                        "  ADDI REG DATA:1",
                        ".ENDMACRO"),
                ".SOURCE \"lib.evo\"",
                ".SOURCE \"lib.evo\"",
                "INC %DR0");

        assertThat(result.diagnostics.hasErrors()).isFalse();
        assertThat(result.texts().stream().filter("ADDI"::equals).count()).isEqualTo(1);
    }

    @Test
    void wrongArgumentCountIsReportedAndTheInvocationRemoved() {
        Expansion result = expand(
                ".MACRO INC REG",
                "  ADDI REG DATA:1",
                ".ENDMACRO",
                "INC %DR0 %DR1");

        assertThat(result.diagnostics.hasErrors()).isTrue();
        assertThat(result.diagnostics.summary()).contains("Macro 'INC' expects 1 arguments, but got 2");
        assertThat(result.texts()).isEmpty();
    }

    @Test
    void aBlockWordAsArgumentIsRejectedAtTheInvocation() {
        Expansion result = expand(
                ".MACRO WRAP X",
                "  NOP",
                ".ENDMACRO",
                "WRAP .ENDMACRO",
                "JMPI END");

        assertThat(result.diagnostics.summary())
                .contains("<memory>:4: Macro 'WRAP' cannot take '.ENDMACRO' as an argument");
        assertThat(result.texts()).containsExactly("JMPI", "END");
    }

    @Test
    void aTopLevelOnlyDirectiveAsArgumentIsRejectedAtTheInvocation() {
        Expansion result = expand(
                ".MACRO WRAP X",
                "  X \"lib.evo\"",
                ".ENDMACRO",
                "WRAP .SOURCE");

        assertThat(result.diagnostics.summary())
                .contains("<memory>:4: Macro 'WRAP' cannot take '.SOURCE' as an argument");
    }

    @Test
    void aStrayEndmacroIsReportedAndRemoved() {
        Expansion result = expand(
                "NOP",
                ".ENDMACRO",
                "JMPI END");

        assertThat(result.diagnostics.summary()).contains("<memory>:2: .ENDMACRO closes no open block");
        assertThat(result.texts()).containsExactly("NOP", "JMPI", "END");
    }

    @Test
    void aNestedMacroDefinitionIsClosedByItsOwnEnd() {
        Expansion result = expand(
                ".MACRO OUTER",
                "  .MACRO INNER",
                "    NOP",
                "  .ENDMACRO",
                "  INNER",
                ".ENDMACRO",
                "OUTER");

        assertThat(result.diagnostics.hasErrors()).isFalse();
        assertThat(result.texts()).containsExactly("NOP");
    }

    /**
     * A macro defined inside a body that is expanded twice is defined twice with the same text;
     * that is one definition, not a second one of the name.
     */
    @Test
    void aMacroDefinedInABodyExpandedTwiceIsOneDefinition() {
        Expansion result = expand(
                ".MACRO OUTER",
                "  .MACRO INNER",
                "    NOP",
                "  .ENDMACRO",
                ".ENDMACRO",
                "OUTER",
                "OUTER",
                "INNER");

        assertThat(result.diagnostics.hasErrors()).isFalse();
        assertThat(result.texts()).containsExactly("NOP");
    }

    @Test
    void aMacroNotClosedInASourcedFileIsReportedThereAndTheIncluderSurvives() {
        Expansion result = expandWithLibrary(
                List.of(
                        "NOP",
                        ".MACRO BROKEN",
                        "  NOP"),
                ".SOURCE \"lib.evo\"",
                ".REPEAT 2",
                "  JMPI END",
                ".ENDREPEAT");

        assertThat(result.diagnostics.summary())
                .contains(LIBRARY + ":2: .MACRO opened at " + LIBRARY + ":2 is not closed before the end of " + LIBRARY);
        assertThat(result.texts()).containsSubsequence(".POP_CTX", "JMPI", "END", "JMPI", "END");
    }

    @Test
    void aMacroFromASourcedFileExpandsWithAnArgumentInsideABlock() {
        Expansion result = expandWithLibrary(
                List.of(
                        ".MACRO TWICE REG",
                        "  .REPEAT 2",
                        "    ADDI REG DATA:1",
                        "  .ENDREPEAT",
                        ".ENDMACRO"),
                ".SOURCE \"lib.evo\"",
                "TWICE %DR0");

        assertThat(result.diagnostics.hasErrors()).isFalse();
        assertThat(result.texts()).containsSubsequence(
                "ADDI", "%DR0", "DATA", ":", "1",
                "ADDI", "%DR0", "DATA", ":", "1");
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

    private static final String MAIN = "/proj/main.evo";
    private static final String LIBRARY = "/proj/lib.evo";
    private static final String OTHER_LIBRARY = "/proj/other.evo";

    /**
     * Expands a main file that may include {@code lib.evo} with {@code .SOURCE}, with the handlers
     * an inclusion needs.
     */
    private static Expansion expandWithLibrary(List<String> library, String... lines) {
        return expandWithLibraries(Map.of(LIBRARY, library), lines);
    }

    /**
     * Expands a main file that may include the given files, by resolved path, with
     * {@code .SOURCE}, with the handlers an inclusion needs.
     */
    private static Expansion expandWithLibraries(Map<String, List<String>> libraries, String... lines) {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Map<String, List<Token>> libraryTokens = new HashMap<>();
        libraries.forEach((path, library) -> {
            List<Token> fileTokens = new ArrayList<>(new Lexer(String.join("\n", library) + "\n",
                    diagnostics, path, TestLexers.symbols()).scanTokens());
            Lexer.stripEofToken(fileTokens);
            libraryTokens.put(path, fileTokens);
        });
        List<Token> tokens = new Lexer(String.join("\n", lines) + "\n", diagnostics, MAIN, TestLexers.symbols())
                .scanTokens();
        PreProcessorContext context = new PreProcessorContext("", libraryTokens, MAIN, CompilerOptions.defaults());
        TestRegistries.registerPreProcessorBlocks(context.handlers());
        context.handlers().register(".MACRO", new MacroDirectiveHandler());
        context.handlers().register(".REPEAT", new RepeatDirectiveHandler());
        context.handlers().register(".SOURCE", new SourceDirectiveHandler());
        context.handlers().register(".POP_CTX", new PopCtxPreProcessorHandler());
        PreProcessor preProcessor = new PreProcessor(tokens, diagnostics,
                new SourceRootResolver(List.of(new SourceRoot(".", null)), Path.of("/proj")),
                context);
        return new Expansion(preProcessor.expand().tokens(), diagnostics);
    }

    private static Expansion expand(String... lines) {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        Lexer lexer = new Lexer(String.join("\n", lines) + "\n", diagnostics, TestLexers.symbols());
        List<Token> tokens = lexer.scanTokens();
        PreProcessorContext context = new PreProcessorContext("", Map.of(), "<memory>", CompilerOptions.defaults());
        context.handlers().register(".MACRO", new MacroDirectiveHandler());
        TestRegistries.registerPreProcessorBlocks(context.handlers());
        PreProcessor preProcessor = new PreProcessor(tokens, diagnostics,
                new SourceRootResolver(List.of(new SourceRoot(".", null)), Path.of("")),
                context);
        return new Expansion(preProcessor.expand().tokens(), diagnostics);
    }
}
