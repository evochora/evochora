# primordial3.s
# Energy Decision Organism (clean main):
# main loop -> reproduce (NOP) or energy search (library proc).
# Nach Energy-Fund kehrt die Proc per RET zurück; dann zurück in MAIN_LOOP.

# ----------------------------
# Library einbinden & Abhängigkeit deklarieren
# ----------------------------
.ORG 0|4
.INCLUDE "lib/behavior4.s"
.REQUIRE "lib/behavior4.s" AS BEHAV

# ----------------------------
# Konstanten
# ----------------------------
.DEFINE REPRODUCTION_THRESHOLD DATA:25000

# ----------------------------
# Register-Aliase (nur Main-State)
# ----------------------------
.REG %ER       %DR0   # current energy
.REG %DIR      %DR1   # movement direction vector (x|y)
.REG %FWD_MASK %DR2   # cached forward direction as bitmask
.REG %KIDX     %DR3   # index 0..3 for K-periods
.REG %KLEFT    %DR4   # countdown to periodic turn

#-----------------------------
# Shell
#-----------------------------
.PLACE STRUCTURE:1 -1..50|-1
.PLACE STRUCTURE:1 -1..50|25
.PLACE STRUCTURE:1 -1|0..24
.PLACE STRUCTURE:1 50|0..24

# ----------------------------
# Entry & Init
# ----------------------------
.ORG 0|0
START:
  # Initialrichtung = rechts; Vorwärtsmaske einmalig cachen
  SETV %DIR 1|0
  V2BR %FWD_MASK %DIR

  # Periodischer Turn-State initial
  SETI %KIDX  DATA:0
  SETI %KLEFT DATA:89   # entspricht K0 in der Lib

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
  # Ruft die exportierte Bibliotheks-Prozedur auf.
  # Die Proc greift nur auf diese vier Register zu und verändert sie in-place.
  CALL BEHAV.ENERGY_SEARCH_PROC WITH %DIR %FWD_MASK %KIDX %KLEFT
  JMPI MAIN_LOOP
