package org.evochora.compiler.diagnostics;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.ProgramArtifact;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.runtime.Config;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.evochora.runtime.model.Molecule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the compiler tells a programmer about a computation in an operand or a constant: each
 * test compiles a small program through the whole pipeline and checks the message and the line
 * of the resulting {@link CompilationException}.
 */
@Tag("integration")
class ExpressionDiagnosticsTest {

    private static final EnvironmentProperties ENV = new EnvironmentProperties(new int[]{100, 100}, true);

    @TempDir
    Path sourceRoot;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @Test
    void computedOperandsAndConstantsCompile() throws Exception {
        write("main.evo",
                ".CONST WIDTH 100",
                ".CONST HEIGHT 70",
                ".CONST UNIT ENERGY:50",
                ".CONST AREA (WIDTH * HEIGHT)",
                ".PROC P VAL V",
                "  RET",
                ".ENDPROC",
                "START:",
                "  SETI %DR0 DATA:(WIDTH-1)",
                "  SETI %DR1 (UNIT * 2)",
                "  SETV %DR2 (WIDTH-1|HEIGHT-1)",
                "  SETI %DR3 DATA:(AREA / 100)",
                "  CALL P VAL DATA:(AREA+1)",
                "  JMPI START");

        ProgramArtifact artifact = compile("main.evo");

        assertThat(artifact.machineCodeLayout().values()).extracting(Molecule::fromInt)
                .as("the computed VAL argument the call pushes").contains(new Molecule(Config.TYPE_DATA, 7001, 0));
    }

    @Test
    void aLayoutValueInAnOperandIsReported() throws Exception {
        write("main.evo",
                "START:",
                "  SETI %DR0 DATA:(@[0])");

        assertReportedAt("Argument 2 for instruction 'SETI' is computed from a position.", 2);
    }

    /**
     * A position and a value computed from one are no instruction argument, written or through a
     * name; a position is of the wrong type, a value computed from one is reported as such.
     */
    @Test
    void positionsAndValuesComputedFromThemAreNoArgument() throws Exception {
        write("main.evo",
                "P:",
                "  NOP",
                "Q:",
                "  NOP",
                ".CONST D (Q - P)",
                "  SETV %DR0 (Q - P)",
                "  SETV %DR1 D",
                "  SETV %DR2 (P)",
                "  SETV %DR3 @");

        assertReportedAt("Argument 2 for instruction 'SETV' is computed from a position.", 6);
        assertReportedAt("Argument 2 for instruction 'SETV' is computed from a position.", 7);
        assertReportedAt("Argument 2 for instruction 'SETV' has the wrong type. Expected VECTOR, but got POSITION.", 8);
        assertReportedAt("Argument 2 for instruction 'SETV' has the wrong type. Expected VECTOR, but got POSITION.", 9);
    }

    /**
     * The CALL target and a VAL argument report a position, a molecule type and a value computed
     * from a position each in the words of their place.
     */
    @Test
    void callArgumentsComputedFromPositionsAreReported() throws Exception {
        write("main.evo",
                "P:",
                "  NOP",
                "Q:",
                "  NOP",
                ".CONST D (Q - P)",
                ".PROC X VAL A",
                "  RET",
                ".ENDPROC",
                "  CALL X VAL (Q - P)",
                "  CALL X VAL D",
                "  CALL X VAL (P)",
                "  CALL X VAL TYPE(DATA:1)",
                "  CALL (P)");

        assertReportedAt("Invalid argument: '(Q-P)' for VAL is computed from a position; expected a register, a typed literal or a vector.", 9);
        assertReportedAt("Invalid argument: 'D' for VAL is computed from a position; expected a register, a typed literal or a vector.", 10);
        assertReportedAt("Invalid argument: 'P' for VAL is a position; expected a register, a typed literal or a vector.", 11);
        assertReportedAt("Invalid argument: 'TYPE(DATA:1)' for VAL is a molecule type; expected a register, a typed literal or a vector.", 12);
        assertReportedAt("Argument 1 for instruction 'CALL' has the wrong type. Expected LABEL, but got POSITION.", 13);
    }

    /**
     * A VAL argument becomes a cell, so a number has to carry a molecule type there, as in an
     * instruction argument: written, through a constant or computed alike. A molecule type
     * through a constant is reported as it is when written or computed.
     */
    @Test
    void aValArgumentWithoutATypeIsReported() throws Exception {
        write("main.evo",
                ".CONST N 5",
                ".CONST K TYPE(DATA:1)",
                ".CONST T DATA:5",
                ".PROC X VAL A",
                "  RET",
                ".ENDPROC",
                "  CALL X VAL 5",
                "  CALL X VAL N",
                "  CALL X VAL (N + 1)",
                "  CALL X VAL K",
                "  CALL X VAL T",
                "  CALL X VAL (T * 2)");

        assertReportedAt("Invalid argument: '5' for VAL is a number without a type; expected a register, a typed literal or a vector.", 7);
        assertReportedAt("Invalid argument: 'N' for VAL is a number without a type; expected a register, a typed literal or a vector.", 8);
        assertReportedAt("Invalid argument: '(N+1)' for VAL is a number without a type; expected a register, a typed literal or a vector.", 9);
        assertReportedAt("Invalid argument: 'K' for VAL is a molecule type; expected a register, a typed literal or a vector.", 10);
        assertThatThrownBy(() -> compile("main.evo"))
                .satisfies(e -> assertThat(e.getMessage().lines()).hasSize(4));
    }

    /**
     * The target of a CALL is a name: a number, a typed value, a vector or a register, written
     * or computed, is of the wrong type.
     */
    @Test
    void aCallTargetThatIsNoNameIsReported() throws Exception {
        write("main.evo",
                ".PROC X",
                "  RET",
                ".ENDPROC",
                "  CALL 3",
                "  CALL DATA:3",
                "  CALL 1|0",
                "  CALL %DR0",
                "  CALL (3)",
                "  CALL (1|2|3)");

        assertReportedAt("Argument 1 for instruction 'CALL' has the wrong type. Expected LABEL, but got LITERAL.", 4);
        assertReportedAt("Argument 1 for instruction 'CALL' has the wrong type. Expected LABEL, but got LITERAL.", 5);
        assertReportedAt("Argument 1 for instruction 'CALL' has the wrong type. Expected LABEL, but got VECTOR.", 6);
        assertReportedAt("Argument 1 for instruction 'CALL' has the wrong type. Expected LABEL, but got REGISTER.", 7);
        assertReportedAt("Argument 1 for instruction 'CALL' has the wrong type. Expected LABEL, but got LITERAL.", 8);
        assertReportedAt("Argument 1 for instruction 'CALL' has the wrong type. Expected LABEL, but got VECTOR.", 9);
    }

    /**
     * A computation with flags cannot use a position, which only the layout knows, in any of
     * the directives that compute with flags.
     */
    @Test
    void aPositionInAComputationWithFlagsIsReported() throws Exception {
        write("main.evo",
                ".DEFINE A POSITION(1|0)",
                ".DEFINE B (POSITION(1|0)[0])",
                ".REPEAT POSITION(1|0)[0]",
                "  NOP",
                ".ENDREPEAT",
                ".IFDEF POSITION(1|0)[0] = 1",
                "  NOP",
                ".ENDDEF",
                "START:",
                "  JMPI START");

        assertReportedAt("A position cannot be used in a computation with flags.", 1);
        assertReportedAt("A position cannot be used in a computation with flags.", 2);
        assertReportedAt("A position cannot be used in a computation with flags.", 3);
        assertReportedAt("A position cannot be used in a computation with flags.", 6);
    }

    /**
     * A value with flags is one number, flag name or computation, in .DEFINE, .IFDEF, .REPEAT
     * and ^ alike: what stands after it is reported, so that a minus is a sign and an operator
     * needs parentheses, as everywhere.
     */
    @Test
    void aValueWithFlagsIsOneOperand() throws Exception {
        write("main.evo",
                ".DEFINE W 3",
                ".DEFINE X 2 -1",
                ".DEFINE Y W-1",
                ".IFDEF W + 1 = 4",
                "  NOP",
                ".ENDDEF",
                ".REPEAT W-1",
                "  NOP",
                ".ENDREPEAT",
                "START:",
                "  NOP^W-1",
                "  JMPI START");

        assertReportedAt("Unexpected '-'.", 2);
        assertReportedAt("Unexpected '-'.", 3);
        assertReportedAt("Unexpected '+'.", 4);
        assertReportedAt("Unexpected '-'.", 7);
        assertReportedAt("Unexpected '-'.", 11);
    }

    /**
     * The forms of a value with flags compile: a number with its sign, a flag name and a
     * computation in parentheses, on either side of a comparison.
     */
    @Test
    void theFormsOfAValueWithFlagsCompile() throws Exception {
        write("main.evo",
                ".DEFINE W 3",
                ".DEFINE A -5",
                ".DEFINE B (W - 1)",
                ".DEFINE C W",
                ".IFDEF W = (A + 8)",
                "  NOP",
                ".ENDDEF",
                ".REPEAT (W * 2)",
                "  NOP",
                ".ENDREPEAT",
                "START:",
                "  NOP^(W-1)",
                "  JMPI START");

        assertThatCode(() -> compile("main.evo")).doesNotThrowAnyException();
    }

    /**
     * A macro argument takes the forms of an operand: a typing or a vector with a name or a
     * computation is one argument, and a vector with a part that is no operand form, such as a
     * minus before a name, stays one argument, so that the part is reported where it is used.
     */
    @Test
    void aMacroArgumentTakesTheFormsOfAnOperand() throws Exception {
        write("main.evo",
                ".CONST N 5",
                ".CONST U ENERGY:5",
                ".MACRO PUT R V",
                "  SETI R V",
                ".ENDMACRO",
                ".MACRO VEC R V",
                "  SETV R V",
                ".ENDMACRO",
                "START:",
                "  PUT %DR0 DATA:N",
                "  PUT %DR1 TYPE(U):7",
                "  VEC %DR2 1|-N",
                "  VEC %DR3 1|(2)",
                "  JMPI START");

        assertReportedAt("Expected a number after '|'.", 12);
        assertThatThrownBy(() -> compile("main.evo")).hasMessageNotContaining("main.evo:13");
        assertThatThrownBy(() -> compile("main.evo"))
                .hasMessageNotContaining("main.evo:10")
                .hasMessageNotContaining("main.evo:11");
    }

    /**
     * A vector outside parentheses takes names and computations as its parts, but a part is no
     * operator, no vector and no sign before a name.
     */
    @Test
    void aVectorTakesNamesAndComputationsAsItsParts() throws Exception {
        write("main.evo",
                ".CONST W 5",
                "START:",
                "  SETV %DR0 1|-W",
                "  JMPI START");
        assertReportedAt("Expected a number after '|'.", 3);

        write("main.evo",
                ".CONST W 5",
                "START:",
                "  SETV %DR1 W-1|W",
                "  JMPI START");
        assertReportedAt("Instruction 'SETV' expects 2 argument(s), but got 3.", 3);

        write("main.evo",
                ".CONST W 5",
                ".CONST NW 1|2",
                ".CONST V W|(W-1)",
                ".CONST X NW|5",
                "START:",
                "  JMPI START");
        assertReportedAt("Invalid vector component: 'NW' is a vector.", 4);
        assertThatThrownBy(() -> compile("main.evo")).hasMessageNotContaining("main.evo:3:");
    }

    @Test
    void aJumpToALayoutValuedConstantIsReported() throws Exception {
        write("main.evo",
                "TARGET:",
                "  NOP",
                ".CONST X (TARGET)",
                "  JMPI X");

        assertReportedAt("Argument 1 for instruction 'JMPI' has the wrong type. Expected LABEL, but got POSITION.", 4);
    }

    @Test
    void aWrittenRegisterInAComputationIsReported() throws Exception {
        write("main.evo",
                "START:",
                "  SETI %DR0 DATA:(%DR1+1)");

        assertReportedAt("Register '%DR1' cannot be used in an expression.", 2);
    }

    @Test
    void aRegisterAliasInAComputationIsReported() throws Exception {
        write("main.evo",
                ".REG COUNTER %DR1",
                "START:",
                "  SETI %DR0 DATA:(COUNTER+1)");

        assertReportedAt("Register 'COUNTER' cannot be used in an expression.", 3);
    }

    @Test
    void aRegisterAliasAsAPositionIsReportedAsWritten() throws Exception {
        write("main.evo",
                ".REG %Spot %DR1",
                ".ORG %spot",
                "  NOP");

        assertReportedAt("Register '%spot' cannot be used in an expression.", 2);
    }

    @Test
    void aNameThatIsNoValueIsReported() throws Exception {
        write("lib.evo",
                "EXPORT ENTRY:",
                "  NOP");
        write("main.evo",
                ".IMPORT \"lib.evo\" AS LIB",
                "START:",
                "  SETI %DR0 DATA:(LIB+1)");

        assertReportedAt("'LIB' cannot be used as a value.", 3);
    }

    @Test
    void anUndefinedNameInAComputationIsReported() throws Exception {
        write("main.evo",
                "START:",
                "  SETI %DR0 DATA:(FOO+1)");

        assertReportedAt("'FOO' is not defined.", 2);
    }

    @Test
    void aConstantComputedBeforeItsNameIsDefinedIsReported() throws Exception {
        write("main.evo",
                ".CONST A (B+1)",
                ".CONST B 1");

        assertReportedAt("'B' is not defined.", 1);
    }

    @Test
    void aDivisionByZeroInAConstantIsReported() throws Exception {
        write("main.evo",
                ".CONST BAD (1/0)",
                "START:",
                "  NOP");

        assertReportedAt("Division by zero.", 1);
    }

    @Test
    void aComputationAsAReferenceArgumentIsReported() throws Exception {
        write("main.evo",
                ".PROC P REF R",
                "  RET",
                ".ENDPROC",
                "START:",
                "  CALL P REF (1+1)");

        assertReportedAt("Invalid argument: '(1+1)' for REF is a computation; expected a register.", 5);
    }

    @Test
    void aMoleculeTypeAsAnOperandIsReported() throws Exception {
        write("main.evo",
                ".CONST T TYPE(DATA:1)",
                "START:",
                "  SETI %DR0 T");

        assertReportedAt("Argument 2 for instruction 'SETI' has the wrong type. Expected LITERAL, but got TYPE.", 3);
    }

    @Test
    void anUnreadableComputationIsReportedAtItsToken() throws Exception {
        write("main.evo",
                "START:",
                "  SETI %DR0 DATA:(1 +)");

        assertReportedAt("Expected an expression after '+'.", 2);
    }

    /**
     * Asserts that the compilation fails and reports the message at the line, the two together.
     */
    private void assertReportedAt(String message, int line) {
        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:" + line + ": " + message);
    }

    private void write(String fileName, String... lines) throws Exception {
        Files.writeString(sourceRoot.resolve(fileName), String.join("\n", lines) + "\n");
    }

    private ProgramArtifact compile(String fileName) throws Exception {
        CompilerOptions options = new CompilerOptions(List.of(new SourceRoot(sourceRoot.toString(), null)));
        return new Compiler().compile(fileName, ENV, options);
    }
}
