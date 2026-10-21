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

        assertReported("A layout value cannot be used as an instruction argument.", 2);
    }

    @Test
    void aJumpToALayoutValuedConstantIsReported() throws Exception {
        write("main.evo",
                "TARGET:",
                "  NOP",
                ".CONST X (TARGET)",
                "  JMPI X");

        assertReported("A layout value cannot be used as an instruction argument.", 4);
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

        assertReported("A molecule type cannot be used as an instruction argument.", 3);
    }

    @Test
    void anUnreadableComputationIsReportedAtItsToken() throws Exception {
        write("main.evo",
                "START:",
                "  SETI %DR0 DATA:(1 +)");

        assertReported("Expected an expression after '+'.", 2);
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
