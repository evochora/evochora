# =================================================================
# Hauptprogramm: main.s (Flexibel)
# =================================================================

.DEFINE ENERGY_REPRODUCTION_THRESHOLD DATA:5000

# --- Register für beide Strategien ---
.REG %CURRENT_ENERGY %DR0
.REG %TEMP_VEC %DR1

# --- Register NUR für den Spiral-Walk ---
.REG %SPIRAL_DIRECTION %DR5
.REG %SPIRAL_STEPS %DR6
.REG %SPIRAL_SEGMENT_LEN %DR7
.REG %SPIRAL_TURN_COUNT %DR4

# --- Register NUR für den Random-Walk ---
.REG %RANDOM_LAST_DIR %DR5 # Kann dasselbe Register wie %SPIRAL_DIRECTION sein

.REQUIRE "lib/behaviors.s" AS BEHAVIORS

.ORG 0|0

# --- Initialisierung ---
# Initialisiere die Zustandsregister für BEIDE Strategien.
# So kann man wechseln, ohne den Initialisierungscode zu ändern.
SETV %SPIRAL_DIRECTION 1|0
SETI %SPIRAL_STEPS DATA:0
SETI %SPIRAL_SEGMENT_LEN DATA:1
SETI %SPIRAL_TURN_COUNT DATA:0

SETV %RANDOM_LAST_DIR 0|0


MAIN_LOOP:
    NRG %CURRENT_ENERGY
    GTI %CURRENT_ENERGY ENERGY_REPRODUCTION_THRESHOLD
        JMPI START_REPRODUCTION

    # ==========================================================
    # HIER STRATEGIE AUSWÄHLEN:
    # Nur EINE der folgenden CALL-Anweisungen auskommentieren!
    # ==========================================================

    # --- Strategie 1: Systematischer Spiral-Walk ---
    CALL BEHAVIORS.SPIRAL_EXPLORE_AND_HARVEST WITH %SPIRAL_DIRECTION %SPIRAL_STEPS %SPIRAL_SEGMENT_LEN %SPIRAL_TURN_COUNT

    # --- Strategie 2: Agiler Random-Walk ---
    # CALL BEHAVIORS.RANDOM_EXPLORE_AND_HARVEST WITH %RANDOM_LAST_DIR %TEMP_VEC
    # SETR %RANDOM_LAST_DIR %TEMP_VEC

    JMPI MAIN_LOOP

START_REPRODUCTION:
    CALL BEHAVIORS.REPRODUCE
    JMPI MAIN_LOOP

# --- Physisches Einbinden der Module ---
# Wir benötigen keine stdlib mehr, da alle Operationen (Rotation, V2B)
# jetzt durch ISA-Befehle erledigt werden.
.ORG 0|1
.INCLUDE "lib/behaviors.s"