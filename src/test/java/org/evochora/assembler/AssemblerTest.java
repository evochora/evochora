package org.evochora.assembler;

import org.evochora.organism.Instruction;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

class AssemblerTest {

    @BeforeAll
    static void setup() {
        Instruction.init();
    }

    private Assembler createAssembler() {
        return new Assembler();
    }

    /**
     * NEW: Helper method to convert a raw string into the new
     * list format expected by the assembler.
     */
    private List<AnnotatedLine> annotateCode(String code) {
        List<AnnotatedLine> lines = new ArrayList<>();
        int lineNum = 1;
        for (String line : code.split("\\r?\\n")) {
            lines.add(new AnnotatedLine(line, lineNum++, "test.s"));
        }
        return lines;
    }

    @Test
    void testAssemblerRecognizesValidCode() {
        String code = """
                .ORG 0|0
                NOP
                .ORG 5|5
                NOP
                """;
        Assembler assembler = createAssembler();
        ProgramMetadata metadata = assembler.assemble(annotateCode(code), "TestValidCode", false);
        Map<int[], Integer> layout = metadata.machineCodeLayout();

        Assertions.assertEquals(2, layout.size());
        Assertions.assertNotNull(findValueAtCoordinate(layout, new int[]{0, 0}));
        Assertions.assertNotNull(findValueAtCoordinate(layout, new int[]{5, 5}));
    }

    @Test
    void testMacroExpansionWithNestedMacros() {
        String code = """
                .REG %DR_A 0
                .REG %DR_B 1
                
                .MACRO $INNER_MACRO PARAM1
                    SETR %DR_A PARAM1
                .ENDM
                
                .MACRO $OUTER_MACRO PARAM2
                    $INNER_MACRO PARAM2
                    ADDR %DR_B %DR_A
                .ENDM
                
                .ORG 0|0
                START:
                    SETI %DR_B DATA:100
                    $OUTER_MACRO %DR_A
                """;
        Assembler assembler = createAssembler();
        Assertions.assertDoesNotThrow(() -> assembler.assemble(annotateCode(code), "TestNestedMacros", false));
    }

    @Test
    void testInfiniteMacroRecursionDetection() {
        String code = """
                .MACRO $INFINITE_LOOP
                    NOP
                    $CALL_LOOP_B
                .ENDM
                
                .MACRO $CALL_LOOP_B
                    NOP
                    $INFINITE_LOOP
                .ENDM
                
                .ORG 0|0
                $INFINITE_LOOP
                """;
        Assembler assembler = createAssembler();
        AssemblerException exception = Assertions.assertThrows(AssemblerException.class, () -> assembler.assemble(annotateCode(code), "TestInfiniteLoop", false));

        Assertions.assertTrue(
                exception.getMessage().contains("Infinite recursion in macro detected"),
                "The error message should indicate infinite recursion. But was: " + exception.getFormattedMessage()
        );
    }

    @Test
    void testMissingMacroEndDirective() {
        String code = """
                .MACRO $INCOMPLETE_MACRO
                    NOP
                """;
        Assembler assembler = createAssembler();
        AssemblerException exception = Assertions.assertThrows(AssemblerException.class, () -> assembler.assemble(annotateCode(code), "TestMissingEndm", false));

        Assertions.assertTrue(
                exception.getMessage().contains("was not closed"),
                "The error message should indicate a missing .ENDM directive."
        );
    }

    @Test
    void testIncorrectNumberOfMacroArguments() {
        String code = """
                .MACRO $TWO_ARGS A B
                    SETR A B
                .ENDM
                
                .ORG 0|0
                $TWO_ARGS %DR_A
                """;
        Assembler assembler = createAssembler();
        AssemblerException exception = Assertions.assertThrows(AssemblerException.class, () -> assembler.assemble(annotateCode(code), "TestWrongMacroArgs", false));

        Assertions.assertTrue(
                exception.getMessage().contains("Wrong number of arguments for macro"),
                "The error message should indicate a wrong number of macro arguments."
        );
    }

    @Test
    void testDuplicateLabelDefinition() {
        String code = """
                .ORG 0|0
                MY_LABEL:
                    NOP
                MY_LABEL:
                    NOP
                """;
        Assembler assembler = createAssembler();
        AssemblerException exception = Assertions.assertThrows(AssemblerException.class, () -> assembler.assemble(annotateCode(code), "TestDuplicateLabel", false));

        Assertions.assertTrue(
                exception.getMessage().contains("Label 'MY_LABEL' has been declared more than once"),
                "The error message should indicate a duplicate label."
        );
    }

    @Test
    void testUnknownInstruction() {
        String code = """
                .ORG 0|0
                UNKNOWN_OPCODE
                """;
        Assembler assembler = createAssembler();
        AssemblerException exception = Assertions.assertThrows(AssemblerException.class, () -> assembler.assemble(annotateCode(code), "TestUnknownInstruction", false));

        Assertions.assertTrue(
                exception.getMessage().contains("Unknown instruction: UNKNOWN_OPCODE"),
                "The error message should indicate an unknown instruction."
        );
    }

    @Test
    void testInvalidInstructionArguments() {
        String code = """
                .ORG 0|0
                JMPI UNKNOWN_LABEL
                """;
        Assembler assembler = createAssembler();
        AssemblerException exception = Assertions.assertThrows(AssemblerException.class, () -> assembler.assemble(annotateCode(code), "TestInvalidArguments", false));

        Assertions.assertTrue(
                exception.getMessage().contains("Unknown label for jump instruction: UNKNOWN_LABEL"),
                "The error message should indicate an invalid label."
        );
    }

    @Test
    void testLabelCannotHaveInstructionName() {
        String code = """
                .ORG 0|0
                ADDR:
                    NOP
                """;
        Assembler assembler = createAssembler();
        AssemblerException exception = Assertions.assertThrows(AssemblerException.class, () -> assembler.assemble(annotateCode(code), "TestLabelIsInstruction", false));

        Assertions.assertTrue(
                exception.getMessage().contains("has the same name as an instruction"),
                "The error message should indicate a name conflict between label and instruction."
        );
    }

    private Integer findValueAtCoordinate(Map<int[], Integer> map, int[] coord) {
        for (Map.Entry<int[], Integer> entry : map.entrySet()) {
            if (Arrays.equals(entry.getKey(), coord)) {
                return entry.getValue();
            }
        }
        return null;
    }
}