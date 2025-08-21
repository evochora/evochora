# =================================================================
# 2D Standard-Bibliothek: stdlib_2d.s
# Enthält Hilfsfunktionen, die spezifisch für 2D-Welten sind.
# =================================================================

# --- Prozedur: stdlib_2d.TURN_RIGHT ---
#
# Zweck:
#   Rotiert einen 2D-Einheitsvektor um 90° im Uhrzeigersinn.
#   Diese Version ist optimiert für minimalen Code-Footprint.
#
# Signatur:
#   .PROC TURN_RIGHT EXPORT WITH VECTOR_IO
#   - Eingabe/Ausgabe:
#       VECTOR_IO: Das Register mit dem Vektor. Wird direkt überschrieben.
#
.PROC TURN_RIGHT EXPORT WITH VECTOR_IO
    # Wir nutzen ein internes Register. Kein zusätzlicher Parameter nötig.
    .PREG %TEMP 0

    # Fall 1: Oben (0|-1) -> Rechts (1|0)
    SETV %TEMP 0|-1
    IFR VECTOR_IO %TEMP
        JMPI SET_RIGHT

    # Fall 2: Rechts (1|0) -> Unten (0|1)
    SETV %TEMP 1|0
    IFR VECTOR_IO %TEMP
        JMPI SET_DOWN

    # Fall 3: Unten (0|1) -> Links (-1|0)
    SETV %TEMP 0|1
    IFR VECTOR_IO %TEMP
        JMPI SET_LEFT

    # Fall 4 (Default): Links (-1|0) -> Oben (0|-1)
    # Dieser Fall benötigt keinen Vergleich, er wird ausgeführt,
    # wenn keiner der anderen zutrifft. Das spart Instruktionen.
    SETV VECTOR_IO 0|-1
    RET

SET_RIGHT:
    SETV VECTOR_IO 1|0
    RET
SET_DOWN:
    SETV VECTOR_IO 0|1
    RET
SET_LEFT:
    SETV VECTOR_IO -1|0
    RET
.ENDP