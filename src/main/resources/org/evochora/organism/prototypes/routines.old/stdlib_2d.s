# =================================================================
# 2D Standard-Bibliothek: stdlib_2d.s
# Enthält Hilfsfunktionen, die spezifisch für 2D-Welten sind.
# =================================================================

# --- Prozedur: stdlib_2d.TURN_RIGHT ---
#
# Zweck:
#   Rotiert einen 2D-Einheitsvektor um 90° im Uhrzeigersinn unter
#   Verwendung der mathematischen Formel (x, y) -> (y, -x).
#   Diese Version nutzt die neuen Vektor-Instruktionen.
#
# Signatur:
#   .PROC TURN_RIGHT EXPORT WITH VECTOR_IO
#
.PROC TURN_RIGHT EXPORT WITH VECTOR_IO
    # Wir leihen uns interne Register für die Komponenten x und y.
    .PREG %X 0
    .PREG %Y 1

    # 1. Extrahiere die x- und y-Komponenten des Eingabevektors.
    #    VGTI %ZielRegister, %QuellVektor, INDEX
    VGTI %X VECTOR_IO DATA:0  # %X = VECTOR_IO.x
    VGTI %Y VECTOR_IO DATA:1  # %Y = VECTOR_IO.y

    # 2. Berechne -x.
    #    Wir multiplizieren x mit -1, um die Negation zu erhalten.
    MULI %X DATA:-1            # %X = %X * -1

    # 3. Baue den neuen Vektor (y, -x) zusammen.
    #    VBLD baut einen Vektor aus Werten vom Stack. Die Reihenfolge
    #    ist wichtig: Der Wert für die letzte Komponente (y) muss
    #    zuerst gepusht werden.
    PUSH %X                     # Lege -x auf den Stack
    PUSH %Y                     # Lege y auf den Stack
    VBLD VECTOR_IO              # Baut den Vektor aus [y, -x] und speichert ihn in VECTOR_IO

    RET
.ENDP

# --- PROZEDUR: stdlib_2d.BITMASK_TO_VECTOR ---
#
# Zweck:
#   Konvertiert eine Bitmaske, bei der nur ein Bit gesetzt ist, in den
#   entsprechenden 2D-Richtungsvektor.
#   Mapping: 0b0001 -> Rechts, 0b0010 -> Unten, 0b0100 -> Links, 0b1000 -> Oben
#
# Signatur:
#   .PROC BITMASK_TO_VECTOR EXPORT WITH MASK_IN, VECTOR_OUT
#
.PROC BITMASK_TO_VECTOR EXPORT WITH MASK_IN VECTOR_OUT
    # Nutzt IFI (If Immediate), um die Maske effizient zu prüfen.
    IFI MASK_IN DATA:0b0001
        JMPI IS_RIGHT
    IFI MASK_IN DATA:0b0010
        JMPI IS_DOWN
    IFI MASK_IN DATA:0b0100
        JMPI IS_LEFT
    # Default ist Oben (0b1000)
    SETV VECTOR_OUT 0|-1
    RET

IS_RIGHT:
    SETV VECTOR_OUT 1|0
    RET
IS_DOWN:
    SETV VECTOR_OUT 0|1
    RET
IS_LEFT:
    SETV VECTOR_OUT -1|0
    RET
.ENDP