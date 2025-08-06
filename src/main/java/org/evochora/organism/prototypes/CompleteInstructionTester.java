// src/main/java/org/evochora/organism/prototypes/CompleteInstructionTester.java
package org.evochora.organism.prototypes;

import org.evochora.assembler.AssemblyProgram;

public class CompleteInstructionTester extends AssemblyProgram {

    @Override
    public String getAssemblyCode() {
        return """
                # Finaler, korrekter Test-Organismus mit korrigierter Makro-Logik.
                
                # --- Register-Definitionen ---
                .REG %DR_A 0
                .REG %DR_B 1
                .REG %DR_C 2
                .REG %DR_RESULT 3
                .REG %VEC_RIGHT 4
                .REG %VEC_LEFT 5
                .REG %VEC_DOWN 6
                
                # --- Makro-Definitionen ---
                .MACRO $ASSERT_LITERAL REGISTER EXPECTED_LITERAL
                    SETI %DR_RESULT DATA:1
                    IFI REGISTER EXPECTED_LITERAL
                    SETI %DR_RESULT DATA:0
                    # KORREKTUR: Der überflüssige SYNC-Befehl wurde hier entfernt.
                    SEEK %VEC_LEFT
                    POKE %DR_RESULT %VEC_LEFT
                .ENDM

                .MACRO $ASSERT_REG REGISTER_A REGISTER_B
                    SETI %DR_RESULT DATA:1
                    IFR REGISTER_A REGISTER_B
                    SETI %DR_RESULT DATA:0
                    # KORREKTUR: Der überflüssige SYNC-Befehl wurde hier entfernt.
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
                    SYNC # DP wird hier einmal korrekt für den Test positioniert.
                    .DIR 1|0
                    SETI %DR_A DATA:123
                    $ASSERT_LITERAL %DR_A DATA:123
                    JMPI TEST_SETR

                .ORG 2|3
                TEST_SETR:
                    SYNC
                    .DIR 1|0
                    SETI %DR_B DATA:456
                    SETR %DR_A %DR_B
                    $ASSERT_LITERAL %DR_A DATA:456
                    JMPI TEST_SETV
                
                .ORG 2|5
                TEST_SETV:
                    SYNC
                    .DIR 1|0
                    SETV %DR_A 7|8
                    SETV %DR_B 7|8
                    $ASSERT_REG %DR_A %DR_B
                    JMPI TEST_ADD

                .ORG 2|7
                TEST_ADD:
                    SYNC
                    .DIR 1|0
                    SETI %DR_A DATA:10
                    SETI %DR_B DATA:20
                    ADD %DR_A %DR_B
                    $ASSERT_LITERAL %DR_A DATA:30
                    JMPI TEST_SUB

                .ORG 2|9
                TEST_SUB:
                    SYNC
                    .DIR 1|0
                    SETV %DR_A 5|5
                    SETV %DR_B 1|2
                    SUB %DR_A %DR_B
                    SETV %DR_C 4|3
                    $ASSERT_REG %DR_A %DR_C
                    JMPI TEST_NAND

                .ORG 2|11
                TEST_NAND:
                    SYNC
                    .DIR 1|0
                    SETI %DR_A DATA:5
                    SETI %DR_B DATA:3
                    NAND %DR_A %DR_B
                    $ASSERT_LITERAL %DR_A DATA:-2
                    JMPI TEST_PUSHPOP

                .ORG 2|13
                TEST_PUSHPOP:
                    SYNC
                    .DIR 1|0
                    SETI %DR_A DATA:999
                    PUSH %DR_A
                    SETI %DR_A DATA:0
                    POP %DR_A
                    $ASSERT_LITERAL %DR_A DATA:999
                    JMPI TEST_IFS

                .ORG 2|15
               TEST_IFS:
                   SYNC
                   .DIR 1|0
                   SETI %DR_A DATA:10
                   SETI %DR_B DATA:20
                   SETI %DR_RESULT DATA:1
                   LTR %DR_A %DR_B
                   SETI %DR_RESULT DATA:0
                   PUSH %DR_RESULT

                   SETI %DR_RESULT DATA:1
                   GTI %DR_B DATA:10
                   SETI %DR_RESULT DATA:0

                   POP %DR_A
                   ADD %DR_RESULT %DR_A

                   SEEK %VEC_LEFT
                   POKE %DR_RESULT %VEC_LEFT
                   JMPI TEST_DIFF

               # --- NEUE TESTS ---

               .ORG 2|17
               TEST_DIFF:
                   SYNC # DP = IP
                   .DIR 1|0
                   SEEK %VEC_RIGHT # DP = IP + [1,0]
                   DIFF %DR_A      # DR_A = IP - DP = IP - (IP + [1,0]) = [-1,0]
                   SETV %DR_B -1|0
                   $ASSERT_REG %DR_A %DR_B
                   JMPI TEST_POS

               .ORG 2|19
               TEST_POS:
                   # Der Code für diesen Test ist 2 Zellen lang (NOP, POS).
                   # Der Test startet bei [2,19], der IP ist danach bei [4,19].
                   # Die Spawn-Position ist [0,0] (Annahme, da .ORG 0|0 am Anfang steht)
                   # Erwartetes Ergebnis: IP - SPAWN = [4,19] - [0,0] = [4,19]
                   SYNC
                   .DIR 1|0
                   NOP
                   POS %DR_A
                   SETV %DR_B 4|19 # Inkrementiert durch den NOP + POS Befehl
                   $ASSERT_REG %DR_A %DR_B
                   JMPI TEST_NRG

               .ORG 2|21
               TEST_NRG:
                   SYNC
                   .DIR 1|0
                   NRG %DR_A # DR_A bekommt die aktuelle Energie
                   SETI %DR_RESULT DATA:1 # Annahme: Fehlschlag
                   GTI %DR_A DATA:0       # Teste, ob Energie größer als 0 ist
                   SETI %DR_RESULT DATA:0
                   SEEK %VEC_LEFT
                   POKE %DR_RESULT %VEC_LEFT
                   JMPI TEST_SCAN

               .ORG 2|23
               TEST_SCAN:
                   # Platziere einen Test-Wert rechts neben dem Start
                   .PLACE DATA:777 2|24
                   SYNC
                   .DIR 1|0
                   SCAN %DR_A %VEC_DOWN
                   $ASSERT_LITERAL %DR_A DATA:777
                   JMPI TEST_PEEK

               .ORG 2|25
               TEST_PEEK:
                   # Platziere einen Test-Wert
                   .PLACE ENERGY:50 2|26
                   SYNC
                   .DIR 1|0
                   PEEK %DR_A %VEC_DOWN  # Lese den Wert, Zelle sollte danach leer sein

                   # Test 1: Wurde der richtige Wert gelesen?
                   $ASSERT_LITERAL %DR_A ENERGY:50

                   # Test 2: Ist die Zelle jetzt leer (CODE:0)?
                   SCAN %DR_B %VEC_RIGHT
                   $ASSERT_LITERAL %DR_B CODE:0
                   JMPI TEST_TURN

               .ORG 2|27
               TEST_TURN:
                   # Dieser Test ist räumlich und muss visuell überprüft werden.
                   # Er schreibt ein "Erfolg"-Zeichen, um zu zeigen, dass er gelaufen ist.
                   SYNC
                   .DIR 1|0
                   # Drehe den Organismus nach unten
                   TURN %VEC_DOWN
                   .DIR 0|1
                   # Der NOP wird jetzt UNTER dem TURN platziert
                   NOP
                   TURN %VEC_RIGHT
                   .DIR 1|0
                   # Schreibe von der neuen Position [2,28] aus eine Struktur nach rechts,
                   # um die neue Position des IP visuell zu markieren.
                   SETI %DR_A STRUCTURE:1
                   POKE %DR_A %VEC_DOWN

                   # Test als erfolgreich markieren
                   SETI %DR_RESULT DATA:0
                   SEEK %VEC_LEFT
                   POKE %DR_RESULT %VEC_LEFT
                   JMPI END_OF_ALL_TESTS

               # --- Ende der Tests ---
               .ORG 0|31 # Etwas mehr Platz gelassen
               END_OF_ALL_TESTS:
               NOP
                """;
    }
}