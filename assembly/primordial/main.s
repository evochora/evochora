# main.s
# New Primordial Organism
# Main loop with reproduction and energy search (both mocks)
# STRUCTURE shell around organism


# ----------------------------
# Register-Aliase (nur Main-State)
# ----------------------------
.REG %ER %DR0   # current energy

# ----------------------------
# Constants
# ----------------------------
.DEFINE REPRODUCTION_CONTINUE_THRESHOLD DATA:10000

# ----------------------------
# STRUCTURE Shell (50x30)
# ----------------------------
.PLACE STRUCTURE:1 -1..49|-1
.PLACE STRUCTURE:1 -1..49|28
.PLACE STRUCTURE:1 -1|0..27
.PLACE STRUCTURE:1 49|0..27

# ----------------------------
# Init
# ----------------------------
.ORG 0|0
INIT:
  JMPI MAIN_LOOP

# ----------------------------
# Main loop
# ----------------------------
.ORG 0|1
MAIN_LOOP:
  NRG %ER
  GTI %ER REPRODUCTION_CONTINUE_THRESHOLD
    CALL REPRODUCE.CONTINUE REF %ER
  CALL ENERGY.HARVEST
  JMPI MAIN_LOOP

# ----------------------------
# Energy search
# ----------------------------
.ORG 0|2
.INCLUDE "lib/energy.s"
.REQUIRE "lib/energy.s" AS ENERGY
  

# ----------------------------
# Reproduction (mock)
# ----------------------------
.ORG 0|10
.INCLUDE "lib/reproduce.s"
.REQUIRE "lib/reproduce.s" AS REPRODUCE

# ----------------------------
# Safty jump back
# ----------------------------
JMPI MAIN_LOOP
