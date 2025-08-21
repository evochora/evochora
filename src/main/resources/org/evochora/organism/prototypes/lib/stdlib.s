# =================================================================
# Standard-Bibliothek: stdlib.s
# Enthält allgemeine, atomare und wiederverwendbare Prozeduren.
# =================================================================

# --- Prozedur: stdlib.IS_PASSABLE ---
#
# Zweck:
#   Prüft, ob eine Zelle in einer gegebenen Richtung passierbar ist.
#   Eine Zelle ist passierbar, wenn sie leer ist ODER dem Organismus
#   selbst gehört.
#
# Signatur:
#   .PROC IS_PASSABLE EXPORT WITH DIRECTION_VEC, SUCCESS_FLAG
#   - Eingabe:
#       DIRECTION_VEC: Der Vektor zur Zielzelle.
#       SUCCESS_FLAG:  Ein Register, das das Ergebnis (1 oder 0) aufnimmt.
#   - Ausgabe:
#       SUCCESS_FLAG: DATA:1, wenn passierbar, sonst DATA:0.
#
.PROC IS_PASSABLE EXPORT WITH DIRECTION_VEC, SUCCESS_FLAG
    # Wir leihen uns ein Prozedur-Register für temporäre Daten.
    .PREG %TEMP_STORAGE 0

    # IFMR (If Mine Register) ist eine spezielle Instruktion.
    # Sie prüft, ob die Zelle bei DP + Vektor uns gehört.
    # Wenn ja, wird die nächste Anweisung ausgeführt. Wenn nein, wird sie übersprungen.
    IFMR DIRECTION_VEC
        JMPI IT_IS_PASSABLE   # Gehört uns -> also passierbar.

    # Wenn die Zelle nicht uns gehört, prüfen wir, ob sie leer ist.
    SCAN %TEMP_STORAGE DIRECTION_VEC
    # CODE:0 ist die Definition einer leeren Zelle (entspricht einem NOP).
    IFTI %TEMP_STORAGE CODE:0
        JMPI IT_IS_PASSABLE   # Ist leer -> also passierbar.

    # Weder unser Eigentum noch leer -> nicht passierbar.
    SETI SUCCESS_FLAG DATA:0
    RET

IT_IS_PASSABLE:
    # Setze das Erfolgs-Flag und beende die Prozedur.
    SETI SUCCESS_FLAG DATA:1
    RET
.ENDP
