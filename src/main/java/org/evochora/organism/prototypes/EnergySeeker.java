// In einer neuen Datei, z.B. ErrorTest.java
package org.evochora.organism.prototypes;

import org.evochora.assembler.AssemblyProgram;

public class EnergySeeker extends AssemblyProgram {

    @Override
    public String getAssemblyCode() {
        return """
                # Register-Definitionen
                              .REG %DR_TMP 0
                              .REG %DR_VEC 1
                              .REG %DR_ENERGY 2
                              .REG %DR_THRESHOLD 3
                              .REG %DR_RETURN 4
                
                              # --- Makro zum Scannen und Aufnehmen eines Feldes (unverändert) ---
                              .MACRO $SCAN_AND_PEEK_FIELD %SEARCH_VEC
                                  SCAN %DR_TMP %SEARCH_VEC
                                  IFTI %DR_TMP ENERGY:0
                                  PEEK %DR_TMP %SEARCH_VEC
                              .ENDM
                
                              # --- Makro zur kontrollierten Bewegung (unverändert) ---
                              .MACRO $TRY_MOVE_AND_JUMP %MOVE_VEC %JUMP_LABEL
                                  SETV %DR_VEC %MOVE_VEC
                                  SCAN %DR_TMP %DR_VEC
                
                                  IFR %DR_TMP CODE:0
                                  JUMP @@MOVE_IT_IS_EMPTY
                
                                  JUMP @@CHECK_END
                
                              @@MOVE_IT_IS_EMPTY:
                                  SEEK %DR_VEC
                                  JUMP %JUMP_LABEL
                              @@CHECK_END:
                              .ENDM
                
                              # --- Haupt-Subroutine zur Energiesuche (korrigiert) ---
                              .MACRO $SEEK_ENERGY
                                  POP %DR_THRESHOLD
                                  POP %DR_RETURN
                
                              @@START_LOOP:
                                  NRG %DR_ENERGY
                                  GTR %DR_ENERGY %DR_THRESHOLD
                                  JUMP %DR_RETURN
                
                                  # Scanne nur die 4 benachbarten Felder mit Vektoren der Länge 1.
                                  $SCAN_AND_PEEK_FIELD 1|0
                                  $SCAN_AND_PEEK_FIELD -1|0
                                  $SCAN_AND_PEEK_FIELD 0|1
                                  $SCAN_AND_PEEK_FIELD 0|-1
                
                                  # Bewege den DP zu einem freien Feld (Vektoren der Länge 1).
                                  $TRY_MOVE_AND_JUMP 1|0 @@START_LOOP
                                  $TRY_MOVE_AND_JUMP -1|0 @@START_LOOP
                                  $TRY_MOVE_AND_JUMP 0|1 @@START_LOOP
                                  $TRY_MOVE_AND_JUMP 0|-1 @@START_LOOP
                
                                  # Wenn alle 4 Richtungen blockiert sind, starte von vorne.
                                  JUMP @@START_LOOP
                              .ENDM
                
                              # --- Hauptprogramm ---
                              MAIN_PROGRAM:
                                  SETI %DR_TMP ENERGY:100
                                  PUSH %DR_TMP
                                  SETV %DR_TMP RETURN_POINT
                                  PUSH %DR_TMP
                
                                  SYNC
                                  $SEEK_ENERGY
                
                                  JUMP MAIN_PROGRAM
                
                              RETURN_POINT:
                                  NOP
                """;
    }
}