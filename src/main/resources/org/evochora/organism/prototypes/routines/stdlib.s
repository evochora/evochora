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

# --- Routine: stdlib.CHECK_CELL ---
#
# Zweck:
#   Prüft das Nachbarfeld in gegebener Richtung auf einen bestimmten Typ
#   und setzt ein Flag-Register auf 1 (gefunden) oder 0 (nicht gefunden).
#
# Optimierung V1:
#   Benötigt nur 3 Register: REG_DIR, REG_TYPE, REG_FLAG.
#
# Parameter:
#   REG_DIR : Register mit Einheits-Vektor zur Zielzelle.
#   REG_TYPE: Register mit Symboltyp für Vergleich (DATA:n).
#   REG_FLAG: Register zur Rückgabe des Ergebnis-Flags (DATA:0/1).
.ROUTINE CHECK_CELL REG_DIR REG_TYPE REG_FLAG
## --- Logik ---
#SCAN REG_FLAG REG_DIR            # Liest Typ der Zielzelle in REG_FLAG
#IFTR REG_FLAG REG_TYPE           # Vergleicht mit REG_TYPE
#JMPI SET_TRUE                    # Gegebenenfalls zu SET_TRUE springen
#SETI REG_FLAG DATA:0             # Flag=0 (nicht gefunden)
#JMPI END_CHECK                   # Springe ans Ende
## --- Interne Sprungziele ---
#SET_TRUE:
#SETI REG_FLAG DATA:1             # Flag=1 (gefunden)
#END_CHECK:
## --- Aufräumen & Rücksprung ---
#RET
#.ENDR

# --- Routine: stdlib.CHECK_CELL ---
#
# Zweck:
#   Prüft das Nachbarfeld in gegebener Richtung auf einen bestimmten Typ
#   und setzt ein Flag (1/0) als Ergebnis zurück.
#
# Optimierung V2:
#   Benötigt nur 2 Register und nutzt den Stack zur Zwischenspeicherung.
#
# Parameter:
#   REG_DIR: Einheits-Vektor zur Zielzelle. (Wird am Ende wiederhergestellt.)
#   REG_TF : Eingabe: Typ-Literal (z.B. DATA:0). Ausgabe: DATA:1 (gefunden) / DATA:0 (nicht).
.ROUTINE CHECK_CELL REG_DIR REG_TF
# --- Vorbereitung ---
PUSH REG_DIR        # Richtung sichern
PUSH REG_TF         # Gewünschten Typ sichern
# --- Logik ---
SCAN REG_TF REG_DIR # Symbol der Zielzelle in REG_TF laden
POP REG_DIR         # REG_DIR hält jetzt das gesicherte Typ-Literal
IFTR REG_TF REG_DIR # Typvergleich
JMPI SET_TRUE
SETI REG_TF DATA:0  # nicht gefunden
JMPI EXIT
SET_TRUE:
SETI REG_TF DATA:1  # gefunden
# --- Aufräumen & Rücksprung ---
EXIT:
POP REG_DIR         # ursprüngliche Richtung wiederherstellen
RET
.ENDR