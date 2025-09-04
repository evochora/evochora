# Energy Decision Organism (ballistic + periodic turn, clean version)
# Finds energy quickly in a toroidal world without repeating same row/column.
# Structure: main loop -> choose reproduction or energy search (procedure).
# Reproduction is mocked with NOP. Energy search returns ONLY when energy found.

# ----------------------------
# Constants
# ----------------------------
.DEFINE REPRODUCTION_THRESHOLD DATA:15000   # if ER >= threshold -> reproduce

# Periodic turn lengths (small primes to break torus resonances)
.DEFINE K0 DATA:89
.DEFINE K1 DATA:97
.DEFINE K2 DATA:101
.DEFINE K3 DATA:103

# ----------------------------
# Register aliases
# ----------------------------
.REG %ER       %DR0   # current energy (NRG)
.REG %DIR      %DR1   # movement direction vector (x|y)
.REG %FWD_MASK %DR2   # cached forward direction as bitmask
.REG %KIDX     %DR3   # index 0..3 for K0..K3
.REG %KLEFT    %DR4   # countdown steps to periodic turn
.REG %T0       %DR5   # scratch
.REG %T1       %DR6   # scratch

# ----------------------------
# Entry & Init
# ----------------------------
.ORG 0|0
START:
  # initial direction = right, cache forward bitmask once
  SETV %DIR 1|0
  V2BR %FWD_MASK %DIR

  # periodic turn state
  SETI %KIDX  DATA:0
  SETI %KLEFT K0

  # into main loop
  JMPI MAIN_LOOP

# ----------------------------
# Main loop
# ----------------------------
.ORG 0|1
MAIN_LOOP:
  NRG %ER
  GTI %ER REPRODUCTION_THRESHOLD
  JMPI REPRODUCE
  JMPI ENERGY_SEARCH

# ----------------------------
# Reproduction (mock)
# ----------------------------
.ORG 0|2
REPRODUCE:
  NOP
  JMPI MAIN_LOOP

# ----------------------------
# Energy search entry
# ----------------------------
.ORG 0|3
ENERGY_SEARCH:
  CALL ENERGY_SEARCH_PROC WITH %DIR %FWD_MASK %KIDX %KLEFT
  JMPI MAIN_LOOP

# ----------------------------
# Energy search procedure
# - Scans every step (no misses)
# - Ballistic movement to cover new cells fast
# - Periodic 90° turns with prime periods to avoid torus locks
# - Returns ONLY when energy was found and consumed
# ----------------------------
.ORG 0|4
.PROC ENERGY_SEARCH_PROC WITH DIR FWD_MASK KIDX KLEFT
  # procedure-local registers
  .PREG %MASK %PR0   # bitmask from SNTI/SPNR
  .PREG %VEC  %PR1   # temporary direction vector

# -------- Movement loop (scan every step) --------

MOVE_LOOP:
  # 1) scan neighborhood for energy
  SNTI %MASK ENERGY:0
  IFI %MASK DATA:0
    JMPI DO_MOVE
  RBIR %VEC %MASK
  B2VR %VEC %VEC
  PEEK %MASK %VEC
  RET

.ORG 0|5
DO_MOVE:
  # 2) forward passable?
  SPNR %MASK
  ANDR %MASK FWD_MASK
  IFI %MASK DATA:0
    JMPI PERIODIC_TURN   # blocked -> turn immediately

  # 3) step forward
  SEEK DIR

  # 4) periodic turn countdown
  SUBI KLEFT DATA:1
  IFI  KLEFT DATA:0
    JMPI PERIODIC_TURN

  JMPI MOVE_LOOP

# -------- Turn handling (periodic + on blockage) --------
.ORG 0|6
PERIODIC_TURN:
  # 90° clockwise
  SETI %T0 DATA:0
  SETI %T1 DATA:1
  RTRR DIR %T0 %T1
  V2BR FWD_MASK DIR

  # KIDX = (KIDX+1) mod 4
  ADDI KIDX DATA:1
  GTI  KIDX DATA:3
    JMPI PT_WRAP
  JMPI PT_LOAD
PT_WRAP:
  SETI KIDX DATA:0

.ORG 0|7
PT_LOAD:
  # choose next K and reload countdown
  IFI KIDX DATA:0
    JMPI PT_K0
  IFI KIDX DATA:1
    JMPI PT_K1
  IFI KIDX DATA:2
    JMPI PT_K2
  SETI KLEFT K3
  JMPI MOVE_LOOP
PT_K0:
  SETI KLEFT K0
  JMPI MOVE_LOOP
PT_K1:
  SETI KLEFT K1
  JMPI MOVE_LOOP
PT_K2:
  SETI KLEFT K2
  JMPI MOVE_LOOP
.ENDP
