package org.evochora.compiler.diagnostics;

import org.evochora.compiler.Compiler;
import org.evochora.compiler.api.CompilationException;
import org.evochora.compiler.api.CompilerOptions;
import org.evochora.compiler.api.SourceRoot;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.EnvironmentProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
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

        assertThatCode(() -> compile("main.evo")).doesNotThrowAnyException();
    }

    @Test
    void aLayoutValueInAnOperandIsReported() throws Exception {
        write("main.evo",
                "START:",
                "  SETI %DR0 DATA:(@[0])");

        assertReported("Argument 2 for instruction 'SETI' is computed from a position.", 2);
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

        assertReportedAt("Cannot pass '(Q-P)' as VAL argument: it is computed from a position.", 9);
        assertReportedAt("Cannot pass 'D' as VAL argument: it is computed from a position.", 10);
        assertReportedAt("Cannot pass a position as VAL argument: VAL takes a register, a literal or a vector.", 11);
        assertReportedAt("Cannot pass a molecule type as VAL argument: VAL takes a register, a literal or a vector.", 12);
        assertReportedAt("Argument 1 for instruction 'CALL' has the wrong type. Expected LABEL, but got POSITION.", 13);
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

    @Test
    void aJumpToALayoutValuedConstantIsReported() throws Exception {
        write("main.evo",
                "TARGET:",
                "  NOP",
                ".CONST X (TARGET)",
                "  JMPI X");

        assertReported("Argument 1 for instruction 'JMPI' has the wrong type. Expected LABEL, but got POSITION.", 4);
    }

    @Test
    void aWrittenRegisterInAComputationIsReported() throws Exception {
        write("main.evo",
                "START:",
                "  SETI %DR0 DATA:(%DR1+1)");

        assertReported("Register '%DR1' cannot be used in an expression.", 2);
    }

    @Test
    void aRegisterAliasInAComputationIsReported() throws Exception {
        write("main.evo",
                ".REG COUNTER %DR1",
                "START:",
                "  SETI %DR0 DATA:(COUNTER+1)");

        assertReported("Register '%DR1' cannot be used in an expression.", 3);
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

        assertReported("'LIB' cannot be used as a value.", 3);
    }

    @Test
    void anUndefinedNameInAComputationIsReported() throws Exception {
        write("main.evo",
                "START:",
                "  SETI %DR0 DATA:(FOO+1)");

        assertReported("'FOO' is not defined.", 2);
    }

    @Test
    void aConstantComputedBeforeItsNameIsDefinedIsReported() throws Exception {
        write("main.evo",
                ".CONST A (B+1)",
                ".CONST B 1");

        assertReported("'B' is not defined.", 1);
    }

    @Test
    void aDivisionByZeroInAConstantIsReported() throws Exception {
        write("main.evo",
                ".CONST BAD (1/0)",
                "START:",
                "  NOP");

        assertReported("Division by zero.", 1);
    }

    @Test
    void aComputationAsAReferenceArgumentIsReported() throws Exception {
        write("main.evo",
                ".PROC P REF R",
                "  RET",
                ".ENDPROC",
                "START:",
                "  CALL P REF (1+1)");

        assertReported("Cannot pass a computation as REF argument: REF takes a register.", 5);
    }

    @Test
    void aMoleculeTypeAsAnOperandIsReported() throws Exception {
        write("main.evo",
                ".CONST T TYPE(DATA:1)",
                "START:",
                "  SETI %DR0 T");

        assertReported("Argument 2 for instruction 'SETI' has the wrong type. Expected LITERAL, but got TYPE.", 3);
    }

    @Test
    void anUnreadableComputationIsReportedAtItsToken() throws Exception {
        write("main.evo",
                "START:",
                "  SETI %DR0 DATA:(1 +)");

        assertReported("Expected an expression after '+'.", 2);
    }

    /**
     * Asserts that the compilation fails and reports the message at the line, the two together.
     */
    private void assertReportedAt(String message, int line) {
        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining("main.evo:" + line + ": " + message);
    }

    private void assertReported(String message, int line) {
        assertThatThrownBy(() -> compile("main.evo"))
                .isInstanceOf(CompilationException.class)
                .hasMessageContaining(message)
                .hasMessageContaining("main.evo:" + line);
    }

    private void write(String fileName, String... lines) throws Exception {
        Files.writeString(sourceRoot.resolve(fileName), String.join("\n", lines) + "\n");
    }

    private void compile(String fileName) throws Exception {
        CompilerOptions options = new CompilerOptions(List.of(new SourceRoot(sourceRoot.toString(), null)));
        new Compiler().compile(fileName, ENV, options);
    }
}
