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
.PROC HARVEST_CELL EXPORT WITH DIRECTION_VEC RESULT_REG
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


# --- Taktik 2: STEP_RANDOMLY (Final optimierte Version) ---
#
# Zweck:
#   Nutzt die erweiterte ISA, um in minimalen Ticks eine freie
#   Nachbarzelle zu finden und sich dorthin zu bewegen.
#
.PROC STEP_RANDOMLY EXPORT WITH NEW_DIRECTION_OUT
    .PREG %MASK   0   # Hält die Maske der passierbaren Richtungen
    .PREG %CHOICE 1   # Hält die Maske der zufälligen Auswahl (nur ein Bit)

    # Tick 1: Finde ALLE passierbaren Richtungen mit einem Befehl.
    SPNR %MASK

    # Tick 2: Wähle zufällig EINE der passierbaren Optionen aus.
    RBIR %CHOICE %MASK

    # Wenn keine Richtung frei war (%CHOICE ist 0), überspringe die Bewegung.
    IFI %CHOICE DATA:0
        JMPI NO_MOVE

    # Tick 3: Konvertiere das gewählte Bit direkt in einen Vektor.
    B2VR NEW_DIRECTION_OUT %CHOICE

    # Tick 4: Gehe dorthin.
    SEEK NEW_DIRECTION_OUT

NO_MOVE:
    RET
.ENDP