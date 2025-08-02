// src/main/java/org/evochora/organism/prototypes/AllOpcodesTester.java
package org.evochora.organism.prototypes;

import org.evochora.AssemblyProgram;

public class AllOpcodesTester extends AssemblyProgram {
    @Override
    public String getAssemblyCode() {
        return """
                # TEST 4: Restliche Opcodes
                .REG %VEC_UP 0
                .REG %DIFF_VEC 1
                .REG %MY_ENERGY 2
                
                # Teste TURN
                SETV %VEC_UP 0|-1 # Vektor für "oben"
                TURN %VEC_UP      # Ändere den DV (Direction Vector) auf "oben"
                
                # Teste DIFF & NRG
                NOP               # Führe einen Schritt nach oben aus
                DIFF %DIFF_VEC    # Sollte (0, -1) in %DIFF_VEC speichern
                NRG %MY_ENERGY    # Speichere die eigene Energie in %MY_ENERGY
                NOP
                """;
    }
}