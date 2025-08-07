# =================================================================
# Standard-Bibliothek: stdlib.s
# =================================================================

# --- Routine: stdlib.TURN_RIGHT ---
#
# Zweck:
#   Nimmt einen Einheitsvektor und dreht ihn um 90 Grad im Uhrzeigersinn.
#
# Optimierung V3:
#   - Benötigt nur 2 Register: Das Zielregister und ein temporäres Register.
#   - Verwendet JMPI für effiziente interne Sprünge zu lokalen Labels.
#   - Nutzt den Stack, um das temporäre Register zu sichern.
#
# Parameter:
#   REG_DV:  Das Register, das den zu drehenden Vektor enthält.
#            Das Ergebnis wird in dasselbe Register zurückgeschrieben.
#   REG_TMP: Ein temporäres Register, das intern verwendet wird.
#            Sein ursprünglicher Wert wird am Ende wiederhergestellt.
#
.ROUTINE TURN_RIGHT REG_DV REG_TMP

    # --- Vorbereitung ---
    # Sichert den ursprünglichen Wert von REG_TMP auf dem Stack.
    PUSH REG_TMP

    # --- Logik ---
    # Fall 1: RECHTS (1|0) -> RUNTER (0|1)
    SETV REG_TMP 1|0
    IFR REG_DV REG_TMP
    JMPI SET_DOWN

    # Fall 2: RUNTER (0|1) -> LINKS (-1|0)
    SETV REG_TMP 0|1
    IFR REG_DV REG_TMP
    JMPI SET_LEFT

    # Fall 3: LINKS (-1|0) -> HOCH (0|-1)
    SETV REG_TMP -1|0
    IFR REG_DV REG_TMP
    JMPI SET_UP

    # Fall 4: HOCH (0|-1) -> RECHTS (1|0) (Default-Fall)
    SETV REG_DV 1|0
    JMPI EXIT

    # --- Interne Zuweisungs-Labels ---
SET_DOWN:
    SETV REG_DV 0|1
    JMPI EXIT
SET_LEFT:
    SETV REG_DV -1|0
    JMPI EXIT
SET_UP:
    SETV REG_DV 0|-1
    # Kein JMPI nötig, da es direkt vor dem Exit-Label steht.

    # --- Aufräumen & Rücksprung ---
EXIT:
    POP REG_TMP     # Den ursprünglichen Wert von REG_TMP wiederherstellen.
    RET             # Kehrt zum ursprünglichen Aufrufer zurück.
.ENDR