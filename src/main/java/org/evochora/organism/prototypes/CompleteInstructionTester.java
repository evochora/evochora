// src/main/java/org/evochora/organism/prototypes/CompleteInstructionTester.java
package org.evochora.organism.prototypes;

import org.evochora.Config;
import org.evochora.assembler.AssemblyProgram;

public class CompleteInstructionTester extends AssemblyProgram {

    @Override
    public String getAssemblyCode() {
        return """
                # Ein umfassendes Testprogramm, das alle Instruktionen testet
                # Die Ergebnisse werden visuell in der Welt dargestellt.
                # Marker: 
                # DATA:1 = Test erfolgreich
                # DATA:-1 = Test fehlgeschlagen
                
                .REG %DR_START_TEST 0
                .REG %DR_END_TEST 1
                .REG %DR_DELTA 2
                .REG %DR_RESULT 3
                .REG %DR_TEMP 4
                .REG %DR_TEMP2 5
                .REG %VEC_UP 6
                .REG %VEC_RIGHT 7
                .REG %VEC_DOWN 8
                
                # Lege die Vektoren fest
                SETV %VEC_UP 0|-1
                SETV %VEC_RIGHT 1|0
                SETV %VEC_DOWN 0|1
                
                # Beginn der einfachen Tests - pro Zeile ein Test
                # Der IP bewegt sich horizontal (DV: 1|0)
                .DIR 1|0
                
                JUMP SETUP_JUMP_LOGIC

                #################################################################
                # Test 1: SETL
                #################################################################
                TEST_SETL:
                SETR %DR_START_TEST %DR_START_TEST
                SETL %DR_TEMP DATA:123
                IF %DR_TEMP DATA:123
                JUMP OK_SETL
                SETL %DR_RESULT DATA:-1
                JUMP POKE_RESULT
                OK_SETL:
                SETL %DR_RESULT DATA:1
                JUMP POKE_RESULT
                
                #################################################################
                # Test 2: SETR
                #################################################################
                TEST_SETR:
                SETR %DR_START_TEST %DR_START_TEST
                SETL %DR_TEMP DATA:456
                SETR %DR_TEMP2 %DR_TEMP
                IF %DR_TEMP2 DATA:456
                JUMP OK_SETR
                SETL %DR_RESULT DATA:-1
                JUMP POKE_RESULT
                OK_SETR:
                SETL %DR_RESULT DATA:1
                JUMP POKE_RESULT
                
                #################################################################
                # Test 3: SETV
                #################################################################
                TEST_SETV:
                SETR %DR_START_TEST %DR_START_TEST
                SETV %DR_TEMP 5|6
                SETV %DR_TEMP2 5|6
                IF %DR_TEMP %DR_TEMP2
                JUMP OK_SETV
                SETL %DR_RESULT DATA:-1
                JUMP POKE_RESULT
                OK_SETV:
                SETL %DR_RESULT DATA:1
                JUMP POKE_RESULT
                
                #################################################################
                # Test 4: ADD
                #################################################################
                TEST_ADD:
                SETR %DR_START_TEST %DR_START_TEST
                SETL %DR_TEMP DATA:10
                SETL %DR_TEMP2 DATA:20
                ADD %DR_TEMP %DR_TEMP2
                IF %DR_TEMP DATA:30
                JUMP OK_ADD
                SETL %DR_RESULT DATA:-1
                JUMP POKE_RESULT
                OK_ADD:
                SETL %DR_RESULT DATA:1
                JUMP POKE_RESULT
                
                #################################################################
                # Test 5: SUB
                #################################################################
                TEST_SUB:
                SETR %DR_START_TEST %DR_START_TEST
                SETL %DR_TEMP DATA:30
                SETL %DR_TEMP2 DATA:15
                SUB %DR_TEMP %DR_TEMP2
                IF %DR_TEMP DATA:15
                JUMP OK_SUB
                SETL %DR_RESULT DATA:-1
                JUMP POKE_RESULT
                OK_SUB:
                SETL %DR_RESULT DATA:1
                JUMP POKE_RESULT
                
                #################################################################
                # Test 6: NAND
                #################################################################
                TEST_NAND:
                SETR %DR_START_TEST %DR_START_TEST
                SETL %DR_TEMP DATA:5
                SETL %DR_TEMP2 DATA:3
                NAND %DR_TEMP %DR_TEMP2
                # 5 = 0101, 3 = 0011 -> AND = 0001 -> NAND = 1110 = -2
                IF %DR_TEMP DATA:-2
                JUMP OK_NAND
                SETL %DR_RESULT DATA:-1
                JUMP POKE_RESULT
                OK_NAND:
                SETL %DR_RESULT DATA:1
                JUMP POKE_RESULT
                
                #################################################################
                # Test 7: IF/IFLT/IFGT
                #################################################################
                TEST_IF:
                SETR %DR_START_TEST %DR_START_TEST
                SETL %DR0 DATA:10
                SETL %DR1 DATA:10
                SETL %DR2 DATA:20
                SETL %DR3 DATA:5
                
                # IF
                IF %DR0 %DR1
                JUMP TEST_IFLT
                SETL %DR4 DATA:-1
                POKE %DR4 %VEC_UP
                JUMP POKE_RESULT
                
                TEST_IFLT:
                # IFLT
                IFLT %DR0 %DR2
                JUMP TEST_IFGT
                SETL %DR4 DATA:-1
                POKE %DR4 %VEC_UP
                JUMP POKE_RESULT
                
                TEST_IFGT:
                # IFGT
                IFGT %DR0 %DR3
                JUMP OK_IF
                SETL %DR4 DATA:-1
                POKE %DR4 %VEC_UP
                JUMP POKE_RESULT
                
                OK_IF:
                SETL %DR4 DATA:1
                POKE %DR4 %VEC_UP
                JUMP POKE_RESULT
                
                #################################################################
                # Test 8: NRG
                #################################################################
                TEST_NRG:
                SETR %DR_START_TEST %DR_START_TEST
                NRG %DR_TEMP
                IF %DR_TEMP DATA:2000
                JUMP OK_NRG
                SETL %DR_RESULT DATA:-1
                JUMP POKE_RESULT
                OK_NRG:
                SETL %DR_RESULT DATA:1
                JUMP POKE_RESULT
                
                POKE_RESULT:
                POKE %DR_RESULT %VEC_UP
                
                END_TEST:
                NOP
                DIFF %DR_DELTA
                
                # Springe zum nächsten Test
                JUMP -25|1
                
                #################################################################
                # Hier beginnt der Bereich für komplexere Tests
                #################################################################
                SETUP_JUMP_LOGIC:
                # Springe aus der Zeilen-Logik heraus
                JUMP COMPLEX_TESTS
                
                #################################################################
                # Test 9: TURN
                # Dieser Test braucht eine separate Zeile, da er den DV ändert
                #################################################################
                COMPLEX_TESTS:
                SETR %DR_START_TEST %DR_START_TEST
                TURN %VEC_UP
                NOP
                DIFF %DR_DELTA
                SETV %DR_TEMP 0|-1
                IF %DR_DELTA %DR_TEMP
                JUMP OK_TURN
                SETL %DR_RESULT DATA:-1
                POKE %DR_RESULT %VEC_UP
                JUMP END_OF_PROGRAM
                
                OK_TURN:
                SETL %DR_RESULT DATA:1
                POKE %DR_RESULT %VEC_UP
                
                #################################################################
                # Test 10: SYNC/SEEK/PEEK/POKE - Welt-Interaktion
                # Dieser Test interagiert mit der Welt und braucht Platz
                #################################################################
                
                # Teste POKE
                .PLACE DATA:0 2|0 # Setze ein leeres DATA-Feld daneben
                SETR %DR_START_TEST %DR_START_TEST
                SETL %DR_TEMP DATA:42
                SEEK %VEC_RIGHT
                POKE %DR_TEMP %VEC_RIGHT
                
                # Teste PEEK
                SEEK %VEC_RIGHT
                PEEK %DR_TEMP2 %VEC_UP # Hol den Wert zurück
                
                IF %DR_TEMP2 DATA:42
                JUMP OK_PEEK_POKE
                SETL %DR_RESULT DATA:-1
                POKE %DR_RESULT %VEC_DOWN
                JUMP END_OF_PROGRAM
                
                OK_PEEK_POKE:
                SETL %DR_RESULT DATA:1
                POKE %DR_RESULT %VEC_DOWN
                
                END_OF_PROGRAM:
                SETL %DR_RESULT DATA:99
                POKE %DR_RESULT %VEC_DOWN
                
                """;
    }
}