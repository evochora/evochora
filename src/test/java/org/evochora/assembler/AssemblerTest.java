package org.evochora.assembler;

import org.evochora.organism.Instruction;
import org.evochora.world.Symbol;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class AssemblerTest {

    @BeforeAll
    static void setup() {
        // Initialize instruction registry once for tests that rely on it
        Instruction.init();
    }

    // ---------- Helpers ----------
    private static DefinitionExtractor.RoutineDefinition makeRoutine(String name, List<String> params, List<String> body) {
        return new DefinitionExtractor.RoutineDefinition(name, params, body, "lib.s");
    }

    private static AnnotatedLine line(String content) {
        return new AnnotatedLine(content, 1, "main.s");
    }

    private Assembler createAssembler() {
        return new Assembler();
    }

    private List<AnnotatedLine> annotateCode(String code) {
        List<AnnotatedLine> lines = new ArrayList<>();
        int lineNum = 1;
        for (String l : code.split("\\r?\\n")) {
            lines.add(new AnnotatedLine(l, lineNum++, "test.s"));
        }
        return lines;
    }

    private Integer findValueAtCoordinate(Map<int[], Integer> map, int[] coord) {
        for (Map.Entry<int[], Integer> entry : map.entrySet()) {
            if (Arrays.equals(entry.getKey(), coord)) {
                return entry.getValue();
            }
        }
        return null;
    }

    // ---------- Include/Dedup tests ----------
    @Test
    void include_sameSignature_isDedupeWithAliasTrampoline() {
        Map<String, DefinitionExtractor.RoutineDefinition> routines = new HashMap<>();
        routines.put("LIB.FOO", makeRoutine("LIB.FOO",
                Arrays.asList("A", "B"),
                Arrays.asList(
                        "NOP",
                        "LABEL_END:"
                )));
        Map<String, DefinitionExtractor.MacroDefinition> macros = new HashMap<>();
        CodeExpander expander = new CodeExpander("prog", routines, macros);

        List<AnnotatedLine> program = Arrays.asList(
                line(".INCLUDE LIB.FOO AS FOO1 WITH %X %Y"),
                line(".INCLUDE LIB.FOO AS FOO2 WITH %X %Y")
        );

        List<AnnotatedLine> out = expander.expand(program);

        String all = String.join("\n", out.stream().map(AnnotatedLine::content).toList());
        assertTrue(all.contains("FOO1:"), "Primary alias label missing");
        assertTrue(all.contains("FOO2:"), "Secondary alias label missing");
        assertTrue(all.contains("JMPI FOO1"), "Alias trampoline missing");
        long nops = out.stream().filter(l -> l.content().strip().equals("NOP")).count();
        assertEquals(1, nops, "Routine body should be emitted once");
    }

    @Test
    void includeStrict_sameSignature_emitsSecondInstance() {
        Map<String, DefinitionExtractor.RoutineDefinition> routines = new HashMap<>();
        routines.put("LIB.BAR", makeRoutine("LIB.BAR",
                Arrays.asList("P"),
                Arrays.asList("NOP")));
        Map<String, DefinitionExtractor.MacroDefinition> macros = new HashMap<>();
        CodeExpander expander = new CodeExpander("prog", routines, macros);

        List<AnnotatedLine> program = Arrays.asList(
                line(".INCLUDE LIB.BAR AS BAR1 WITH %R0"),
                line(".INCLUDE_STRICT LIB.BAR AS BAR2 WITH %R0")
        );

        List<AnnotatedLine> out = expander.expand(program);
        String all = String.join("\n", out.stream().map(AnnotatedLine::content).toList());

        assertTrue(all.contains("BAR1:"), "BAR1 label missing");
        assertTrue(all.contains("BAR2:"), "BAR2 label missing");
        long nops = out.stream().filter(l -> l.content().strip().equals("NOP")).count();
        assertEquals(2, nops, "STRICT must emit a second body");
    }

    // ---------- Assembler pipeline tests ----------
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

    @Test
    void testDefineDirective() {
        String code = """
                .DEFINE MY_CONSTANT DATA:42
                .REG %DR_A 0
                .ORG 0|0
                SETI %DR_A MY_CONSTANT
                """;
        Assembler assembler = createAssembler();
        ProgramMetadata metadata = assembler.assemble(annotateCode(code), "TestDefine", false);
        Map<int[], Integer> layout = metadata.machineCodeLayout();
        Integer instruction = findValueAtCoordinate(layout, new int[]{0, 0});
        Integer value = findValueAtCoordinate(layout, new int[]{2, 0});

        Assertions.assertNotNull(instruction);
        Assertions.assertNotNull(value);
        Assertions.assertEquals(42, Symbol.fromInt(value).toScalarValue());
    }
}