# =================================================================
# Taktik-Bibliothek: tactics.s
# Enthält kurzfristige, zielgerichtete Aktionen.
# =================================================================

.REQUIRE "stdlib.s" AS STDLIB
.REQUIRE "stdlib_2d.s" AS STDLIB_2D

# --- Taktik: HARVEST_CELL ---
#
# Zweck:
#   Prüft eine Zelle in einer gegebenen Richtung. Wenn sie Energie
#   enthält, wird sie mit PEEK geerntet.
#
# Signatur:
#   .PROC HARVEST_CELL EXPORT WITH DIRECTION_VEC, RESULT_REG
#   - Eingabe:
#       DIRECTION_VEC: Ein Vektor (z.B. 1|0), der auf die Zielzelle zeigt.
#       RESULT_REG:    Ein Register, das das Ergebnis aufnimmt.
#   - Ausgabe:
#       RESULT_REG: Enthält die geerntete Energie oder CODE:0 (leer),
#                   wenn nichts gefunden wurde.
#
.PROC HARVEST_CELL EXPORT WITH DIRECTION_VEC, RESULT_REG
    # Wir leihen uns ein Prozedur-Register (%PR0), um das Ergebnis
    # des Scans temporär zu speichern.
    .PREG %TEMP_STORAGE 0

    # 1. Zuerst nur schauen, was da ist, ohne etwas zu verändern.
    SCAN %TEMP_STORAGE DIRECTION_VEC

    # 2. Prüfen, ob der Typ des Moleküls "ENERGY" ist.
    #    IFTI überspringt die nächste Anweisung, wenn die Typen NICHT übereinstimmen.
    IFTI %TEMP_STORAGE ENERGY:0
        JMPI IS_ENERGY      # Ja, es ist Energie. Springe zum Ernten.

    # 3. Wenn es keine Energie war, setze das Ergebnis auf 0 und beende die Prozedur.
    SETI RESULT_REG CODE:0
    RET

IS_ENERGY:
    # 4. Es ist Energie! Nimm sie mit PEEK auf.
    #    PEEK konsumiert die Zelle und fügt die Energie dem Organismus hinzu.
    PEEK RESULT_REG DIRECTION_VEC
    RET
.ENDP


# --- Taktik: STEP_RANDOMLY ---
#
# Zweck:
#   Findet eine zufällige, passierbare Nachbarzelle und bewegt sich.
#   Diese Version ist für minimalen Code-Footprint und Effizienz optimiert.
#   Sie verzichtet auf den Stack und die "Nicht-zurück-gehen"-Logik.
#
# Signatur:
#   .PROC STEP_RANDOMLY EXPORT WITH NEW_DIRECTION_OUT
#
.PROC STEP_RANDOMLY EXPORT WITH NEW_DIRECTION_OUT
    .PREG %DIRECTION_TO_TEST 0 # Vektor für die aktuelle Prüfung
    .PREG %IS_PASSABLE 1       # Flag von IS_PASSABLE
    .PREG %VALID_MOVES_COUNT 2 # Zähler für gefundene, gültige Züge
    .PREG %RANDOM_CHOICE 3     # Register für die Zufallszahl
    .PREG %LOOP_COUNTER 4      # Schleifenzähler

    # --- Phase 1: Prüfe alle Richtungen und wähle dabei zufällig aus ---
    SETI %VALID_MOVES_COUNT DATA:0
    SETV %DIRECTION_TO_TEST 1|0      # Starte mit der Richtung "Rechts"
    SETI %LOOP_COUNTER DATA:4

CHECK_DIRECTIONS_LOOP:
    # Ist die aktuelle Richtung passierbar?
    CALL STDLIB.IS_PASSABLE WITH %DIRECTION_TO_TEST, %IS_PASSABLE
    IFI %IS_PASSABLE DATA:0
        JMPI TRY_NEXT_DIRECTION      # Wenn nicht, überspringe den Rest und drehe weiter.

    # Es ist eine gültige Richtung!
    ADDI %VALID_MOVES_COUNT DATA:1

    # Der Trick: Die k-te gefundene Option wird mit einer Wahrscheinlichkeit
    # von 1/k zur neuen "besten" Option.
    SETR %RANDOM_CHOICE %VALID_MOVES_COUNT
    RAND %RANDOM_CHOICE

    # RAND %k gibt eine Zahl von 0 bis k-1 zurück. Wenn das Ergebnis 0 ist
    # (was mit 1/k Wahrscheinlichkeit passiert), überschreiben wir unsere Wahl.
    IFI %RANDOM_CHOICE DATA:0
        SETR NEW_DIRECTION_OUT %DIRECTION_TO_TEST # Neue beste Richtung merken

TRY_NEXT_DIRECTION:
    # Drehe zur nächsten Richtung für die nächste Iteration.
    CALL STDLIB_2D.TURN_RIGHT WITH %DIRECTION_TO_TEST

    SUBI %LOOP_COUNTER DATA:1
    GTI %LOOP_COUNTER DATA:0
        JMPI CHECK_DIRECTIONS_LOOP

    # --- Phase 2: Führe die Bewegung aus (wenn eine Richtung gefunden wurde) ---
    IFI %VALID_MOVES_COUNT DATA:0
        RET # Keine gültige Richtung gefunden, also nichts tun.

    # Bewege dich in die zuletzt ausgewählte "beste" Richtung.
    SEEK NEW_DIRECTION_OUT
    SYNC
    RET
.ENDP