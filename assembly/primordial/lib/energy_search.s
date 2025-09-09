# lib/behavior.s
# Exportierte Energiesuche: ballistisch + periodischer Turn mit Primperioden.
# Keine externen Abhängigkeiten außer den übergebenen Parametern.

# Procedure: BEHAV.ENERGY_SEARCH
# Params (per .WITH):
#   DIR       : Bewegungsrichtungs-Vektor (wird in-place gedreht)
#   FWD_MASK  : gecachte Vorwärts-Bitmaske (wird nach jedem Turn aktualisiert)
#   KIDX      : Index 0..3 für K-Perioden (wird zyklisch erhöht)
#   KLEFT     : Countdown bis zum erzwungenen Turn (wird neu geladen)

.PROC ENERGY_SEARCH_PROC EXPORT WITH DIR FWD_MASK KIDX KLEFT
  # Lokale (procedure-saving) Register
  .PREG %MASK %PR0   # Bitmaske von SNTI/SPNR
  .PREG %VEC  %PR1   # temporärer Richtungsvektor


MOVE_LOOP:
  # 1) Umgebung scannen – wenn Energie da, sofort nehmen und zurück
  SNTI %MASK ENERGY:0
  IFI %MASK DATA:0
    JMPI DO_MOVE
  RBIR %VEC %MASK
  B2VR %VEC %VEC
  PEEK %MASK %VEC
  RET

.ORG 0|1
DO_MOVE:
  # 2) Vorwärts passierbar?
  SPNR %MASK
  ANDR %MASK FWD_MASK
  IFI %MASK DATA:0
    JMPI PERIODIC_TURN

  # 3) Schritt vorwärts
  SEEK DIR

  # 4) Periodischer Turn-Countdown
  SUBI KLEFT DATA:1
  IFI  KLEFT DATA:0
    JMPI PERIODIC_TURN

  JMPI MOVE_LOOP

.ORG 0|2
PERIODIC_TURN:
  # 90° CW drehen (Achsen 0,1) ohne externe Temp-Register
  RTRI DIR DATA:0 DATA:1
  V2BR FWD_MASK DIR

  # KIDX = (KIDX+1) mod 4
  ADDI KIDX DATA:1
  GTI  KIDX DATA:3
    JMPI PT_WRAP
  JMPI PT_LOAD
PT_WRAP:
  SETI KIDX DATA:0

PT_LOAD:
  # Nächste Prim-Periode auswählen und KLEFT laden
  IFI KIDX DATA:0
    JMPI PT_K0
  IFI KIDX DATA:1
    JMPI PT_K1
  IFI KIDX DATA:2
    JMPI PT_K2
  # else K3
  SETI KLEFT DATA:103
  JMPI MOVE_LOOP

.ORG 0|3
PT_K0:
  SETI KLEFT DATA:89
  JMPI MOVE_LOOP
PT_K1:
  SETI KLEFT DATA:97
  JMPI MOVE_LOOP
PT_K2:
  SETI KLEFT DATA:101
  JMPI MOVE_LOOP
.ENDP