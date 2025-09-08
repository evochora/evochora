# main.s
# New Primordial Organism
# Main loop with reproduction and energy search (both mocks)
# STRUCTURE shell around organism

# ----------------------------
# Constants
# ----------------------------
.DEFINE REPRODUCTION_THRESHOLD DATA:25000

# ----------------------------
# STRUCTURE Shell (20x15)
# ----------------------------
.PLACE STRUCTURE:1 0..50|1
.PLACE STRUCTURE:1 0..50|31
.PLACE STRUCTURE:1 0|1..30
.PLACE STRUCTURE:1 50|1..30

# ----------------------------
# Entry & Init
# ----------------------------
.ORG 2|2
START:
  JMPI MAIN_LOOP

# ----------------------------
# Main loop
# ----------------------------
.ORG 2|3
MAIN_LOOP:
  NRG %DR0
  GTI %DR0 REPRODUCTION_THRESHOLD
  JMPI REPRODUCE
  JMPI ENERGY_SEARCH

# ----------------------------
# Reproduction (mock)
# ----------------------------
.ORG 2|4
REPRODUCE:
  NOP
  JMPI MAIN_LOOP

# ----------------------------
# Energy search (mock)
# ----------------------------
.ORG 2|5
ENERGY_SEARCH:
  NOP
  JMPI MAIN_LOOP
