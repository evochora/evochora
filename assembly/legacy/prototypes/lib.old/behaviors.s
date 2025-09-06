# =================================================================
# Verhaltens-Bibliothek: behaviors.s (Multi-Strategie)
# =================================================================
# Enthält verschiedene, in sich geschlossene Prozeduren für
# das Überlebens- und Explorationsverhalten des Organismus.
# =================================================================

# --- Verhalten 1: SPIRAL_EXPLORE_AND_HARVEST ---
#
# Zweck:
#   Systematische Erkundung der Umgebung in einer wachsenden Spirale.
#   Sehr effektiv, um neues Territorium zu erschließen.
#
.PROC SPIRAL_EXPLORE_AND_HARVEST EXPORT WITH DIRECTION_IO STEPS_TAKEN_IO SEGMENT_LEN_IO TURN_COUNTER_IO
    # --- Prozedur-interne Register ---
    .PREG %ENERGY_MASK %PR0
    .PREG %PASSABLE_MASK %PR1
    .PREG %CURRENT_CHOICE %PR2
    .PREG %TMP_DIRECTION %PR3
    .PREG %SCAN_RESULT %PR4
    .PREG %AXIS1 %PR5
    .PREG %AXIS2 %PR6
    .PREG %FORWARD_MASK %PR7

    # --- Phase 1: Umgebung abernten ---
    SNTI %ENERGY_MASK ENERGY:0
HARVEST_LOOP_SPIRAL:
    IFI %ENERGY_MASK DATA:0
        JMPI START_MOVEMENT_SPIRAL
    RBIR %CURRENT_CHOICE %ENERGY_MASK
    B2VR %TMP_DIRECTION %CURRENT_CHOICE
    SCAN %SCAN_RESULT %TMP_DIRECTION
    GTI %SCAN_RESULT DATA:0
        PEEK %SCAN_RESULT %TMP_DIRECTION
    XORR %ENERGY_MASK %CURRENT_CHOICE
    JMPI HARVEST_LOOP_SPIRAL
.ORG 0|1
START_MOVEMENT_SPIRAL:
    # --- Phase 2: Spiral-Bewegung mit Hinderniserkennung ---
    IFR STEPS_TAKEN_IO SEGMENT_LEN_IO
        JMPI TURN_AND_RESET
    SPNR %PASSABLE_MASK
    V2BR %FORWARD_MASK DIRECTION_IO
    ANDR %FORWARD_MASK %PASSABLE_MASK
    IFI %FORWARD_MASK DATA:0
        JMPI TURN_AND_RESET
    JMPI MOVE_FORWARD
.ORG 0|2
TURN_AND_RESET:
    SETI %AXIS1 DATA:0
    SETI %AXIS2 DATA:1
    RTRR DIRECTION_IO %AXIS1 %AXIS2
    SETI STEPS_TAKEN_IO DATA:0
    ADDI TURN_COUNTER_IO DATA:1
    IFI TURN_COUNTER_IO DATA:2
        ADDI SEGMENT_LEN_IO DATA:1
        SETI TURN_COUNTER_IO DATA:0
.ORG 0|3
MOVE_FORWARD:
    SEEK DIRECTION_IO
    ADDI STEPS_TAKEN_IO DATA:1
    RET
.ENDP

.ORG 0|5
# --- Verhalten 2: RANDOM_EXPLORE_AND_HARVEST ---
#
# Zweck:
#   Agile, unvorhersehbare Bewegung. Sehr gut, um aus lokalen
#   Sackgassen auszubrechen oder komplexen Mustern zu folgen.
#
.PROC RANDOM_EXPLORE_AND_HARVEST EXPORT WITH AVOID_DIRECTION_VEC MOVE_DIRECTION_OUT
    .PREG %ENERGY_MASK %PR0
    .PREG %PASSABLE_MASK %PR1
    .PREG %CURRENT_CHOICE %PR2
    .PREG %TMP_DIRECTION %PR3
    .PREG %SCAN_RESULT %PR4
    .PREG %AVOID_MASK %PR5

    # --- Phase 1: Umgebung abernten ---
    SNTI %ENERGY_MASK ENERGY:0
HARVEST_LOOP_RANDOM:
    IFI %ENERGY_MASK DATA:0
        JMPI START_MOVEMENT_RANDOM
    RBIR %CURRENT_CHOICE %ENERGY_MASK
    B2VR %TMP_DIRECTION %CURRENT_CHOICE
    SCAN %SCAN_RESULT %TMP_DIRECTION
    GTI %SCAN_RESULT DATA:0
        PEEK %SCAN_RESULT %TMP_DIRECTION
    XORR %ENERGY_MASK %CURRENT_CHOICE
    JMPI HARVEST_LOOP_RANDOM
.ORG 0|6
START_MOVEMENT_RANDOM:
    # --- Phase 2: Random-Walk mit "Nicht-zurückgehen"-Logik ---
    SPNR %PASSABLE_MASK
    V2BR %AVOID_MASK AVOID_DIRECTION_VEC
    NOT %AVOID_MASK
    ANDR %PASSABLE_MASK %AVOID_MASK
    IFI %PASSABLE_MASK DATA:0
        SPNR %PASSABLE_MASK
    RBIR %CURRENT_CHOICE %PASSABLE_MASK
    IFI %CURRENT_CHOICE DATA:0
        JMPI NO_MOVE
    B2VR MOVE_DIRECTION_OUT %CURRENT_CHOICE
    SEEK MOVE_DIRECTION_OUT
NO_MOVE:
    RET
.ENDP


# --- Verhalten: Reproduktion (Mock) ---
.PROC REPRODUCE EXPORT
    NOP
    RET
.ENDP