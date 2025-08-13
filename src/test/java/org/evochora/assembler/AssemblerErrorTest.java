package org.evochora.assembler;

import org.evochora.compiler.internal.legacy.AssemblerException;
import org.evochora.compiler.internal.legacy.AssemblyProgram;
import org.evochora.runtime.isa.Instruction;
import org.evochora.runtime.model.Environment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class AssemblerErrorTest {

    private static class TestProgram extends AssemblyProgram {
        private final String code;
        public TestProgram(List<String> codeLines) {
            super("ErrorTest.s");
            this.code = String.join("\n", codeLines);
        }
        @Override
        public String getProgramCode() {
            return code;
        }
    }

    private Environment environment;

    @BeforeAll
    static void init() {
        Instruction.init();
    }

    @BeforeEach
    void setUp() {
        environment = new Environment(new int[]{10, 10}, true);
    }

    private void runAssemblyExpectingErrorExact(List<String> code, String messageContains) {
        TestProgram program = new TestProgram(code);
        assertThatThrownBy(program::assemble)
                .isInstanceOf(AssemblerException.class)
                .hasMessageContaining(messageContains);
    }

    private void runAssemblyExpectingAnyError(List<String> code) {
        TestProgram program = new TestProgram(code);
        assertThatThrownBy(program::assemble).isInstanceOf(AssemblerException.class);
    }

    @Test
    void testUnknownInstruction() {
        runAssemblyExpectingErrorExact(List.of("FOO"), "Unknown instruction");
    }

    @Test
    void testInvalidIncludeSyntax() {
        // Missing AS
        runAssemblyExpectingErrorExact(
                List.of(
                        ".ROUTINE R ARG",
                        "  ADDI ARG DATA:1",
                        ".ENDR",
                        ".INCLUDE R WITH %DR0"
                ),
                "Invalid .INCLUDE syntax"
        );
    }

    @Test
    void testUnknownRoutineInInclude() {
        // Include non-existent routine
        runAssemblyExpectingErrorExact(
                List.of(".INCLUDE ROUTINETEST.DOES_NOT_EXIST AS X WITH %DR0"),
                "Unknown routine"
        );
    }

    @Test
    void testPregOutsideProc() {
        runAssemblyExpectingErrorExact(
                List.of(".PREG %P0 0"),
                "Invalid .PREG outside .PROC"
        );
    }

    @Test
    void testDefineInvalidArgs() {
        // .DEFINE requires two arguments; message text may vary -> type-only check
        runAssemblyExpectingAnyError(List.of(".DEFINE ONLYONE"));
    }

    @Test
    void testPlaceUnknownType() {
        // Unknown type in .PLACE
        runAssemblyExpectingErrorExact(
                List.of(".PLACE FOO:5 0|0"),
                "Unknown type"
        );
    }

    @Test
    void testCallWithRequiredButMissing() {
        // PROC has a formal param, CALL must use .WITH
        runAssemblyExpectingErrorExact(
                List.of(
                        ".PROC MYPROC WITH A",
                        "  RET",
                        ".ENDP",
                        "CALL MYPROC"
                ),
                ".WITH"
        );
    }

    // Additional syntax error coverage

    @Test
    void testLabelCollidesWithInstruction() {
        runAssemblyExpectingErrorExact(List.of("ADDI:"), "same name as an instruction");
    }

    @Test
    void testUnexpectedEndOutsideBlock() {
        runAssemblyExpectingErrorExact(List.of(".ENDP"), "Unexpected .ENDP outside");
    }

    @Test
    void testUnclosedMacroBlock() {
        runAssemblyExpectingErrorExact(List.of(".MACRO $M X", "  SETI X DATA:1"), "not closed");
    }

    @Test
    void testUnclosedRoutineBlock() {
        runAssemblyExpectingErrorExact(List.of(".ROUTINE R ARG", "  ADDI ARG DATA:1"), "not closed");
    }

    @Test
    void testUnclosedProcBlock() {
        runAssemblyExpectingErrorExact(List.of(".PROC P WITH A", "  RET"), "not closed");
    }

    @Test
    void testRoutineParameterCollidesWithInstruction() {
        runAssemblyExpectingErrorExact(List.of(".ROUTINE R ADDI", "  ADDI ADDI DATA:1", ".ENDR"), "collides with an instruction");
    }

    @Test
    void testProcFormalStartsWithPercent() {
        runAssemblyExpectingErrorExact(List.of(".PROC P WITH %A", "  RET", ".ENDP"), "Formal parameter must not start");
    }

    @Test
    void testDirInvalidFormat() {
        runAssemblyExpectingAnyError(List.of(".DIR 1", "NOP"));
        runAssemblyExpectingAnyError(List.of(".DIR A|B", "NOP"));
    }

    @Test
    void testOrgInvalidFormat() {
        runAssemblyExpectingAnyError(List.of(".ORG 1", "NOP"));
        runAssemblyExpectingAnyError(List.of(".ORG X|Y", "NOP"));
    }

    @Test
    void testPlaceInvalidArgs() {
        runAssemblyExpectingAnyError(List.of(".PLACE 3|4 DATA:5"));
        runAssemblyExpectingAnyError(List.of(".PLACE DATA:5 3"));
        runAssemblyExpectingAnyError(List.of(".PLACE DATA:5 X|Y"));
    }

    @Test
    void testImportInvalidSyntax() {
        runAssemblyExpectingErrorExact(List.of(".IMPORT LIB.NAME WITH ALIAS"), "Invalid .IMPORT syntax");
    }
}
