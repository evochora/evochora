# =================================================================
# Taktik-Bibliothek: tactics.s
#
# Enthält Routinen, die primitive Operationen aus stdlib.s zu
# einfachen, aber nützlichen Verhaltensmustern kombinieren.
# =================================================================

# --- Routine: tactics.SCAN_SURROUNDINGS ---
#
# Zweck:
#   Überprüft alle vier benachbarten Felder (relativ zum aktuellen DV)
#   auf ein Zielobjekt. Stoppt, sobald das erste Ziel gefunden wurde.
#
# Parameter:
#   REG_TARGET_TYPE: (Input) Register mit einem Beispiel-Symbol des gesuchten Typs
#                    (z.B. ein Register, das ENERGY:0 enthält).
#   REG_SUCCESS:     (Output) Wird auf DATA:1 (gefunden) oder DATA:0 (nicht) gesetzt.
#   REG_DIR_FOUND:   (Output) Wenn erfolgreich, enthält dieses Register den
#                    Vektor der Richtung, in der das Ziel gefunden wurde.
#   REG_TMP_DV:      (Internal) Temporäres Register für den rotierenden Suchvektor.
#   REG_TMP:         (Internal) Temporäres Register, das von den stdlib-Routinen benötigt wird.
#
.ROUTINE SCAN_SURROUNDINGS REG_TARGET_TYPE REG_SUCCESS REG_DIR_FOUND REG_TMP_DV REG_TMP

    # --- Vorbereitung ---
    POS REG_TMP_DV       # Hole die aktuelle IP-Position (als Platzhalter, da POS relativ ist)
    PUSH REG_TMP_DV      # Sichere den ursprünglichen Wert von REG_TMP_DV
    TURN REG_TMP_DV      # Lade den aktuellen DV des Organismus in REG_TMP_DV

    # Initialisiere Ergebnis-Register auf "nicht gefunden".
    SETI REG_SUCCESS DATA:0

    # Starte eine Schleife, die vier Mal durchläuft (für jede Himmelsrichtung).
    SETI REG_TMP DATA:4
SCAN_LOOP:
    # Taktik: Nutze die primitive CHECK_CELL Routine
    CALL stdlib.CHECK_CELL

    # Prüfe das Ergebnis von CHECK_CELL
    IFI REG_SUCCESS DATA:1
    JMPI FOUND_TARGET   # Wenn gefunden, springe aus der Schleife

    # Wenn nichts gefunden wurde, drehe den Suchvektor weiter
    CALL stdlib.TURN_RIGHT

    # Schleifenzähler dekrementieren und erneut versuchen
    SUBI REG_TMP DATA:1
    GTI REG_TMP DATA:0
    JMPI SCAN_LOOP

    # Wenn die Schleife durchläuft, ohne etwas zu finden, springe zum Ende.
    JMPI SCAN_EXIT

FOUND_TARGET:
    # Ziel wurde gefunden. Speichere die erfolgreiche Richtung.
    SETR REG_DIR_FOUND REG_TMP_DV

SCAN_EXIT:
    POP REG_TMP_DV     # Stelle den ursprünglichen Wert von REG_TMP_DV wieder her.
    RET
.ENDR


.INCLUDE stdlib.CHECK_CELL AS R_TEST_2 WITH ??? ???