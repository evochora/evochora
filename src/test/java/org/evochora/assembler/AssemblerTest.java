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
        // Diese Initialisierung ist entscheidend, damit die Befehle gefunden werden.
        Instruction.init();
    }

    @Test
    void testSimpleLayoutWithOrgDirective() {
        // Dieser Test bleibt als "Rauchtest" für die Basis-Funktionalität.
        String code = """
                .ORG 0|0
                NOP
                .ORG 5|5
                NOP
                """;

        Assembler assembler = new Assembler();
        ProgramMetadata metadata = assembler.assemble(code, "TestSimpleLayout", false);
        Map<int[], Integer> layout = metadata.machineCodeLayout();

        Assertions.assertEquals(2, layout.size());
        Assertions.assertNotNull(findValueAtCoordinate(layout, new int[]{0, 0}));
        Assertions.assertNotNull(findValueAtCoordinate(layout, new int[]{5, 5}));
    }

    @Test
    void testFullLayoutOfFirstTwoBlocks() {
        // 1. Vorbereitung: Wir nehmen den Code der ersten beiden Test-Blöcke.
        String code = """
                .REG %DR_A 0
                .REG %DR_B 1
                .REG %DR_RESULT 3
                .REG %VEC_LEFT 5
                
                .MACRO $ASSERT_LITERAL REGISTER EXPECTED_LITERAL
                    SETL %DR_RESULT DATA:1
                    IFI REGISTER EXPECTED_LITERAL
                    SETL %DR_RESULT DATA:0
                    SYNC
                    SEEK %VEC_LEFT
                    POKE %DR_RESULT %VEC_LEFT
                .ENDM

                .ORG 2|1
                TEST_SETL:
                    .DIR 1|0
                    SETL %DR_A DATA:123
                    $ASSERT_LITERAL %DR_A DATA:123
                    JUMP TEST_SETR

                .ORG 2|3
                TEST_SETR:
                    .DIR 1|0
                    SETL %DR_B DATA:456
                    SETR %DR_A %DR_B
                    $ASSERT_LITERAL %DR_A DATA:456
                    JUMP TEST_SETV
                """;

        // 2. Ausführung
        Assembler assembler = new Assembler();
        ProgramMetadata metadata = assembler.assemble(code, "TestTwoBlocks", true); // Debug-Modus an!
        Map<int[], Integer> layout = metadata.machineCodeLayout();

        // 3. Überprüfung
        // Wir berechnen die erwartete Endposition des gesamten Codes.
        // Der JUMP im TEST_SETR Block ist die letzte Anweisung.
        // Start von TEST_SETR: .ORG 2|3
        // .DIR (0) + SETL (3) + SETR (3) + $ASSERT_LITERAL (15) = 21 Zellen
        // Der JUMP (Länge 3) beginnt also an Koordinate [2+21, 3] = [23, 3]
        int[] expectedJumpCoordinate = new int[]{23, 3};

        Integer jumpInstruction = findValueAtCoordinate(layout, expectedJumpCoordinate);

        Assertions.assertNotNull(jumpInstruction,
                "Der JUMP-Befehl am Ende von TEST_SETR wurde nicht an der erwarteten Koordinate [23, 3] gefunden.");
    }

    @Test
    void testFullLayoutOfFirstThreeBlocks() { // Name des Tests angepasst
        // 1. Vorbereitung: Wir nehmen den Code der ersten drei Test-Blöcke.
        String code = """
                .REG %DR_A 0
                .REG %DR_B 1
                .REG %DR_RESULT 3
                .REG %VEC_LEFT 5
                
                .MACRO $ASSERT_LITERAL REGISTER EXPECTED_LITERAL
                    SETL %DR_RESULT DATA:1
                    IFI REGISTER EXPECTED_LITERAL
                    SETL %DR_RESULT DATA:0
                    SYNC
                    SEEK %VEC_LEFT
                    POKE %DR_RESULT %VEC_LEFT
                .ENDM
                
                .MACRO $ASSERT_REG REGISTER_A REGISTER_B
                    SETL %DR_RESULT DATA:1
                    IFR REGISTER_A REGISTER_B
                    SETL %DR_RESULT DATA:0
                    SYNC
                    SEEK %VEC_LEFT
                    POKE %DR_RESULT %VEC_LEFT
                .ENDM

                .ORG 2|1
                TEST_SETL:
                    .DIR 1|0
                    SETL %DR_A DATA:123
                    $ASSERT_LITERAL %DR_A DATA:123
                    JUMP TEST_SETR

                .ORG 2|3
                TEST_SETR:
                    .DIR 1|0
                    SETL %DR_B DATA:456
                    SETR %DR_A %DR_B
                    $ASSERT_LITERAL %DR_A DATA:456
                    JUMP TEST_SETV
                    
                .ORG 2|5
                TEST_SETV:
                    .DIR 1|0
                    SETV %DR_A 7|8
                    SETV %DR_B 7|8
                    $ASSERT_REG %DR_A %DR_B
                    JUMP TEST_ADD
                """;

        // 2. Ausführung
        Assembler assembler = new Assembler();
        ProgramMetadata metadata = assembler.assemble(code, "TestThreeBlocks", false);
        Map<int[], Integer> layout = metadata.machineCodeLayout();

        // 3. Überprüfung
        // Wir berechnen die erwartete Position des JUMP am Ende des TEST_SETV Blocks.
        // Start von TEST_SETV: .ORG 2|5
        // .DIR (0) + SETV (3) + SETV (3) + $ASSERT_REG (15) = 21 Zellen
        // Der JUMP (Länge 3) beginnt also an Koordinate [2+21, 5] = [23, 5]
        int[] expectedJumpCoordinate = new int[]{23, 5};

        Integer jumpInstruction = findValueAtCoordinate(layout, expectedJumpCoordinate);

        Assertions.assertNotNull(jumpInstruction,
                "Der JUMP-Befehl am Ende von TEST_SETV wurde nicht an der erwarteten Koordinate [23, 5] gefunden.");
    }

    @Test
    void testFullLayoutOfCompleteTester() {
        // 1. Vorbereitung: Wir verwenden den VOLLSTÄNDIGEN Code des Testers.
        String code = """
                # Umfassendes Testprogramm mit finaler, korrekter Logik und den neuen IFR/IFI Befehlen.
                
                .REG %DR_A 0
                .REG %DR_B 1
                .REG %DR_C 2
                .REG %DR_RESULT 3
                .REG %VEC_RIGHT 4
                .REG %VEC_LEFT 5
                
                .MACRO $ASSERT_LITERAL REGISTER EXPECTED_LITERAL
                    SETL %DR_RESULT DATA:1
                    IFI REGISTER EXPECTED_LITERAL
                    SETL %DR_RESULT DATA:0
                    SYNC
                    SEEK %VEC_LEFT
                    POKE %DR_RESULT %VEC_LEFT
                .ENDM

                .MACRO $ASSERT_REG REGISTER_A REGISTER_B
                    SETL %DR_RESULT DATA:1
                    IFR REGISTER_A REGISTER_B
                    SETL %DR_RESULT DATA:0
                    SYNC
                    SEEK %VEC_LEFT
                    POKE %DR_RESULT %VEC_LEFT
                .ENDM

                .ORG 0|0
                SETV %VEC_RIGHT 1|0
                SETV %VEC_LEFT -1|0
                JUMP TEST_SETL

                .ORG 2|1
                TEST_SETL:
                    .DIR 1|0
                    SETL %DR_A DATA:123
                    $ASSERT_LITERAL %DR_A DATA:123
                    JUMP TEST_SETR

                .ORG 2|3
                TEST_SETR:
                    .DIR 1|0
                    SETL %DR_B DATA:456
                    SETR %DR_A %DR_B
                    $ASSERT_LITERAL %DR_A DATA:456
                    JUMP TEST_SETV
                
                .ORG 2|5
                TEST_SETV:
                    .DIR 1|0
                    SETV %DR_A 7|8
                    SETV %DR_B 7|8
                    $ASSERT_REG %DR_A %DR_B
                    JUMP TEST_ADD

                .ORG 2|7
                TEST_ADD:
                    .DIR 1|0
                    SETL %DR_A DATA:10
                    SETL %DR_B DATA:20
                    ADD %DR_A %DR_B
                    $ASSERT_LITERAL %DR_A DATA:30
                    JUMP TEST_SUB

                .ORG 2|9
                TEST_SUB:
                    .DIR 1|0
                    SETV %DR_A 5|5
                    SETV %DR_B 1|2
                    SUB %DR_A %DR_B
                    SETV %DR_C 4|3
                    $ASSERT_REG %DR_A %DR_C
                    JUMP TEST_NAND

                .ORG 2|11
                TEST_NAND:
                    .DIR 1|0
                    SETL %DR_A DATA:5
                    SETL %DR_B DATA:3
                    NAND %DR_A %DR_B
                    $ASSERT_LITERAL %DR_A DATA:-2
                    JUMP TEST_PUSHPOP

                .ORG 2|13
                TEST_PUSHPOP:
                    .DIR 1|0
                    SETL %DR_A DATA:999
                    PUSH %DR_A
                    SETL %DR_A DATA:0
                    POP %DR_A
                    $ASSERT_LITERAL %DR_A DATA:999
                    JUMP TEST_IFS

                .ORG 2|15
                TEST_IFS:
                    .DIR 1|0
                    SETL %DR_A DATA:10
                    SETL %DR_B DATA:20
                    SETL %DR_RESULT DATA:1
                    LTR %DR_A %DR_B
                    SETL %DR_RESULT DATA:0
                    PUSH %DR_RESULT
                    
                    SETL %DR_RESULT DATA:1
                    GTI %DR_B DATA:10
                    SETL %DR_RESULT DATA:0
                    
                    POP %DR_A
                    ADD %DR_RESULT %DR_A
                    
                    SETV %DR_C 0|0
                    SETL %DR_C DATA:0
                    
                    SYNC
                    SEEK %VEC_LEFT
                    POKE %DR_RESULT %VEC_LEFT
                    JUMP END_OF_ALL_TESTS

                .ORG 0|23
                END_OF_ALL_TESTS:
                NOP
                """;

        // 2. Ausführung
        Assembler assembler = new Assembler();
        ProgramMetadata metadata = assembler.assemble(code, "FullTester", false);
        Map<int[], Integer> layout = metadata.machineCodeLayout();

        // 3. Überprüfung: Wir prüfen nur, ob die allerletzte Anweisung (NOP)
        // an der korrekten, finalen Koordinate platziert wurde.
        int[] expectedNopCoordinate = new int[]{0, 23};

        Integer nopInstruction = findValueAtCoordinate(layout, expectedNopCoordinate);

        Assertions.assertNotNull(nopInstruction,
                "Die finale NOP-Anweisung wurde nicht an der erwarteten Koordinate [0, 23] gefunden.");

        // Wir können sogar prüfen, ob es der richtige Opcode ist.
        int nopOpcode = Instruction.getInstructionIdByName("NOP") | Config.TYPE_CODE;
        Assertions.assertEquals(nopOpcode, nopInstruction, "Die Anweisung an [0, 23] sollte ein NOP sein.");
    }

    // Hilfsmethode
    private Integer findValueAtCoordinate(Map<int[], Integer> map, int[] coord) {
        for (Map.Entry<int[], Integer> entry : map.entrySet()) {
            if (Arrays.equals(entry.getKey(), coord)) {
                return entry.getValue();
            }
        }
        return null;
    }
}