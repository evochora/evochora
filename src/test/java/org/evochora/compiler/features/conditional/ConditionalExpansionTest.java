package org.evochora.compiler.features.conditional;

import org.evochora.compiler.FeatureRegistry;
import org.evochora.compiler.StandardFeatures;
import org.evochora.compiler.TestLexers;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.compiler.diagnostics.Diagnostic;
import org.evochora.compiler.diagnostics.DiagnosticsEngine;
import org.evochora.compiler.frontend.lexer.Lexer;
import org.evochora.compiler.frontend.preprocessor.PreProcessor;
import org.evochora.compiler.frontend.preprocessor.PreProcessorContext;
import org.evochora.compiler.isa.RuntimeInstructionSetAdapter;
import org.evochora.compiler.model.token.Token;
import org.evochora.compiler.model.token.TokenType;
import org.evochora.compiler.util.SourceRootResolver;
import org.evochora.runtime.isa.Instruction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the conditional directives leave of a token stream, checked on the stream the
 * preprocessor hands the parser, with the preprocessor handlers of every standard feature: which
 * branch is kept, how flags and their values decide it, and the cases the preprocessor rejects.
 */
@Tag("unit")
class ConditionalExpansionTest {

    private static final String MAIN = "/proj/main.evo";
    private static final String LIBRARY = "/proj/lib.evo";

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    // --- Flags and branches ---

    @Test
    void definedFlagKeepsTheIfdefBlock() {
        Expansion result = expand(
                ".DEFINE FOO",
                ".IFDEF FOO",
                "  NOP",
                ".ENDDEF",
                "JMPI END");

        assertThat(result.errors()).isEmpty();
        assertThat(result.texts()).containsExactly("NOP", "JMPI", "END");
    }

    @Test
    void unsetFlagRemovesTheIfdefBlock() {
        Expansion result = expand(
                ".IFDEF UNSET",
                "  NOP",
                ".ENDDEF",
                "JMPI END");

        assertThat(result.errors()).isEmpty();
        assertThat(result.texts()).containsExactly("JMPI", "END");
    }

    @Test
    void ifndefOfAnUnsetFlagKeepsTheBlock() {
        Expansion result = expand(
                ".IFNDEF UNSET",
                "  NOP",
                ".ENDDEF");

        assertThat(result.errors()).isEmpty();
        assertThat(result.texts()).containsExactly("NOP");
    }

    @Test
    void flagNamesAreCaseInsensitive() {
        Expansion result = expand(
                ".DEFINE foo",
                ".IFDEF FOO",
                "  NOP",
                ".ENDDEF");

        assertThat(result.texts()).containsExactly("NOP");
    }

    @Test
    void elsedefIsKeptWhenTheConditionDoesNotHold() {
        Expansion result = expand(
                ".IFDEF UNSET",
                "  NOP",
                ".ELSEDEF",
                "  JMPI END",
                ".ENDDEF");

        assertThat(result.errors()).isEmpty();
        assertThat(result.texts()).containsExactly("JMPI", "END");
    }

    @Test
    void theFirstBranchOfAChainThatHoldsIsKept() {
        Expansion result = expand(
                ".DEFINE B",
                ".DEFINE C",
                ".IFDEF A",
                "  ADDI %DR0 DATA:1",
                ".ELSEIFDEF B",
                "  ADDI %DR0 DATA:2",
                ".ELSEIFDEF C",
                "  ADDI %DR0 DATA:3",
                ".ELSEDEF",
                "  ADDI %DR0 DATA:4",
                ".ENDDEF");

        assertThat(result.errors()).isEmpty();
        assertThat(result.texts()).containsExactly("ADDI", "%DR0", "DATA", ":", "2");
    }

    @Test
    void elseifndefHoldsForAnUnsetFlag() {
        Expansion result = expand(
                ".IFDEF A",
                "  ADDI %DR0 DATA:1",
                ".ELSEIFNDEF B",
                "  ADDI %DR0 DATA:2",
                ".ENDDEF");

        assertThat(result.errors()).isEmpty();
        assertThat(result.texts()).containsExactly("ADDI", "%DR0", "DATA", ":", "2");
    }

    @Test
    void noBranchHoldsAndNothingIsKept() {
        Expansion result = expand(
                ".IFDEF A",
                "  NOP",
                ".ELSEIFDEF B",
                "  NOP",
                ".ENDDEF",
                "JMPI END");

        assertThat(result.errors()).isEmpty();
        assertThat(result.texts()).containsExactly("JMPI", "END");
    }

    // --- Values and comparisons ---

    @ParameterizedTest
    @CsvSource({
            "=, 2, true", "=, 3, false",
            "==, 2, true", "==, 3, false",
            "<>, 3, true", "<>, 2, false",
            "!=, 3, true", "!=, 2, false",
            "<, 3, true", "<, 2, false",
            "<=, 2, true", "<=, 1, false",
            ">, 1, true", ">, 2, false",
            ">=, 2, true", ">=, 3, false"})
    void eachOperatorComparesTheValue(String operator, int operand, boolean holds) {
        Expansion result = expand(
                ".DEFINE LEVEL 2",
                ".IFDEF LEVEL " + operator + " " + operand,
                "  NOP",
                ".ENDDEF");

        assertThat(result.errors()).isEmpty();
        assertThat(result.texts()).isEqualTo(holds ? List.of("NOP") : List.of());
    }

    @Test
    void anOpcodeWordIsAFlagName() {
        Expansion result = expand(
                ".DEFINE NOP",
                ".IFDEF NOP",
                "  ADDI %DR0 DATA:1",
                ".ENDDEF",
                ".UNDEF NOP",
                ".DEFINE NOP 2",
                ".DEFINE LEVEL 2",
                ".IFDEF LEVEL = NOP",
                "  ADDI %DR0 DATA:2",
                ".ENDDEF");

        assertThat(result.errors()).isEmpty();
        assertThat(result.texts()).containsExactly(
                "ADDI", "%DR0", "DATA", ":", "1",
                "ADDI", "%DR0", "DATA", ":", "2");
    }

    @Test
    void anOperatorWithoutSpacesIsRead() {
        Expansion result = expand(
                ".DEFINE LEVEL 2",
                ".IFDEF LEVEL>=2",
                "  NOP",
                ".ENDDEF");

        assertThat(result.texts()).containsExactly("NOP");
    }

    @Test
    void theRightHandSideMayNameAnotherFlag() {
        Expansion result = expand(
                ".DEFINE LEVEL 2",
                ".DEFINE MINIMUM 2",
                ".IFDEF LEVEL >= MINIMUM",
                "  NOP",
                ".ENDDEF",
                ".IFDEF LEVEL > MINIMUM",
                "  JMPI END",
                ".ENDDEF");

        assertThat(result.errors()).isEmpty();
        assertThat(result.texts()).containsExactly("NOP");
    }

    @Test
    void hexadecimalBinaryAndNegativeLiteralsAreCompared() {
        Expansion result = expand(
                ".DEFINE MASK 0x1F",
                ".DEFINE OFFSET -16",
                ".IFDEF MASK = 31",
                "  ADDI %DR0 DATA:1",
                ".ENDDEF",
                ".IFDEF OFFSET = -0x10",
                "  ADDI %DR0 DATA:2",
                ".ENDDEF",
                ".IFDEF OFFSET = -0b10000",
                "  ADDI %DR0 DATA:3",
                ".ENDDEF",
                ".IFDEF OFFSET < 0",
                "  ADDI %DR0 DATA:4",
                ".ENDDEF");

        assertThat(result.errors()).isEmpty();
        assertThat(result.texts()).containsExactly(
                "ADDI", "%DR0", "DATA", ":", "1",
                "ADDI", "%DR0", "DATA", ":", "2",
                "ADDI", "%DR0", "DATA", ":", "3",
                "ADDI", "%DR0", "DATA", ":", "4");
    }

    @Test
    void aComparisonOfAnUnsetFlagDoesNotHold() {
        Expansion result = expand(
                ".IFDEF LEVEL > 1",
                "  NOP",
                ".ENDDEF");

        assertThat(result.errors()).isEmpty();
        assertThat(result.texts()).isEmpty();
    }

    @Test
    void aComparisonOfAFlagWithoutValueIsAnError() {
        Expansion result = expand(
                ".DEFINE FAST",
                ".IFDEF FAST > 1",
                "  NOP",
                ".ELSEDEF",
                "  JMPI END",
                ".ENDDEF");

        assertThat(result.errors()).containsExactly(
                MAIN + ":2: Flag FAST is set without a value and cannot be compared");
        assertThat(result.texts()).isEmpty();
    }

    @Test
    void aComparisonWithAnUnsetFlagIsAnError() {
        Expansion result = expand(
                ".DEFINE LEVEL 2",
                ".IFDEF LEVEL >= MINIMUM",
                "  NOP",
                ".ENDDEF");

        assertThat(result.errors()).containsExactly(
                MAIN + ":2: Flag MINIMUM, which LEVEL is compared with, is not set");
        assertThat(result.texts()).isEmpty();
    }

    @Test
    void aComparisonWithAFlagWithoutValueIsAnError() {
        Expansion result = expand(
                ".DEFINE LEVEL 2",
                ".DEFINE MINIMUM",
                ".IFDEF LEVEL >= MINIMUM",
                "  NOP",
                ".ENDDEF");

        assertThat(result.errors()).containsExactly(
                MAIN + ":3: Flag MINIMUM, which LEVEL is compared with, is set without a value");
    }

    @Test
    void aComparisonAfterIfndefIsAnError() {
        Expansion result = expand(
                ".IFNDEF LEVEL > 1",
                "  NOP",
                ".ENDDEF",
                ".IFDEF A",
                ".ELSEIFNDEF LEVEL = 2",
                ".ENDDEF");

        assertThat(result.errors()).hasSize(2);
        assertThat(result.errors().get(0)).startsWith(MAIN + ":1: .IFNDEF takes no comparison");
        assertThat(result.errors().get(1)).startsWith(MAIN + ":5: .ELSEIFNDEF takes no comparison");
        assertThat(result.texts()).isEmpty();
    }

    // --- Redefinition ---

    @Test
    void theSameDefinitionAgainIsAccepted() {
        Expansion result = expand(
                ".DEFINE LEVEL 2",
                ".DEFINE LEVEL 2",
                ".DEFINE FAST",
                ".DEFINE FAST");

        assertThat(result.errors()).isEmpty();
        assertThat(result.texts()).isEmpty();
    }

    @Test
    void anotherValueIsRejectedAndTheFirstStaysInForce() {
        Expansion result = expand(
                ".DEFINE LEVEL 2",
                ".DEFINE LEVEL 3",
                ".IFDEF LEVEL = 2",
                "  NOP",
                ".ENDDEF");

        assertThat(result.errors()).containsExactly(MAIN + ":2: Cannot define flag LEVEL as 3: it is already"
                + " defined as 2 at " + MAIN + ":1; .UNDEF it first to change it");
        assertThat(result.texts()).containsExactly("NOP");
    }

    @Test
    void aValueWhereThereWasNoneIsRejected() {
        Expansion result = expand(
                ".DEFINE FAST",
                ".DEFINE FAST 1");

        assertThat(result.errors()).containsExactly(MAIN + ":2: Cannot define flag FAST as 1: it is already"
                + " defined without a value at " + MAIN + ":1; .UNDEF it first to change it");
    }

    @Test
    void undefThenDefineChangesTheFlag() {
        Expansion result = expand(
                ".DEFINE LEVEL 2",
                ".UNDEF LEVEL",
                ".DEFINE LEVEL 3",
                ".IFDEF LEVEL = 3",
                "  NOP",
                ".ENDDEF");

        assertThat(result.errors()).isEmpty();
        assertThat(result.texts()).containsExactly("NOP");
    }

    @Test
    void undefRemovesTheFlagAndAnUnsetFlagIsNoError() {
        Expansion result = expand(
                ".DEFINE FOO",
                ".UNDEF FOO",
                ".UNDEF FOO",
                ".UNDEF NEVER_SET",
                ".IFDEF FOO",
                "  NOP",
                ".ENDDEF");

        assertThat(result.errors()).isEmpty();
        assertThat(result.texts()).isEmpty();
    }

    @Test
    void theSameDefinitionAsTheConfigurationIsAccepted() {
        Expansion result = expandWith(Map.of("LEVEL", OptionalInt.of(2)), List.of(),
                ".DEFINE LEVEL 2",
                ".IFDEF LEVEL = 2",
                "  NOP",
                ".ENDDEF");

        assertThat(result.errors()).isEmpty();
        assertThat(result.texts()).containsExactly("NOP");
    }

    @Test
    void anotherValueThanTheConfigurationIsRejected() {
        Expansion result = expandWith(Map.of("LEVEL", OptionalInt.of(2)), List.of(),
                ".DEFINE LEVEL 3");

        assertThat(result.errors()).containsExactly(MAIN + ":1: Cannot define flag LEVEL as 3: it is already"
                + " defined as 2 by the configuration; .UNDEF it first to change it");
    }

    @Test
    void theIfndefDefaultYieldsToTheConfiguration() {
        String[] program = {
                ".IFNDEF REDUNDANCY",
                "  .DEFINE REDUNDANCY 1",
                ".ENDDEF",
                ".IFDEF REDUNDANCY >= 2",
                "  NOP",
                ".ELSEDEF",
                "  JMPI END",
                ".ENDDEF"};

        Expansion configured = expandWith(Map.of("redundancy", OptionalInt.of(2)), List.of(), program);
        Expansion unconfigured = expand(program);

        assertThat(configured.errors()).isEmpty();
        assertThat(configured.texts()).containsExactly("NOP");
        assertThat(unconfigured.errors()).isEmpty();
        assertThat(unconfigured.texts()).containsExactly("JMPI", "END");
    }

    @Test
    void aConfiguredFlagThatIsNoNameIsReportedOnce() {
        Expansion result = expandWith(Map.of("NO-NAME", OptionalInt.empty(), "GOOD", OptionalInt.empty()), List.of(),
                ".IFDEF GOOD",
                "  NOP",
                ".ENDDEF",
                ".DEFINE OTHER",
                ".IFDEF OTHER",
                ".ENDDEF");

        assertThat(result.errors()).containsExactly(":0: Configured flag 'NO-NAME' is not a valid name:"
                + " a flag name consists of letters, digits and _ and begins with a letter or _");
        assertThat(result.texts()).containsExactly("NOP");
    }

    @Test
    void aConfiguredFlagThatIsNoNameIsNotCheckedWithoutAConditionalDirective() {
        Expansion result = expandWith(Map.of("NO-NAME", OptionalInt.empty()), List.of(), "NOP");

        assertThat(result.errors()).isEmpty();
    }

    // --- The line rule ---

    @Test
    void aLabelBeforeIfdefBreaksTheLineRule() {
        Expansion result = expand(
                ".DEFINE X",
                "L: .IFDEF X",
                "  NOP",
                ".ENDDEF",
                "JMPI END");

        assertThat(result.errors()).containsExactly(MAIN + ":2: .IFDEF must stand alone on its line");
        assertThat(result.texts()).containsExactly(".LABEL", "L", "JMPI", "END");
    }

    @Test
    void aStatementAfterIfdefBreaksTheLineRule() {
        Expansion result = expand(
                ".DEFINE X",
                ".IFDEF X; NOP",
                "  NOP",
                ".ENDDEF");

        assertThat(result.errors()).containsExactly(MAIN + ":2: .IFDEF must stand alone on its line");
        assertThat(result.texts()).isEmpty();
    }

    @Test
    void aStatementBeforeADividerOrAfterTheEndBreaksTheLineRule() {
        Expansion result = expand(
                ".IFDEF X",
                "NOP; .ELSEDEF",
                ".ENDDEF; NOP");

        assertThat(result.errors()).containsExactly(
                MAIN + ":2: .ELSEDEF must stand alone on its line",
                MAIN + ":3: .ENDDEF must stand alone on its line");
        assertThat(result.texts()).containsExactly("NOP");
    }

    @Test
    void anExportedDefineIsToldThatAConstantIsWrittenConst() {
        Expansion result = expand(
                "EXPORT .DEFINE X 5",
                "NOP");

        assertThat(result.errors()).containsExactly(
                MAIN + ":1: .DEFINE must stand alone on its line; a constant is written .CONST");
    }

    @Test
    void undefAndDefineWithAStatementAfterThemBreakTheLineRule() {
        Expansion result = expand(
                ".DEFINE X; NOP",
                ".UNDEF X; NOP");

        assertThat(result.errors()).containsExactly(
                MAIN + ":1: .DEFINE must stand alone on its line; a constant is written .CONST",
                MAIN + ":2: .UNDEF must stand alone on its line");
    }

    // --- Nesting ---

    @Test
    void aBlockNestedInTheKeptBranchIsEvaluated() {
        Expansion result = expand(
                ".DEFINE AGGRESSIVE",
                ".IFDEF AGGRESSIVE",
                "  .IFDEF HAS_SCANNER",
                "    ADDI %DR0 DATA:1",
                "  .ELSEDEF",
                "    ADDI %DR0 DATA:2",
                "  .ENDDEF",
                ".ELSEDEF",
                "  ADDI %DR0 DATA:3",
                ".ENDDEF");

        assertThat(result.errors()).isEmpty();
        assertThat(result.texts()).containsExactly("ADDI", "%DR0", "DATA", ":", "2");
    }

    @Test
    void aBlockNestedInASkippedBranchKeepsItsDividersToItself() {
        Expansion result = expand(
                ".IFDEF AGGRESSIVE",
                "  .IFDEF HAS_SCANNER",
                "    ADDI %DR0 DATA:1",
                "  .ELSEDEF",
                "    ADDI %DR0 DATA:2",
                "  .ENDDEF",
                ".ELSEDEF",
                "  ADDI %DR0 DATA:3",
                ".ENDDEF");

        assertThat(result.errors()).isEmpty();
        assertThat(result.texts()).containsExactly("ADDI", "%DR0", "DATA", ":", "3");
    }

    @Test
    void aDefineInASkippedBranchHasNoEffect() {
        Expansion result = expand(
                ".IFDEF UNSET",
                "  .DEFINE FOO",
                ".ENDDEF",
                ".IFDEF FOO",
                "  NOP",
                ".ENDDEF");

        assertThat(result.errors()).isEmpty();
        assertThat(result.texts()).isEmpty();
    }

    @Test
    void aDefineInTheKeptBranchTakesEffectForWhatFollows() {
        Expansion result = expand(
                ".IFNDEF FOO",
                "  .DEFINE FOO 1",
                ".ENDDEF",
                ".IFDEF FOO = 1",
                "  NOP",
                ".ENDDEF");

        assertThat(result.errors()).isEmpty();
        assertThat(result.texts()).containsExactly("NOP");
    }

    @Test
    void aSkippedBranchWithAnUnbalancedMacroIsReported() {
        Expansion result = expand(
                ".IFDEF UNSET",
                "  .MACRO BROKEN",
                "    NOP",
                ".ENDDEF");

        assertThat(result.errors()).anySatisfy(error -> assertThat(error).startsWith(MAIN + ":4: .ENDDEF closes"
                + " the .IFDEF opened at " + MAIN + ":1, but the .MACRO opened at " + MAIN + ":2 is still open"));
    }

    // --- Errors ---

    @Test
    void dividersAndEndOutsideABlockAreReportedAndRemoved() {
        Expansion result = expand(
                ".ELSEDEF",
                ".ELSEIFDEF X",
                ".ENDDEF",
                "NOP");

        assertThat(result.errors()).containsExactly(
                MAIN + ":1: .ELSEDEF divides no open block",
                MAIN + ":2: .ELSEIFDEF divides no open block",
                MAIN + ":3: .ENDDEF closes no open block");
        assertThat(result.texts()).contains("NOP");
    }

    @Test
    void aBranchAfterElsedefIsAnErrorAndTheBlockIsRemoved() {
        Expansion result = expand(
                ".IFDEF A",
                "  ADDI %DR0 DATA:1",
                ".ELSEDEF",
                "  ADDI %DR0 DATA:2",
                ".ELSEIFDEF B",
                "  ADDI %DR0 DATA:3",
                ".ELSEDEF",
                "  ADDI %DR0 DATA:4",
                ".ENDDEF",
                "NOP");

        assertThat(result.errors()).containsExactly(
                MAIN + ":5: .ELSEIFDEF follows the .ELSEDEF at " + MAIN + ":3; .ELSEDEF is the last branch of"
                        + " the block opened at " + MAIN + ":1",
                MAIN + ":7: A second .ELSEDEF follows the .ELSEDEF at " + MAIN + ":3; .ELSEDEF is the last branch"
                        + " of the block opened at " + MAIN + ":1");
        assertThat(result.texts()).containsExactly("NOP");
    }

    @Test
    void aBlockOpenAtTheEndOfTheInputIsReportedAtItsOpener() {
        Expansion result = expand(
                "NOP",
                ".IFDEF X",
                "  NOP");

        assertThat(result.errors()).containsExactly(
                MAIN + ":2: .IFDEF opened at " + MAIN + ":2 is not closed before the end of the input");
    }

    @Test
    void aBlockOpenAtTheEndOfASourcedFileIsReportedThereAndTheIncluderSurvives() {
        Expansion result = expandWith(Map.of(), List.of(
                        "NOP",
                        ".IFDEF X",
                        "  NOP"),
                ".SOURCE \"lib.evo\"",
                ".IFDEF X",
                "  NOP",
                ".ENDDEF",
                "JMPI END");

        assertThat(result.errors()).containsExactly(
                LIBRARY + ":2: .IFDEF opened at " + LIBRARY + ":2 is not closed before the end of " + LIBRARY);
        assertThat(result.texts()).containsSubsequence(".POP_CTX", "JMPI", "END");
    }

    @Test
    void anIfdefWithoutANameIsReportedOnceAndItsBlockRemoved() {
        Expansion result = expand(
                ".IFDEF",
                "  NOP",
                ".ELSEIFDEF X",
                "  NOP",
                ".ELSEDEF",
                "  NOP",
                ".ENDDEF",
                "JMPI END");

        assertThat(result.errors()).containsExactly(MAIN + ":1: .IFDEF needs a flag name");
        assertThat(result.texts()).containsExactly("JMPI", "END");
    }

    @Test
    void aMalformedConditionIsReportedAndItsBlockRemoved() {
        Expansion result = expand(
                ".IFDEF X Y",
                ".ENDDEF",
                ".IFDEF X >= DATA:1",
                ".ENDDEF",
                ".IFDEF X >",
                ".ENDDEF");

        assertThat(result.errors()).containsExactly(
                MAIN + ":1: Expected a comparison (=, ==, <>, !=, <, <=, >, >=) after the flag name of .IFDEF, found 'Y'",
                MAIN + ":3: Unexpected ':' after the condition of .IFDEF",
                MAIN + ":5: Expected an integer or a flag name after '>' in .IFDEF");
    }

    @Test
    void elsedefTakesNoCondition() {
        Expansion result = expand(
                ".IFDEF X",
                ".ELSEDEF Y",
                ".ENDDEF");

        assertThat(result.errors()).containsExactly(MAIN + ":2: .ELSEDEF must stand alone on its line");
    }

    @Test
    void defineAndUndefWithoutANameAreReported() {
        Expansion result = expand(
                ".DEFINE",
                ".UNDEF",
                "NOP");

        assertThat(result.errors()).containsExactly(
                MAIN + ":1: .DEFINE needs a flag name",
                MAIN + ":2: .UNDEF needs a flag name");
        assertThat(result.texts()).containsExactly("NOP");
    }

    @Test
    void aDefineWithATypedLiteralOrAVectorIsToldThatAConstantIsWrittenConst() {
        Expansion result = expand(
                ".DEFINE X DATA:5",
                ".DEFINE Y 1|0",
                ".DEFINE Z W");

        assertThat(result.errors()).containsExactly(
                MAIN + ":1: .DEFINE takes a flag name and an optional integer; a constant is written .CONST",
                MAIN + ":2: .DEFINE takes a flag name and an optional integer; a constant is written .CONST",
                MAIN + ":3: .DEFINE takes a flag name and an optional integer; a constant is written .CONST");
    }

    // --- Macros and repetitions ---

    @Test
    void anIfdefInAMacroBodyIsEvaluatedAtEachExpansionWithAParameterAsFlagName() {
        Expansion result = expandWith(Map.of(), List.of(
                        ".MACRO WHEN FLAG",
                        "  .IFDEF FLAG",
                        "    NOP",
                        "  .ELSEDEF",
                        "    JMPI END",
                        "  .ENDDEF",
                        ".ENDMACRO"),
                ".SOURCE \"lib.evo\"",
                "WHEN FAST",
                ".DEFINE FAST",
                "WHEN FAST");

        assertThat(result.errors()).isEmpty();
        assertThat(result.texts()).containsSubsequence("JMPI", "END", "NOP");
        assertThat(result.texts()).filteredOn("NOP"::equals).hasSize(1);
    }

    @Test
    void aDefineOrUndefInAMacroBodyIsRejectedAtTheDefinition() {
        Expansion result = expand(
                ".MACRO SETUP",
                "  .DEFINE FAST",
                "  .UNDEF SLOW",
                ".ENDMACRO");

        assertThat(result.errors()).containsExactly(
                MAIN + ":2: .DEFINE may not stand inside a .MACRO body; the body opened at " + MAIN + ":1",
                MAIN + ":3: .UNDEF may not stand inside a .MACRO body; the body opened at " + MAIN + ":1");
    }

    @Test
    void anElsedefAtTheTopOfAMacroBodyIsRejectedAtTheDefinition() {
        Expansion result = expand(
                ".MACRO HALF",
                "  .ELSEDEF",
                ".ENDMACRO");

        assertThat(result.errors()).containsExactly(MAIN + ":2: .ELSEDEF divides no open block; the .MACRO"
                + " opened at " + MAIN + ":1 is still open");
    }

    @Test
    void enddefAsAMacroArgumentIsRejectedAtTheCall() {
        Expansion result = expand(
                ".MACRO WRAP X",
                "  NOP",
                ".ENDMACRO",
                "WRAP .ENDDEF");

        assertThat(result.errors()).containsExactly(MAIN + ":4: Macro 'WRAP' cannot take '.ENDDEF' as an"
                + " argument: a block directive or a directive that stands only at the top level is never an argument.");
    }

    @Test
    void anIfdefInsideARepeatBlockIsEvaluatedForEveryRepetition() {
        Expansion result = expand(
                ".DEFINE FAST",
                ".REPEAT 2",
                "  .IFDEF FAST",
                "    NOP",
                "  .ELSEDEF",
                "    JMPI END",
                "  .ENDDEF",
                ".ENDREPEAT");

        assertThat(result.errors()).isEmpty();
        assertThat(result.texts()).containsExactly("NOP", "NOP");
    }

    @Test
    void aDefineInsideARepeatBlockIsRejected() {
        Expansion result = expand(
                ".REPEAT 2",
                "  .DEFINE FAST",
                ".ENDREPEAT");

        assertThat(result.errors()).containsExactly(
                MAIN + ":2: .DEFINE may not stand inside a .REPEAT body; the body opened at " + MAIN + ":1");
    }

    // --- Inclusion ---

    @Test
    void aDefineInASourcedFileIsSeenByALaterIfdefOfTheIncluder() {
        Expansion result = expandWith(Map.of(), List.of(".DEFINE FROM_LIBRARY"),
                ".SOURCE \"lib.evo\"",
                ".IFDEF FROM_LIBRARY",
                "  NOP",
                ".ENDDEF");

        assertThat(result.errors()).isEmpty();
        assertThat(result.texts()).containsSubsequence(".POP_CTX", "NOP");
    }

    @Test
    void aBlockOnTheFirstLineOfASourcedFileStandsAloneDespiteTheInclusionMarker() {
        Expansion result = expandWith(Map.of(), List.of(
                        ".IFNDEF X",
                        "  NOP",
                        ".ENDDEF"),
                ".SOURCE \"lib.evo\"");

        assertThat(result.errors()).isEmpty();
        assertThat(result.texts()).containsExactly(".PUSH_CTX", "NOP", ".POP_CTX");
    }

    // --- Harness ---

    private record Expansion(List<Token> tokens, DiagnosticsEngine diagnostics) {
        /** The texts of the expanded tokens, without newlines and the end marker. */
        List<String> texts() {
            return tokens.stream()
                    .filter(t -> t.type() != TokenType.NEWLINE && t.type() != TokenType.END_OF_FILE)
                    .map(Token::text)
                    .toList();
        }

        /** The errors as {@code file:line: message}. */
        List<String> errors() {
            return diagnostics.getDiagnostics().stream()
                    .filter(d -> d.type() == Diagnostic.Type.ERROR)
                    .map(d -> d.fileName() + ":" + d.lineNumber() + ": " + d.message())
                    .toList();
        }
    }

    private static Expansion expand(String... lines) {
        return expandWith(Map.of(), List.of(), lines);
    }

    /**
     * Expands a main file with the preprocessor handlers of every standard feature, the given
     * configured flags, and a file {@code lib.evo} the main file may include with
     * {@code .SOURCE}.
     */
    private static Expansion expandWith(Map<String, OptionalInt> defines, List<String> library, String... lines) {
        DiagnosticsEngine diagnostics = new DiagnosticsEngine();
        List<Token> libraryTokens = new ArrayList<>(new Lexer(String.join("\n", library) + "\n",
                diagnostics, LIBRARY, TestLexers.symbols()).scanTokens());
        Lexer.stripEofToken(libraryTokens);
        List<Token> tokens = new Lexer(String.join("\n", lines) + "\n", diagnostics, MAIN, TestLexers.symbols())
                .scanTokens();
        CompilerOptions options = new CompilerOptions(List.of(new SourceRoot(".", null)), defines);
        PreProcessorContext context = new PreProcessorContext("", Map.of(LIBRARY, libraryTokens), options);
        FeatureRegistry features = new FeatureRegistry(new RuntimeInstructionSetAdapter());
        StandardFeatures.all().forEach(f -> f.register(features));
        features.preprocessorHandlers().forEach(context.handlers()::register);
        features.preprocessorBlocks().forEach(context.handlers()::registerBlock);
        features.preprocessorTopLevelOnly().forEach(context.handlers()::registerTopLevelOnly);
        PreProcessor preProcessor = new PreProcessor(tokens, diagnostics,
                new SourceRootResolver(List.of(new SourceRoot(".", null)), Path.of("/proj")), context);
        return new Expansion(preProcessor.expand().tokens(), diagnostics);
    }
}
