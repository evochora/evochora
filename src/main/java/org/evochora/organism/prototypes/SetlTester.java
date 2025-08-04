// src/main/java/org/evochora/organism/prototypes/SetlTester.java
package org.evochora.organism.prototypes;

import org.evochora.assembler.AssemblyProgram;

public class SetlTester extends AssemblyProgram {

    @Override
    public String getAssemblyCode() {
        return """
                # Minimales Testprogramm für den SETL-Befehl
                .PLACE CODE:1 2 2
                .PLACE DATA:1 3 3
                .PLACE STRUCTURE:1 4 4
                .PLACE ENERGY:1 5 5
                
                .PLACE CODE:0 -2 -2
                #.PLACE DATA:0 -3 -3
                .PLACE STRUCTURE:0 -4 -4
                .PLACE ENERGY:0 -5 -5
                
                .REG %DR0 0
                # GEÄNDERT: SETL verwendet jetzt die TYPE:WERT Syntax
                SETL %DR0 DATA:123
                NOP
                """;
    }
}