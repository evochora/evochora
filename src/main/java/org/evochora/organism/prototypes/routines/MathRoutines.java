// src/main/java/org/evochora/organism/prototypes/routines/MathRoutines.java
package org.evochora.organism.prototypes.routines;

import org.evochora.assembler.AssemblyRoutine;

public class MathRoutines extends AssemblyRoutine {

    @Override
    public String getAssemblyCodeTemplate() {
        return """
                # --- Subroutine zum Addieren zweier Werte ---
                # Verwendet Stack: Erwartet 2 Werte, legt 1 Ergebnis ab.
                
                ADD_TWO_VALUES:
                    .REG _REG_TMP_A_ 0
                    .REG _REG_TMP_B_ 1
                
                    POP _REG_TMP_B_     # Holt den zweiten Wert vom Stack
                    POP _REG_TMP_A_     # Holt den ersten Wert vom Stack
                    ADDR _REG_TMP_A_ _REG_TMP_B_
                    PUSH _REG_TMP_A_    # Legt das Ergebnis auf den Stack
                    RET
                """;
    }
}