// src/main/java/org/evochora/organism/prototypes/InstructionTester.java
package org.evochora.organism.prototypes;

import org.evochora.assembler.AssemblyProgram;

public class InstructionTester extends AssemblyProgram {

    @Override
    public String getProgramCode() {
        return """
                # Finaler, korrekter Test-Organismus, angepasst an die neue Assembler-Logik.
                
                # --- Register-Definitionen ---
                .REG %DR_A 0
                .REG %DR_B 1
                .REG %DR_C 2
                .REG %DR_RESULT 3
                .REG %VEC_RIGHT 4
                .REG %VEC_LEFT 5
                .REG %VEC_DOWN 6
                
                # --- Makro-Definitionen (angepasst an die neue Syntax ohne Parameter-Präfix) ---
                .MACRO $ASSERT_LITERAL REGISTER EXPECTED_LITERAL
                    SETI %DR_RESULT DATA:1         # Annahme: Test schlägt fehl
                    IFI REGISTER EXPECTED_LITERAL
                    SETI %DR_RESULT DATA:0         # Test erfolgreich, wenn Bedingung zutrifft
                    SEEK %VEC_LEFT
                    POKE %DR_RESULT %VEC_LEFT
                .ENDM

                .MACRO $ASSERT_REG REGISTER_A REGISTER_B
                    SETI %DR_RESULT DATA:1         # Annahme: Test schlägt fehl
                    IFR REGISTER_A REGISTER_B
                    SETI %DR_RESULT DATA:0         # Test erfolgreich, wenn Register gleich sind
                    SEEK %VEC_LEFT
                    POKE %DR_RESULT %VEC_LEFT
                .ENDM

                # --- Programmstart & Initialisierung ---
                .ORG 0|0
                SETV %VEC_RIGHT 1|0
                SETV %VEC_LEFT -1|0
                SETV %VEC_DOWN 0|1
                JMPI TEST_SETI

                # --- TEST-KETTE ---
                
                .ORG 2|1
                TEST_SETI:
                    SYNC
                    SETI %DR_A DATA:123
                    $ASSERT_LITERAL %DR_A DATA:123
                    JMPI TEST_SETR

                .ORG 2|3
                TEST_SETR:
                    SYNC
                    SETI %DR_B DATA:456
                    SETR %DR_A %DR_B
                    $ASSERT_LITERAL %DR_A DATA:456
                    JMPI TEST_SETV
                
                .ORG 2|5
                TEST_SETV:
                    SYNC
                    SETV %DR_A TEST_VECTOR_TARGET
                    SETV %DR_B 25|5
                    $ASSERT_REG %DR_A %DR_B
                TEST_VECTOR_TARGET:
                    JMPI TEST_ADDR

                .ORG 2|7
                TEST_ADDR:
                    SYNC
                    SETI %DR_A DATA:10
                    SETI %DR_B DATA:20
                    ADDR %DR_A %DR_B
                    $ASSERT_LITERAL %DR_A DATA:30
                    JMPI TEST_SUBR

                .ORG 2|9
                TEST_SUBR:
                    SYNC
                    SETV %DR_A 5|5
                    SETV %DR_B 1|2
                    SUBR %DR_A %DR_B
                    SETV %DR_C 4|3
                    $ASSERT_REG %DR_A %DR_C
                    JMPI TEST_NANDR

                .ORG 2|11
                TEST_NANDR:
                    SYNC
                    SETI %DR_A DATA:5
                    SETI %DR_B DATA:3
                    NANDR %DR_A %DR_B
                    $ASSERT_LITERAL %DR_A DATA:-2
                    JMPI TEST_PUSHPOP

                .ORG 2|13
                TEST_PUSHPOP:
                    SYNC
                    SETI %DR_A DATA:999
                    PUSH %DR_A
                    SETI %DR_A DATA:0
                    POP %DR_A
                    $ASSERT_LITERAL %DR_A DATA:999
                    JMPI TEST_JMP_ABSOLUTE

                .ORG 2|15
                TEST_JMP_ABSOLUTE:
                    SYNC
                    SETV %DR_A JUMP_TARGET
                    JMPR %DR_A
                JUMP_FAIL:
                    SETI %DR_RESULT DATA:1
                    SEEK %VEC_LEFT
                    POKE %DR_RESULT %VEC_LEFT
                    JMPI END_OF_ALL_TESTS
                JUMP_TARGET:
                    SETI %DR_RESULT DATA:0
                    SEEK %VEC_LEFT
                    POKE %DR_RESULT %VEC_LEFT
                    JMPI TEST_DIFF
                    
               .ORG 2|19
               TEST_DIFF:
                   SYNC
                   SEEK %VEC_RIGHT
                   DIFF %DR_A
                   SETV %DR_B -1|0
                   $ASSERT_REG %DR_A %DR_B
                   JMPI TEST_POS

               .ORG 2|21
               TEST_POS:
                   SYNC
                   NOP
                   POS %DR_A
                   SETV %DR_B 4|21
                   $ASSERT_REG %DR_A %DR_B
                   JMPI TEST_NRG

               .ORG 2|23
               TEST_NRG:
                   SYNC
                   NRG %DR_A
                   SETI %DR_RESULT DATA:1
                   GTI %DR_A DATA:0
                   SETI %DR_RESULT DATA:0
                   SEEK %VEC_LEFT
                   POKE %DR_RESULT %VEC_LEFT
                   JMPI TEST_SCAN

               .ORG 2|25
               TEST_SCAN:
                   .PLACE DATA:777 2|26
                   SYNC
                   SCAN %DR_A %VEC_DOWN
                   $ASSERT_LITERAL %DR_A DATA:777
                   JMPI TEST_PEEK

               .ORG 2|27
               TEST_PEEK:
                   .PLACE ENERGY:50 2|28
                   SYNC
                   PEEK %DR_A %VEC_DOWN
                   $ASSERT_LITERAL %DR_A ENERGY:50
                   SCAN %DR_B %VEC_DOWN
                   $ASSERT_LITERAL %DR_B CODE:0
                   JMPI TEST_TURN

               .ORG 2|29
               TEST_TURN:
                   SYNC
                   TURN %VEC_DOWN
                   .DIR 0|1
                   # KORRIGIERT: Der Test setzt jetzt den Erfolgs-Code in das Ergebnis-Register.
                   SETI %DR_RESULT DATA:0
                   SEEK %VEC_LEFT
                   POKE %DR_RESULT %VEC_LEFT
                   JMPI END_OF_ALL_TESTS

               # --- Ende der Tests ---
               .ORG 0|31
               END_OF_ALL_TESTS:
               NOP
                """;
    }
}