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


# --- Taktik: STEP_RANDOMLY (Optimierte Version) ---
#
# Zweck:
#   Findet alle passierbaren Nachbarn, meidet dabei die vorherige Zelle
#   (wenn möglich), wählt zufällig eine Richtung und bewegt sich.
#
# Signatur:
#   .PROC STEP_RANDOMLY EXPORT WITH LAST_DIRECTION_INV, NEW_DIRECTION
#   - Eingabe:
#       LAST_DIRECTION_INV: Der *invertierte* Vektor der letzten Bewegung.
#                           (z.B. wenn wir von links kamen [1|0], ist dies [-1|0]).
#   - Ausgabe:
#       NEW_DIRECTION:      Das Register wird mit der neuen Bewegungsrichtung überschrieben.
#
.PROC STEP_RANDOMLY EXPORT WITH LAST_DIRECTION_INV, NEW_DIRECTION
    .PREG %DIRECTION_TO_TEST 0 # %PR0
    .PREG %IS_PASSABLE 1       # %PR1
    .PREG %VALID_MOVES 2       # %PR2
    .PREG %RANDOM_CHOICE 3     # %PR3
    .PREG %TEMP_COUNTER 4      # %PR4

    # --- Phase 1: Finde passierbare Nachbarn (jetzt in einer Schleife) ---
    SETI %VALID_MOVES DATA:0
    SETI %TEMP_COUNTER DATA:4   # Wir haben 4 Richtungen zu prüfen

CHECK_NEXT_DIRECTION:
    # Lade die nächste Richtung basierend auf dem Zähler.
    # Die Richtungen sind direkt unter der Prozedur als Daten gespeichert.
    SETV %DIRECTION_TO_TEST DIR_TABLE
    ADDR %DIRECTION_TO_TEST %TEMP_COUNTER # Berechne die Adresse des Vektors

    CALL STDLIB.IS_PASSABLE WITH %DIRECTION_TO_TEST, %IS_PASSABLE
    IFR %IS_PASSABLE DATA:1
        PUSH %DIRECTION_TO_TEST # Gültige Richtung auf den Stack
        ADDI %VALID_MOVES DATA:1

    SUBI %TEMP_COUNTER DATA:1
    GTI %TEMP_COUNTER DATA:0
        JMPI CHECK_NEXT_DIRECTION

    # --- Phase 2: "Nicht zurückgehen"-Filter anwenden ---
    IFI %VALID_MOVES DATA:0
        RET # Keine Bewegung möglich

    # Nur filtern, wenn es mehr als eine Option gibt.
    GTI %VALID_MOVES DATA:1
        JMPI APPLY_FILTER
    JMPI CHOOSE_DIRECTION # Ansonsten direkt zur Auswahl springen

APPLY_FILTER:
    # Vergleiche jede Richtung auf dem Stack mit der "verbotenen" Richtung.
    # Wir bauen einen neuen, gefilterten Stack auf.
    SETI %TEMP_COUNTER %VALID_MOVES
    FILTER_LOOP:
        POP %DIRECTION_TO_TEST
        IFR %DIRECTION_TO_TEST LAST_DIRECTION_INV
            SUBI %VALID_MOVES DATA:1 # Diese Richtung verwerfen und Zähler reduzieren
        JMPI CONTINUE_FILTER_LOOP
        # Wenn es nicht die verbotene Richtung ist, legen wir sie
        # auf den "neuen" Stack (der effektiv unter dem alten liegt).
        PUSH %DIRECTION_TO_TEST
    CONTINUE_FILTER_LOOP:
        SUBI %TEMP_COUNTER DATA:1
        GTI %TEMP_COUNTER DATA:0
            JMPI FILTER_LOOP

    # Wenn nach dem Filtern keine Optionen mehr übrig sind (z.B. in einer Sackgasse),
    # müssen wir die ungefilterten Optionen wiederherstellen.
    # (Diese Logik ist komplex und wird hier für den Anfang weggelassen).

CHOOSE_DIRECTION:
    # --- Phase 3 & 4: Wähle und bewege ---
    # (Diese Logik ist identisch zur vorherigen Version, aber nutzt den
    # möglicherweise reduzierten %VALID_MOVES Zähler)
    SETR %RANDOM_CHOICE %VALID_MOVES
    RAND %RANDOM_CHOICE

    SUBI %VALID_MOVES %RANDOM_CHOICE
    SUBI %VALID_MOVES DATA:1
    # ... (POP_LOOP, MOVE_NOW, CLEANUP_LOOP wie zuvor) ...
    POP NEW_DIRECTION # Die gewählte Richtung in den Ausgabeparameter schreiben
    SEEK NEW_DIRECTION
    SYNC
    # ... (Restlicher Cleanup) ...
    RET

# Datentabelle für die Schleife (direkt im Code platziert für Effizienz)
DIR_TABLE:
    .PLACE DATA:1  0|1
    .PLACE DATA:2 -1|0
    .PLACE DATA:3  0|-1
    .PLACE DATA:4  1|0
.ENDP