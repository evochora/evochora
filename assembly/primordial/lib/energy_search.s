# lib/energy_search.s
# Exportierte Energiesuche: ballistisch + periodischer Turn mit Primperioden.
# State-Management mit Burst-Load/Store Pattern

# Row-above Burst-Load mit integriertem ENTER/EXIT
.MACRO STATIC3_LOAD_BURST_ALL D0 D1 D2
  DPLS            # alten DP sichern
  SYNC            # DP := IP (an PROC_START binden)

  SEKI -1|0
  SCNI D0 0|-1    # Slot 0 (x=0, y=-1)
  SEKI 1|0
  SCNI D1 0|-1    # Slot 1 (x=1)
  SEKI 1|0
  SCNI D2 0|-1    # Slot 2 (x=2)
  SEKI -1|0
  SEKI -1|0       # DP zurück nach x=0

  DPLS            # DP auf LS sichern
  SWPL            # Alte DP position nach unten auf den stack swappen
  SKLS            # alten DP wiederherstellen
.ENDM

# Row-above Burst-Store mit integriertem ENTER/EXIT
.MACRO STATIC3_STORE_BURST_ALL S0 S1 S2
  DPLS            # Caller DP sichern
  SWPL            # PROC_START position im LS nach unten swappen
  SKLS            # Active DP nach PROC_START holen
  #SEKI -1|0
  POKI S0 0|-1
  SEKI 1|0
  POKI S1 0|-1
  SEKI 1|0
  POKI S2 0|-1
  SEKI -1|0
  SEKI -1|0

  SKLS            # Caller DP wiederherstellen
.ENDM

.PROC ENERGY_SEARCH_PROC EXPORT
  .PREG %FWD_MASK %PR0   # cached forward direction as bitmask
  .PREG %KIDX     %PR1   # index 0..3 for K-periods
  .PREG %KLEFT    %PR2   # countdown to periodic turn
  .PREG %DIR      %PR3   # movement direction vector (neu bei jedem Aufruf)
  .PREG %MASK     %PR4   # Bitmaske von SNTI/SPNR
  .PREG %VEC      %PR5   # temporärer Richtungsvektor

  .ORG 0|0
  JMPI PROC_START

  # 3 DATA-Felder in der Zeile über PROC_START (x=0..2, y=-1 relativ zu PROC_START)
  .PLACE DATA:1  0|1    # FWD_MASK: rechts (Bitmaske)
  .PLACE DATA:0  1|1    # KIDX: Index 0
  .PLACE DATA:89 2|1    # KLEFT: K0-Periode

  .ORG 0|2
PROC_START:
  # am Anfang: "in einem Rutsch" laden
  STATIC3_LOAD_BURST_ALL %FWD_MASK %KIDX %KLEFT
  JMPI RANDOM_DIR
  
.ORG 0|3 
RANDOM_DIR:
  # Zufällige Richtung wählen (NACH dem State-Load!)
  SPNR %MASK                       # %MASK := passierbare Richtungen (Bitmaske)
  RBIR %DIR %MASK
  B2VR %DIR %DIR
  V2BR %FWD_MASK %DIR

MOVE_LOOP:
  # 1) Umgebung scannen – wenn Energie da, sofort nehmen und zurück
  SNTI %MASK ENERGY:0
  IFI %MASK DATA:0
    JMPI DO_MOVE
  RBIR %VEC %MASK
  B2VR %VEC %VEC
  SCAN %MASK %VEC
  IFI %MASK ENERGY:0
    JMPI DO_MOVE
  PEEK %MASK %VEC
  # vor RET: "in einem Rutsch" speichern
  STATIC3_STORE_BURST_ALL %FWD_MASK %KIDX %KLEFT
  RET

.ORG 0|4
DO_MOVE:
  # 2) Vorwärts passierbar?
  SPNR %MASK
  ANDR %MASK %FWD_MASK
  IFI %MASK DATA:0
    JMPI PERIODIC_TURN

  # 3) Schritt vorwärts
  SEEK %DIR

  # 4) Periodischer Turn-Countdown
  SUBI %KLEFT DATA:1
  IFI  %KLEFT DATA:0
    JMPI PERIODIC_TURN

  JMPI MOVE_LOOP

.ORG 0|5
PERIODIC_TURN:
  # 90° CW drehen (Achsen 0,1) ohne externe Temp-Register
  RTRI %DIR DATA:0 DATA:1
  V2BR %FWD_MASK %DIR

  # KIDX = (KIDX+1) mod 4
  ADDI %KIDX DATA:1
  GTI  %KIDX DATA:3
    JMPI PT_WRAP
  JMPI PT_LOAD
PT_WRAP:
  SETI %KIDX DATA:0

PT_LOAD:
  # Nächste Prim-Periode auswählen und KLEFT laden
  IFI %KIDX DATA:0
    JMPI PT_K0
  IFI %KIDX DATA:1
    JMPI PT_K1
  IFI %KIDX DATA:2
    JMPI PT_K2
  # else K3
  SETI %KLEFT DATA:103
  JMPI MOVE_LOOP

.ORG 0|6
PT_K0:
  SETI %KLEFT DATA:89
  JMPI MOVE_LOOP
PT_K1:
  SETI %KLEFT DATA:97
  JMPI MOVE_LOOP
PT_K2:
  SETI %KLEFT DATA:101
  JMPI MOVE_LOOP
.ENDP