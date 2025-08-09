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


# --- Routine: stdlib.TURN_LEFT ---
#
# Zweck:
#   Nimmt einen Einheitsvektor und dreht ihn um 90 Grad gegen den Uhrzeigersinn.
#
# Eigenschaften:
#   - Symmetrisch zu TURN_RIGHT.
#   - Benötigt 2 Register: Zielregister und ein temporäres Register (wird via Stack gesichert).
#
# Parameter:
#   REG_DV:  Das Register, das den zu drehenden Vektor enthält.
#            Das Ergebnis wird in dasselbe Register zurückgeschrieben.
#   REG_TMP: Ein temporäres Register, das intern verwendet wird.
#            Sein ursprünglicher Wert wird am Ende wiederhergestellt.
#
.ROUTINE TURN_LEFT REG_DV REG_TMP

    # --- Vorbereitung ---
    # Sichert den ursprünglichen Wert von REG_TMP auf dem Stack.
    PUSH REG_TMP

    # --- Logik ---
    # Fall 1: RECHTS (1|0) -> HOCH (0|-1)
    SETV REG_TMP 1|0
    IFR REG_DV REG_TMP
    JMPI SET_UP

    # Fall 2: HOCH (0|-1) -> LINKS (-1|0)
    SETV REG_TMP 0|-1
    IFR REG_DV REG_TMP
    JMPI SET_LEFT

    # Fall 3: LINKS (-1|0) -> RUNTER (0|1)
    SETV REG_TMP -1|0
    IFR REG_DV REG_TMP
    JMPI SET_DOWN

    # Fall 4: RUNTER (0|1) -> RECHTS (1|0) (Default-Fall)
    SETV REG_DV 1|0
    JMPI EXIT

    # --- Interne Zuweisungs-Labels ---
SET_UP:
    SETV REG_DV 0|-1
    JMPI EXIT
SET_LEFT:
    SETV REG_DV -1|0
    JMPI EXIT
SET_DOWN:
    SETV REG_DV 0|1
    # Kein JMPI nötig, da es direkt vor dem Exit-Label steht.

    # --- Aufräumen & Rücksprung ---
EXIT:
    POP REG_TMP     # Den ursprünglichen Wert von REG_TMP wiederherstellen.
    RET             # Kehrt zum ursprünglichen Aufrufer zurück.
.ENDR


# --- Routine: stdlib.CHECK_EMPTY ---
#
# Zweck:
#   Prüft die Nachbarzelle in gegebener Richtung auf "leer" und setzt ein Flag (1/0).
#
# Semantik:
#   - REG_TF wird am Ende auf DATA:1 gesetzt, wenn die Zielzelle leer ist, sonst DATA:0.
#   - REG_DIR wird am Ende auf seinen ursprünglichen Wert zurückgesetzt.
#
# Parameter:
#   REG_DIR: Einheits-Vektor zur Zielzelle. (Wird am Ende wiederhergestellt.)
#   REG_TF : Ausgabe: DATA:1 (leer) / DATA:0 (nicht leer).
.ROUTINE CHECK_EMPTY REG_DIR REG_TF
# --- Vorbereitung ---
PUSH REG_DIR              # Richtung sichern

# --- Logik ---
# Wir wollen auf den Typ "EMPTY" vergleichen. Dafür benötigen wir den gewünschten Typ
# beim IFTR in einem Register; analog zu CHECK_CELL nutzen wir den Stack-Trick:
SETI REG_TF CODE:0        # Gesuchter Typ-Literal: EMPTY
PUSH REG_TF               # Gesuchten Typ auf den Stack legen
SCAN REG_TF REG_DIR       # Symbol der Zielzelle in REG_TF laden
POP REG_DIR               # REG_DIR enthält jetzt den gesuchten Typ-Literal (EMPTY)
IFTR REG_TF REG_DIR       # Typvergleich REG_TF (gescannter Typ) vs. EMPTY
JMPI SET_TRUE
SETI REG_TF DATA:0        # nicht leer
JMPI EXIT
SET_TRUE:
SETI REG_TF DATA:1        # leer

# --- Aufräumen & Rücksprung ---
EXIT:
POP REG_DIR               # ursprüngliche Richtung wiederherstellen
RET
.ENDR