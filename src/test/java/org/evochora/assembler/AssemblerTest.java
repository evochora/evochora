// src/test/java/org/evochora/assembler/AssemblerTest.java
package org.evochora.assembler;

import org.evochora.Config;
import org.evochora.organism.Instruction;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Map;

class AssemblerTest {

    @BeforeAll
    static void setup() {
        Instruction.init();
    }

    @Test
    void testAssemblerRecognizesValidCode() {
        String code = """
                .ORG 0|0
                NOP
                .ORG 5|5
                NOP
                """;
        Assembler assembler = new Assembler();
        ProgramMetadata metadata = assembler.assemble(code, "TestValidCode", false);
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
                    ADD %DR_B %DR_A
                .ENDM
                
                .ORG 0|0
                START:
                    SETI %DR_B DATA:100
                    SETI %DR_B DATA:200
                    $OUTER_MACRO %DR_A
                    $OUTER_MACRO %DR_A
                """;

        Assembler assembler = new Assembler();
        Assertions.assertDoesNotThrow(() -> assembler.assemble(code, "TestNestedMacros", false));
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

        Assembler assembler = new Assembler();
        AssemblerException exception = Assertions.assertThrows(AssemblerException.class, () -> assembler.assemble(code, "TestInfiniteLoop", false));

        Assertions.assertTrue(exception.getMessage().contains("Endlose Makro-Rekursion erkannt"), "Die Fehlermeldung sollte auf eine endlose Rekursion hinweisen.");
        String expectedPart = "CALL_LOOP_B -> INFINITE_LOOP -> INFINITE_LOOP";
        Assertions.assertTrue(
                exception.getMessage().contains(expectedPart),
                () -> "Die tatsächliche Fehlermeldung war: " + exception.getMessage()
        );
    }

    @Test
    void testMissingMacroEndDirective() {
        String code = """
                .MACRO $INCOMPLETE_MACRO
                    NOP
                """;

        Assembler assembler = new Assembler();
        AssemblerException exception = Assertions.assertThrows(AssemblerException.class, () -> assembler.assemble(code, "TestMissingEndm", false));

        Assertions.assertTrue(exception.getMessage().contains("Fehlendes .ENDM für Makro-Definition."), "Die Fehlermeldung sollte auf eine fehlende .ENDM-Direktive hinweisen.");
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

        Assembler assembler = new Assembler();
        AssemblerException exception = Assertions.assertThrows(AssemblerException.class, () -> assembler.assemble(code, "TestWrongMacroArgs", false));

        Assertions.assertTrue(exception.getMessage().contains("Falsche Anzahl an Argumenten für Makro"), "Die Fehlermeldung sollte auf eine falsche Anzahl an Makro-Argumenten hinweisen.");
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

        Assembler assembler = new Assembler();
        AssemblerException exception = Assertions.assertThrows(AssemblerException.class, () -> assembler.assemble(code, "TestDuplicateLabel", false));

        Assertions.assertTrue(exception.getMessage().contains("Label 'MY_LABEL' wurde mehrfach vergeben."), "Die Fehlermeldung sollte auf ein doppeltes Label hinweisen.");
    }

    @Test
    void testUnknownInstruction() {
        String code = """
                .ORG 0|0
                UNKNOWN_OPCODE
                """;

        Assembler assembler = new Assembler();
        AssemblerException exception = Assertions.assertThrows(AssemblerException.class, () -> assembler.assemble(code, "TestUnknownInstruction", false));

        Assertions.assertTrue(exception.getMessage().contains("Unbekannter Befehl: UNKNOWN_OPCODE"), "Die Fehlermeldung sollte auf einen unbekannten Befehl hinweisen.");
    }

    @Test
    void testInvalidInstructionArguments() {
        String code = """
                .ORG 0|0
                JUMP UNKNOWN_LABEL
                """;

        Assembler assembler = new Assembler();
        AssemblerException exception = Assertions.assertThrows(AssemblerException.class, () -> assembler.assemble(code, "TestInvalidArguments", false));

        Assertions.assertTrue(exception.getMessage().contains("JUMP erwartet ein Register oder Label."), "Die Fehlermeldung sollte auf ungültige Argumente hinweisen.");
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