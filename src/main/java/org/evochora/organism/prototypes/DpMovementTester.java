// src/main/java/org/evochora/organism/prototypes/DpMovementTester.java
package org.evochora.organism.prototypes;

import org.evochora.assembler.AssemblyProgram; // GEÄNDERT: Neuer Importpfad

public class DpMovementTester extends AssemblyProgram {
    @Override
    public String getAssemblyCode() {
        return """
                # TEST 3: DP-Bewegung mit SEEK und SYNC
                .REG %VEC_R 0
                .REG %VEC_D 1
                
                SETV %VEC_R 1|0  # Vektor für "rechts"
                SETV %VEC_D 0|1  # Vektor für "runter"
                
                SEEK %VEC_R      # DP einen Schritt nach rechts
                SEEK %VEC_R      # DP noch einen Schritt nach rechts
                SEEK %VEC_D      # DP einen Schritt nach unten
                SYNC             # Setze DP zurück auf die Position des IP
                NOP
                """;
    }
}