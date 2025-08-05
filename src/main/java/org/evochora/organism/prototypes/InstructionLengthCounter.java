// src/main/java/org/evochora/organism/prototypes/InstructionLengthCounter.java
package org.evochora.organism.prototypes;

import org.evochora.assembler.AssemblyProgram;

public class InstructionLengthCounter extends AssemblyProgram {

    @Override
    public String getAssemblyCode() {
        return """
                # Ein Testprogramm, das die Länge einer Subroutine dynamisch berechnet.
                
                .REG %DR_START_POS 0
                .REG %DR_END_POS 1
                .REG %DR_DELTA 2
                .REG %DR_LENGTH 3
                .REG %VEC_DOWN 4
                .REG %VEC_START 5
                
                # Gehe zum Start der zu messenden Routine
                JUMP ROUTINE_START
                
                # Hier beginnt die Logik, die die Länge der Routine berechnet
                MEASURE_LOGIC:
                # Hole die Startposition der Routine
                SETR %DR_START_POS %DR_END_POS
                
                # Führe den NOP-Befehl aus
                NOP
                
                # Hole die Endposition der Routine
                SETR %DR_END_POS %DR_START_POS
                
                # Berechne die Differenz und speichere sie
                DIFF %DR_DELTA
                
                # Speichere die Länge in einem Register
                SETL %DR_LENGTH DATA:0
                ADD %DR_LENGTH %DR_DELTA
                
                # Platziere das Ergebnis in der Welt
                POKE %DR_LENGTH %VEC_DOWN
                
                JUMP END_OF_PROGRAM
                
                # Hier beginnt die zu messende Routine
                ROUTINE_START:
                NOP
                NOP
                NOP
                
                END_OF_ROUTINE:
                # Gehe zurück zur Mess-Logik
                JUMP MEASURE_LOGIC
                
                END_OF_PROGRAM:
                NOP
                
                """;
    }
}