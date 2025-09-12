# lib/energy.s
# Exportierte Energiesuche: ballistisch + periodischer Turn mit Primperioden.

.INCLUDE "lib/state.s"

.PROC HARVEST EXPORT

  .PREG %FWD_MASK %PR0   # cached forward direction as bitmask
  .PREG %KIDX     %PR1   # index 0..3 for K-periods
  .PREG %KLEFT    %PR2   # countdown to periodic turn
  .PREG %DIR      %PR3   # movement direction vector (neu bei jedem Aufruf)
  .PREG %MASK     %PR4   # Bitmaske von SNTI/SPNR
  .PREG %VEC      %PR5   # temporärer Richtungsvektor

  .ORG 0|0
  JMPI HARVEST_START

  # 3 DATA-Felder in der Zeile über HARVEST_START (x=0..2, y=-1 relativ zu HARVEST_START)
  .PLACE DATA:1  0|1    # FWD_MASK: rechts (Bitmaske)
  .PLACE DATA:0  1|1    # KIDX: Index 0
  .PLACE DATA:89 2|1    # KLEFT: K0-Periode

  .ORG 0|2
    HARVEST_START:
      # am Anfang: "in einem Rutsch" laden
      STATIC3_LOAD %FWD_MASK %KIDX %KLEFT
      JMPI HARVEST_RANDOM_DIR

    .ORG 0|3
    HARVEST_RANDOM_DIR:
      # Zufällige Richtung wählen (NACH dem State-Load!)
      SPNR %MASK                       # %MASK := passierbare Richtungen (Bitmaske)
      RBIR %DIR %MASK
      B2VR %DIR %DIR
      V2BR %FWD_MASK %DIR

    HARVEST_LOOP:
      # 1) Umgebung scannen – wenn Energie da, sofort nehmen und zurück
      SNTI %MASK ENERGY:0
      IFI %MASK DATA:0
        JMPI HARVEST_MOVE

      RBIR %VEC %MASK
      B2VR %VEC %VEC

      SCAN %MASK %VEC
      LETI %MASK ENERGY:0
        JMPI HARVEST_MOVE

      PEEK %MASK %VEC
      JMPI HARVEST_SAVE_AND_RETURN

    .ORG 0|4
    HARVEST_SAVE_AND_RETURN:
      # vor RET: "in einem Rutsch" speichern
      STATIC3_STORE %FWD_MASK %KIDX %KLEFT
      RET

    .ORG 0|5
    HARVEST_MOVE:
      # 2) Vorwärts passierbar?
      SPNR %MASK
      ANDR %MASK %FWD_MASK
      IFI %MASK DATA:0
        JMPI HARVEST_TURN

      # 3) Schritt vorwärts
      SEEK %DIR

      # 4) Periodischer Turn-Countdown
      SUBI %KLEFT DATA:1
      IFI  %KLEFT DATA:0
        JMPI HARVEST_TURN

      JMPI HARVEST_LOOP

    .ORG 0|6
    HARVEST_TURN:
      # 90° CW drehen (Achsen 0,1) ohne externe Temp-Register
      RTRI %DIR DATA:0 DATA:1
      V2BR %FWD_MASK %DIR

      # KIDX = (KIDX+1) mod 4
      ADDI %KIDX DATA:1
      GTI  %KIDX DATA:3
        JMPI HARVEST_PT_WRAP
      JMPI HARVEST_PT_LOAD
    HARVEST_PT_WRAP:
      SETI %KIDX DATA:0

    HARVEST_PT_LOAD:
      # Nächste Prim-Periode auswählen und KLEFT laden
      IFI %KIDX DATA:0
        JMPI HARVEST_PT_K0
      IFI %KIDX DATA:1
        JMPI HARVEST_PT_K1
      IFI %KIDX DATA:2
        JMPI HARVEST_PT_K2
      # else K3
      SETI %KLEFT DATA:103
      JMPI HARVEST_LOOP

    .ORG 0|7
    HARVEST_PT_K0:
      SETI %KLEFT DATA:89
      JMPI HARVEST_LOOP
    HARVEST_PT_K1:
      SETI %KLEFT DATA:97
      JMPI HARVEST_LOOP
    HARVEST_PT_K2:
      SETI %KLEFT DATA:101
      JMPI HARVEST_LOOP
.ENDP
