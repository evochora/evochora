# =================================================================
# Taktik-Bibliothek: tactics.s
# Enthält kurzfristige, zielgerichtete Aktionen.
# =================================================================

.REQUIRE "lib/stdlib.s" AS STDLIB
.REQUIRE "lib/stdlib_2d.s" AS STDLIB_2D

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


# --- Taktik 2: STEP_RANDOMLY (Bitmasken-Version) ---
#
# Zweck:
#   Implementiert die kompakte Filter-Logik mit den neuen Bit-Befehlen.
#
.PROC STEP_RANDOMLY EXPORT WITH LAST_DIRECTION_INV NEW_DIRECTION_OUT
    .PREG %DIR_TEST 0       # Zu prüfende Richtung
    .PREG %FLAG 1           # Flag für Passierbarkeit
    .PREG %PASSABLE_MASK 2  # Bitmaske der passierbaren Richtungen
    .PREG %FILTERED_MASK 3  # Gefilterte Maske
    .PREG %COUNT 4          # Zähler für Optionen

    # --- Phase 1: Baue eine Bitmaske aller passierbaren Richtungen ---
    SETI %PASSABLE_MASK DATA:0
    SETV %DIR_TEST 1|0      # Start: Rechts (entspricht Bit 0)
    SETI %FLAG DATA:4       # Schleifenzähler

CHECK_LOOP:
    # Bit für die aktuelle Richtung nach links schieben (Rechts=1, Unten=2, Links=4, Oben=8)
    SHLI %FLAG DATA:1
    CALL STDLIB.IS_PASSABLE WITH %DIR_TEST %FLAG
    IFI %FLAG DATA:1
        ORR %PASSABLE_MASK %FLAG # Bit in Maske setzen
    CALL STDLIB_2D.TURN_RIGHT WITH %DIR_TEST
    GTI %FLAG DATA:0
        JMPI CHECK_LOOP

    # --- Phase 2: "Nicht zurückgehen"-Filter anwenden ---
    PCNR %COUNT %PASSABLE_MASK # Zähle, wie viele Optionen es gibt
    IFI %COUNT DATA:0
        RET # Keine Bewegung möglich

    SETR %FILTERED_MASK %PASSABLE_MASK # Kopiere für die Filterung
    GTI %COUNT DATA:1
        JMPI APPLY_FILTER

APPLY_FILTER:
    # Konvertiere "verbotene" Richtung in eine Bitmaske
    CALL STDLIB_2D.BITMASK_TO_VECTOR WITH LAST_DIRECTION_INV %FLAG # Missbrauche %FLAG temporär
    NOT %FLAG
    ANDR %FILTERED_MASK %FLAG # Entferne das "zurück"-Bit

    PCNR %COUNT %FILTERED_MASK
    # Sackgasse? Wenn nach dem Filtern nichts übrig ist, nimm die originale Maske.
    IFI %COUNT DATA:0
        SETR %FILTERED_MASK %PASSABLE_MASK
        PCNR %COUNT %FILTERED_MASK

    # --- Phase 3: Wähle zufällig und bewege ---
    RAND %COUNT                 # Wähle einen Index von 0 bis (COUNT-1)
    ADDI %COUNT DATA:1          # BSN ist 1-basiert, also +1

    # Finde das N-te gesetzte Bit. Wir nutzen die Stack-Version BSNS.
    PUSH %FILTERED_MASK
    PUSH %COUNT
    BSNS
    POP %FINAL_MASK             # Ergebnis ist eine Maske mit nur einem gesetzten Bit

    # Konvertiere die finale Bitmaske zurück in einen Vektor
    CALL STDLIB_2D.BITMASK_TO_VECTOR WITH %FINAL_MASK NEW_DIRECTION_OUT

    SEEK NEW_DIRECTION_OUT
    SYNC
    RET
.ENDP