# lib/reproduce.s
# Exportierte Reproduktion des Organimus
.SCOPE CREPRODUCE
.INCLUDE "lib/state.s"

# ----------------------------
# Constants
# ----------------------------
.DEFINE REPRODUCTION_PAUSE_THRESHOLD DATA:5000

.PROC CONTINUE EXPORT

  .PREG %PHASE  %PR0     # aktuelle Repro-Phase (Platzhalter)
  .PREG %STEP   %PR1     # Schritt/Offset (Platzhalter)
  .PREG %TMP    %PR2     # Scratch (Platzhalter)
  .PREG %ER_TMP %PR3     # aktueller Energy-Wert für den Threshold-Check

  .ORG 0|0
  JMPI PROC_START

  # 3 DATA-Felder in der Zeile über PROC_START (x=0..2, y=-1 relativ zu PROC_START)
  .PLACE DATA:0  0|1     # PHASE
  .PLACE DATA:0  1|1     # STEP
  .PLACE DATA:0  2|1     # RESERVED

  .ORG 0|2
    PROC_START:
      # am Anfang: "in einem Rutsch" laden
      STATIC3_LOAD  %PHASE %STEP %TMP
      JMPI REPRO_LOOP

    .ORG 0|3
    REPRO_LOOP:

      NRG %ER_TMP                                   # ER in %ER_TMP lesen (Cost 1). :contentReference[oaicite:3]{index=3}
      LTI %ER_TMP REPRODUCTION_PAUSE_THRESHOLD      # wenn ER < Threshold ...
      JMPI SAVE_AND_RET                             # ... dann State speichern & zurück. :contentReference[oaicite:4]{index=4}

    SAVE_AND_RET:
      # vor RET: "in einem Rutsch" speichern
      STATIC3_STORE %PHASE %STEP %TMP
      RET
.ENDP
.ENDS