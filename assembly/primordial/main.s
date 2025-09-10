# main.s
# New Primordial Organism
# Main loop with reproduction and energy search (both mocks)
# STRUCTURE shell around organism


# ----------------------------
# Register-Aliase (nur Main-State)
# ----------------------------
.REG %ER       %DR0   # current energy

# ----------------------------
# Constants
# ----------------------------
.DEFINE REPRODUCTION_CONTINUE_THRESHOLD DATA:25000

# ----------------------------
# STRUCTURE Shell (50x30)
# ----------------------------
.PLACE STRUCTURE:1 0..70|1
.PLACE STRUCTURE:1 0..70|30
.PLACE STRUCTURE:1 0|2..29
.PLACE STRUCTURE:1 70|2..29

# ----------------------------
# Entry / Trampolin
# ----------------------------
.ORG 0|0
START:
  JMPI INIT

# ----------------------------
# Init
# ----------------------------
.ORG 1|2
INIT:
  JMPI MAIN_LOOP

# ----------------------------
# Main loop
# ----------------------------
.ORG 1|3
MAIN_LOOP:
  NRG %ER
  GTI %ER REPRODUCTION_CONTINUE_THRESHOLD
    CALL REPRODUCE.CONTINUE
  CALL ENERGY.HARVEST
  JMPI MAIN_LOOP

# ----------------------------
# Energy search
# ----------------------------
.ORG 1|4
.INCLUDE "lib/energy.s"
.REQUIRE "lib/energy.s" AS ENERGY
  

# ----------------------------
# Reproduction (mock)
# ----------------------------
.ORG 1|11
.INCLUDE "lib/reproduce.s"
.REQUIRE "lib/reproduce.s" AS REPRODUCE

# ----------------------------
# Safty jump back
# ----------------------------
JMPI MAIN_LOOP
