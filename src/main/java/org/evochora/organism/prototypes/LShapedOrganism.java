// src/main/java/org/evochora/organism/prototypes/LShapedOrganism.java
package org.evochora.organism.prototypes;

import org.evochora.assembler.AssemblyProgram;

public class LShapedOrganism extends AssemblyProgram {

    @Override
    public String getAssemblyCode() {
        return """
                # Ein Test-Organismus, der eine L-Form hat
                .REG %DR0 0
                .REG %DR1 1
                
                # Gehe 3 Schritte in die aktuelle Richtung (Start: 1|0 -> rechts)
                # GEÄNDERT: SETL verwendet jetzt die TYPE:WERT Syntax
                SETL %DR0 DATA:1
                SETL %DR0 DATA:2
                SETL %DR0 DATA:3
                
                # Ändere die Schreibrichtung nach unten
                .DIR 0|1
                
                # Gehe 2 Schritte in die neue Richtung
                SETL %DR1 DATA:4
                SETL %DR1 DATA:5
                NOP
                """;
    }
}